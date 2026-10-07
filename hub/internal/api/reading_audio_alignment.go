package api

// The read-along timeline of an audiobook. A book with a read-along edition has,
// beside its audio, an EPUB whose SMIL says which sentence is spoken where in
// that audio (internal/reading/alignment.go reads it). Storyteller cut the audio
// again when it aligned it, and numbered the pieces in its own order, so a
// piece is not a track of the hub's: it is matched to one by length, completely
// and unambiguously or not at all, and the manifest says which piece is which
// track and where in it the piece starts. With that, a sentence and a moment of
// the audiobook are the same place, exactly.

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"path"
	"sort"
	"strconv"
	"strings"

	"ayaneohub/internal/cache"
	readingdomain "ayaneohub/internal/reading"

	"ayaneohub/internal/adapters/storyteller"
)

// Why a read-along edition that is there cannot be used, as alignmentReason says
// it: the edition's file (the same three words as audio_not_streamable, for the
// same causes), or the narration it holds against the book's files.
const (
	alignReasonUnmapped    = "unmapped_root"
	alignReasonMissing     = "missing_file"
	alignReasonUnreadable  = "unreadable"
	alignReasonNoNarration = "no_narration"
	// The narrated files are not the book's files: there are more or fewer of them,
	// or they are not named as Storyteller names its own.
	alignReasonFiles = "files_do_not_match"
	// A narrated file is no length of any file of the book.
	alignReasonLengths = "lengths_do_not_match"
	// Two narrated files are the length of two files of the book and nothing tells
	// them apart.
	alignReasonAmbiguous = "lengths_ambiguous"
)

// ReadingAudioAlignment lists the audio files of a read-along edition as the
// edition's own SMIL names them (a path inside the EPUB), each with the track of
// the manifest it is part of and where in that track it begins.
type ReadingAudioAlignment struct {
	Audio []ReadingAlignedAudio `json:"audio"`
}

type ReadingAlignedAudio struct {
	Href    string `json:"href"`
	Track   int    `json:"track"`
	StartMs int64  `json:"startMs"`
}

// audioAlignment is an edition's narration mapped onto a plan's tracks.
type audioAlignment struct {
	narration *readingdomain.Alignment
	// places is where each of the narration's audio files lies, by its index in
	// narration.Files; byTrack the files of each track in the order they start.
	places  []alignedPlace
	byTrack map[int][]int
	// edition is the edition's size and time, for the revision.
	edition string
	listing []ReadingAlignedAudio
	// byOrder says some narrated files could not be told apart by their lengths and
	// were paired in the order they are read (readingdomain.MatchSources).
	byOrder bool
}

type alignedPlace struct {
	track   int
	startMs int64
}

// alignTarget is something a narrated file may be: a file of the book, or a
// chapter of the one file of a lone M4B.
type alignTarget struct {
	track      int
	startMs    int64
	durationMs int64
}

func (a *audioAlignment) manifest() *ReadingAudioAlignment {
	return &ReadingAudioAlignment{Audio: a.listing}
}

// mapNarration pairs each narrated file with what it is, by length, and says why
// when it cannot.
func mapNarration(narration *readingdomain.Alignment, targets []alignTarget) (*audioAlignment, string) {
	sources, err := narration.Sources()
	if err != nil {
		if errors.Is(err, readingdomain.ErrNoAlignment) {
			return nil, alignReasonNoNarration
		}
		return nil, alignReasonFiles
	}
	durations := make([]int64, len(targets))
	for i, target := range targets {
		if target.durationMs <= 0 {
			// A file of unknown length cannot be told from another by it.
			return nil, alignReasonLengths
		}
		durations[i] = target.durationMs
	}
	pairing, err := readingdomain.MatchSources(sources, durations)
	switch {
	case errors.Is(err, readingdomain.ErrAlignmentMismatch):
		return nil, alignReasonLengths
	case errors.Is(err, readingdomain.ErrAlignmentAmbiguous):
		return nil, alignReasonAmbiguous
	case err != nil:
		return nil, alignReasonFiles
	}
	mapped := &audioAlignment{narration: narration, places: make([]alignedPlace, len(narration.Files)), byTrack: map[int][]int{}, byOrder: pairing.ByOrder}
	for i, source := range sources {
		target := targets[pairing.File[i]]
		for k, file := range source.Files {
			mapped.places[file] = alignedPlace{track: target.track, startMs: target.startMs + source.ChunkStartMs[k]}
		}
	}
	for index, file := range narration.Files {
		place := mapped.places[index]
		mapped.byTrack[place.track] = append(mapped.byTrack[place.track], index)
		mapped.listing = append(mapped.listing, ReadingAlignedAudio{Href: file.Entry, Track: place.track, StartMs: place.startMs})
	}
	for _, files := range mapped.byTrack {
		sort.SliceStable(files, func(i, j int) bool { return mapped.places[files[i]].startMs < mapped.places[files[j]].startMs })
	}
	return mapped, ""
}

// sentenceAt is the sentence being spoken at a moment of a track, in the text of
// the edition.
func (a *audioAlignment) sentenceAt(track int, offsetMs int64) (readingdomain.AlignedPar, bool) {
	files := a.byTrack[track]
	if len(files) == 0 {
		return readingdomain.AlignedPar{}, false
	}
	chosen := files[0]
	for _, file := range files {
		if a.places[file].startMs > offsetMs {
			break
		}
		chosen = file
	}
	return a.narration.Sentence(chosen, offsetMs-a.places[chosen].startMs)
}

// placeOf is where a sentence of the text is spoken: a track and a moment in it.
func (a *audioAlignment) placeOf(href, fragment string) (track int, offsetMs int64, ok bool) {
	file, par, found := a.narration.Find(href, fragment)
	if !found {
		return 0, 0, false
	}
	place := a.places[file]
	return place.track, place.startMs + par.BeginMs, true
}

// alignTargets are what the narrated files can be matched to, in the order they
// are played. That is the order that decides between files whose lengths cannot
// tell the narration apart: the narration takes the book's files in the order it
// reads them, and the hub plays them in that order (by their own tags, else as
// Storyteller lists them), not in the order Storyteller lists them.
func (p *audioPlan) alignTargets() []alignTarget {
	targets := make([]alignTarget, len(p.entries))
	for i, entry := range p.entries {
		targets[i] = alignTarget{track: entry.track, startMs: entry.startMs, durationMs: entry.durationMs}
	}
	sort.SliceStable(targets, func(i, j int) bool {
		if targets[i].track != targets[j].track {
			return targets[i].track < targets[j].track
		}
		return targets[i].startMs < targets[j].startMs
	})
	return targets
}

// alignPlan reads the book's read-along edition, if it has one that is ready, and
// maps it onto the plan. An edition that cannot be used is not a failure of the
// plan: the tracks stream as they did, and alignmentReason says what is wrong.
// Only the end of the time allowed for the plan fails it, since a plan built
// from an interrupted read would be held as if it were the truth.
func (s *Server) alignPlan(ctx context.Context, book storyteller.Book, plan *audioPlan) error {
	if !book.Readaloud.Available() {
		return nil
	}
	refuse := func(reason string) error {
		plan.alignmentReason = reason
		// The book and the reason; never the path, which the error would carry.
		slog.Info("read-along edition is not usable", "book", book.ID, "reason", reason)
		return nil
	}
	file, reason := s.openReadaloudEdition(book)
	if reason != "" {
		return refuse(reason)
	}
	defer file.Close()

	narration, _, err := cache.Fetch(ctx, s.cache, alignmentKey(file), cache.ReadingAlignment,
		func(fetchCtx context.Context) (*readingdomain.Alignment, error) {
			narration, err := s.readAlignment(contextReaderAt{ReaderAt: file, ctx: fetchCtx}, file.Size)
			if err == nil && fetchCtx.Err() != nil {
				// The edition's contents are read last and an edition without them is still
				// an edition, so a read cut short there would succeed without them and be
				// kept, for hours, as the edition's.
				return nil, fetchCtx.Err()
			}
			return narration, err
		})
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if errors.Is(err, readingdomain.ErrNoAlignment) {
			return refuse(alignReasonNoNarration)
		}
		return refuse(alignReasonUnreadable)
	}
	mapped, reason := mapNarration(narration, plan.alignTargets())
	if reason != "" {
		return refuse(reason)
	}
	if mapped.byOrder {
		// The book and nothing else, never a path. The lengths of some files could not
		// tell them apart, so which narrated file is which was settled by order.
		slog.Info("read-along edition mapped by order for files of one length", "book", book.ID)
	}
	mapped.edition = strconv.FormatInt(file.Size, 10) + "\x00" + strconv.FormatInt(file.ModTime.UnixNano(), 10)
	plan.alignment = mapped
	plan.revision = audioRevision(plan.tracks, mapped.edition)
	// The edition's own table of contents, placed by its narration, is the book's
	// chapters when it gives enough of them; otherwise the files' own stay.
	if chapters := mapped.bookChapters(plan.trackLengths()); len(chapters) >= minBookChapters {
		plan.chapters = chapters
	}
	return nil
}

// openReadaloudEdition opens the book's read-along edition, read-only, through
// the media mapping, or says why it cannot be: in the words alignmentReason uses.
func (s *Server) openReadaloudEdition(book storyteller.Book) (readingdomain.MediaFile, string) {
	remote := path.Clean(strings.ReplaceAll(strings.TrimSpace(book.Readaloud.Filepath), "\\", "/"))
	if !strings.HasPrefix(remote, "/") {
		return readingdomain.MediaFile{}, alignReasonUnreadable
	}
	file, err := s.openEPUB(s.cfg.Server.MediaRemovalRoots, "storyteller", remote)
	if err != nil {
		var media *readingdomain.MediaError
		switch {
		case errors.As(err, &media) && media.Failure == readingdomain.MediaUnmapped:
			return readingdomain.MediaFile{}, alignReasonUnmapped
		case errors.As(err, &media) && media.Failure == readingdomain.MediaMissing:
			return readingdomain.MediaFile{}, alignReasonMissing
		}
		return readingdomain.MediaFile{}, alignReasonUnreadable
	}
	return file, ""
}

// alignmentKey names an edition as it was when it was read. It is not under
// "reading:", so a rescan, which clears everything the hub knows of Storyteller,
// leaves it: the size and time in it are what say the file has changed.
func alignmentKey(file readingdomain.MediaFile) string {
	return "audio-alignment:" + file.Path + "\x00" + strconv.FormatInt(file.Size, 10) + "\x00" + strconv.FormatInt(file.ModTime.UnixNano(), 10)
}

// contextReaderAt stops reading when the request that wanted the read is over.
type contextReaderAt struct {
	io.ReaderAt
	ctx context.Context
}

func (c contextReaderAt) ReadAt(p []byte, offset int64) (int, error) {
	if err := c.ctx.Err(); err != nil {
		return 0, err
	}
	return c.ReaderAt.ReadAt(p, offset)
}
