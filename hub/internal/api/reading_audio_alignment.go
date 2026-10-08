package api

// The read-along timeline of an audiobook. A book with a read-along edition has,
// beside its audio, an EPUB whose SMIL says which sentence is spoken where in
// that audio (internal/reading/alignment.go reads it). Storyteller cut the audio
// again when it aligned it, and numbered the pieces in its own order, so a
// piece is not a track of the hub's: it is matched to one by length, completely
// and unambiguously or not at all, and the manifest says which piece is which
// track and where in it the piece starts (at a chapter of a file Storyteller cut
// at its chapters, readingdomain.PlaceOnTracks). With that, a sentence and a
// moment of the audiobook are the same place, exactly.

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
	// layout is how the narration lies on the tracks, for the log: each narrated
	// file one of the book's files, or the pieces of one track at its chapters or
	// one after another; unnarrated is how many tracks have no narration.
	layout     string
	unnarrated int
}

const (
	alignLayoutFiles    = "files"
	alignLayoutChapters = "chapters"
	alignLayoutRuns     = "runs"
)

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
// when it cannot: first each narrated file with one file of the book (or one
// chapter of a lone M4B), one for one; when they are not that, each with a track
// that its pieces lie on (readingdomain.PlaceOnTracks: at the track's chapters,
// or one after another), where a track may have no narration. detail is the
// second's own words when both fail, for the log; it names no path.
func mapNarration(narration *readingdomain.Alignment, targets []alignTarget, tracks []readingdomain.AlignedTrack) (mapped *audioAlignment, reason, detail string) {
	if len(narration.Files) == 0 {
		return nil, alignReasonNoNarration, ""
	}
	mapped = &audioAlignment{narration: narration, places: make([]alignedPlace, len(narration.Files)), byTrack: map[int][]int{}, layout: alignLayoutFiles}
	if reason := mapped.byFiles(targets); reason != "" {
		placement, err := narration.PlaceOnTracks(tracks)
		switch {
		case errors.Is(err, readingdomain.ErrAlignmentCount):
			// More narrated files than tracks: the narration is not laid over the
			// tracks, and the first answer says why it is not the book's files.
			return nil, reason, err.Error()
		case err != nil:
			return nil, placeReason(err), err.Error()
		}
		mapped.layout, mapped.byOrder, mapped.unnarrated = alignLayoutRuns, placement.ByOrder, placement.Unnarrated
		if placement.ByChapters > 0 {
			mapped.layout = alignLayoutChapters
		}
		for file := range narration.Files {
			mapped.places[file] = alignedPlace{track: placement.Track[file], startMs: placement.StartMs[file]}
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
	return mapped, "", ""
}

// byFiles places each narrated source on the one file of the book (or chapter of
// a lone M4B) that it is, by length (readingdomain.MatchSources), or says why it
// cannot.
func (a *audioAlignment) byFiles(targets []alignTarget) string {
	sources, err := a.narration.Sources()
	if err != nil {
		return alignReasonFiles
	}
	durations := make([]int64, len(targets))
	for i, target := range targets {
		if target.durationMs <= 0 {
			// A file of unknown length cannot be told from another by it.
			return alignReasonLengths
		}
		durations[i] = target.durationMs
	}
	pairing, err := readingdomain.MatchSources(sources, durations)
	if err != nil {
		return placeReason(err)
	}
	a.byOrder = pairing.ByOrder
	for i, source := range sources {
		target := targets[pairing.File[i]]
		for k, file := range source.Files {
			a.places[file] = alignedPlace{track: target.track, startMs: target.startMs + source.ChunkStartMs[k]}
		}
	}
	return ""
}

// placeReason is alignmentReason for a pairing or placement that failed.
func placeReason(err error) string {
	switch {
	case errors.Is(err, readingdomain.ErrNoAlignment):
		return alignReasonNoNarration
	case errors.Is(err, readingdomain.ErrAlignmentMismatch):
		return alignReasonLengths
	case errors.Is(err, readingdomain.ErrAlignmentAmbiguous):
		return alignReasonAmbiguous
	}
	return alignReasonFiles
}

// alignTracks are the tracks in the order they are played, with the chapters
// each has of its own (a file's marks, or the chapters Storyteller lists for a
// lone M4B), for readingdomain.PlaceOnTracks. It is read before the book's own
// chapters take the place of the files'.
func (p *audioPlan) alignTracks() []readingdomain.AlignedTrack {
	tracks := make([]readingdomain.AlignedTrack, len(p.tracks))
	for i, track := range p.tracks {
		tracks[i].DurationMs = track.DurationMs
	}
	for _, chapter := range p.chapters {
		if chapter.Source == chapterSourceMarks && chapter.Track >= 0 && chapter.Track < len(tracks) {
			tracks[chapter.Track].ChapterMs = append(tracks[chapter.Track].ChapterMs, chapter.StartMs)
		}
	}
	return tracks
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
	refuse := func(reason, detail string) error {
		plan.alignmentReason = reason
		// The book, the reason and what exactly refused it; never the path, which
		// a file's error would carry. readingdomain's own errors name none.
		slog.Info("read-along edition is not usable", "book", book.ID, "reason", reason, "detail", detail)
		return nil
	}
	file, reason := s.openReadaloudEdition(book)
	if reason != "" {
		return refuse(reason, "")
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
			return refuse(alignReasonNoNarration, "")
		}
		var detail string
		if errors.Is(err, readingdomain.ErrBadAlignment) {
			detail = err.Error()
		}
		return refuse(alignReasonUnreadable, detail)
	}
	mapped, reason, detail := mapNarration(narration, plan.alignTargets(), plan.alignTracks())
	if reason != "" {
		return refuse(reason, detail)
	}
	if mapped.byOrder {
		// The book and nothing else, never a path. The lengths of some files could not
		// tell them apart, so which narrated file is which was settled by order.
		slog.Info("read-along edition mapped by order for files of one length", "book", book.ID)
	}
	if mapped.layout != alignLayoutFiles || narration.PastEnd+narration.CutAtEnd > 0 {
		// Counts only. Its pieces lie on the tracks' chapters or one after another,
		// some tracks with nothing narrated; sentences past the end of their audio
		// were left out or ended there (readingdomain.endAudioAt).
		slog.Info("read-along edition mapped", "book", book.ID, "layout", mapped.layout, "files", len(narration.Files),
			"tracks", len(plan.tracks), "unnarratedTracks", mapped.unnarrated, "sentencesPastEnd", narration.PastEnd, "sentencesCutAtEnd", narration.CutAtEnd)
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
	return s.openStorytellerEPUB(book.Readaloud.Filepath)
}

// openStorytellerEPUB is the way to any of a Storyteller book's EPUB files from the
// path Storyteller reports for it (the ebook, the read-along edition): through
// media_removal_roots, read-only, opened once. It says why it cannot be opened in
// the same words, and names no path.
func (s *Server) openStorytellerEPUB(storytellerPath string) (readingdomain.MediaFile, string) {
	remote := path.Clean(strings.ReplaceAll(strings.TrimSpace(storytellerPath), "\\", "/"))
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
