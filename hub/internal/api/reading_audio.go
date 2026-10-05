package api

// Audiobook tracks. Storyteller keeps an audiobook as a folder; asking it for
// the book (`/files?format=audiobook`) makes it write a ZIP of every file to a
// temporary folder and hash the lot before the first byte, so the app waited,
// downloaded it all and extracted it before the first sound (AUDIO_PLAN.md).
//
// The hub already maps Storyteller's folders onto this PC for deletion, so it
// serves the files itself: a manifest of the tracks in the order they are to be
// played (this file), and each track's bytes by Range (reading_audio_track.go).
// `/file?format=audiobook`, the ZIP, is untouched for apps that do not know the
// new routes.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

const (
	// Why a book cannot be streamed, for the app to branch on and an operator to
	// read in the log: the mapping is missing, a file is, or the folder is not
	// a shape the hub reads.
	audioReasonUnmapped = "unmapped_root"
	audioReasonMissing  = "missing_file"
	audioReasonLayout   = "unsupported_layout"

	codeAudioNotStreamable = "audio_not_streamable"
	codeAudioChanged       = "audio_changed"

	// A plan is a few hundred opens and stats and, the first time, as many
	// probes; this is for a disk that is asleep.
	audioPlanTimeout = 25 * time.Second
)

// ReadingAudioManifest is what an app needs to play an audiobook from the hub:
// its tracks in the order to play them, each with an id that survives a rescan,
// and the chapters inside them. `revision` names this list of files; a track
// request carries it back (`?rev=`) and is refused with 412 audio_changed if
// the files have changed since, so a rescan cannot play another file under an
// old index. A book with a read-along edition folds that edition into its
// revision too, since its alignment is part of what the manifest says.
//
// aligned says the book's read-along edition has been read and each of its audio
// files matched to a track (reading_audio_alignment.go); alignment lists them. An
// edition that is there and cannot be used says why in alignmentReason instead,
// and a book with no edition says nothing.
type ReadingAudioManifest struct {
	WorkID       string `json:"workId"`
	SourceItemID string `json:"sourceItemId"`
	Revision     string `json:"revision"`
	Narrator     string `json:"narrator"`
	// TotalMs is the tracks' lengths summed, as Storyteller's own apps work out
	// how far through a book a place is.
	TotalMs         int64                  `json:"totalMs"`
	Aligned         bool                   `json:"aligned"`
	Tracks          []ReadingAudioTrack    `json:"tracks"`
	Chapters        []ReadingAudioChapter  `json:"chapters"`
	Alignment       *ReadingAudioAlignment `json:"alignment,omitempty"`
	AlignmentReason string                 `json:"alignmentReason,omitempty"`
	Cache           CacheInfo              `json:"cache"`
}

type ReadingAudioTrack struct {
	// Index is the {n} of the track's route.
	Index int `json:"index"`
	// ID is "t_" and twelve hex digits of a hash of the file's name in
	// Storyteller. It stays the same when the order changes, so a saved place
	// can name a track; the name itself never leaves the hub.
	ID    string `json:"id"`
	Title string `json:"title"`
	// DurationMs is Storyteller's own length for the track.
	DurationMs int64  `json:"durationMs"`
	Bytes      int64  `json:"bytes"`
	Mime       string `json:"mime"`
	// ETag is the strong validator the track's bytes are served with.
	ETag string `json:"etag"`
}

// ReadingAudioChapter is a chapter mark inside a track. StartMs counts from the
// start of that track, so a player seeks to it as (track, StartMs).
type ReadingAudioChapter struct {
	Title   string `json:"title"`
	StartMs int64  `json:"startMs"`
	Track   int    `json:"track"`
}

// audioPlan is the hub's reading of one audiobook against the disk: the manifest
// it answers with and what the bytes route needs to serve a track. It is built
// from Storyteller's record and the files themselves and held for a minute.
type audioPlan struct {
	narrator string
	totalMs  int64
	revision string
	tracks   []audioTrack
	chapters []ReadingAudioChapter
	// entries are Storyteller's manifest as it was read, in its order: what a
	// place is written against and read back from (reading_audio_position.go).
	entries []manifestEntry
	// alignment is the read-along edition mapped onto the tracks, or nil, in which
	// case alignmentReason says why when there is an edition (and is empty when
	// there is none).
	alignment       *audioAlignment
	alignmentReason string
}

// manifestEntry is one link of Storyteller's audiobook manifest and where it
// lies in the hub's tracks. For a folder of files it is a file: one track, from
// its start. For a lone M4B it is a chapter, a name that exists nowhere on disk
// (Storyteller's virtual "00000-00001.mp3"), and where it lies is the chapter's
// stretch of the one track.
type manifestEntry struct {
	// href, mime and title are as Storyteller wrote them.
	href, mime, title string
	// durationMs is Storyteller's length for it (the probe's when it gave none).
	durationMs int64
	// track is the hub's track it lies in, startMs where in that track it begins.
	track   int
	startMs int64
}

type audioTrack struct {
	ReadingAudioTrack
	// remote is Storyteller's path to the file, resolved again for every request
	// and never sent anywhere.
	remote string
	// href is Storyteller's own name for the file; manifestIndex its place in
	// Storyteller's manifest. Places are written back to Storyteller in that
	// order, whatever order the tags give here.
	href          string
	manifestIndex int
	modNano       int64
}

func (p *audioPlan) manifest(workID, sourceItemID string, info CacheInfo) ReadingAudioManifest {
	tracks := make([]ReadingAudioTrack, len(p.tracks))
	for i, track := range p.tracks {
		tracks[i] = track.ReadingAudioTrack
	}
	chapters := p.chapters
	if chapters == nil {
		chapters = []ReadingAudioChapter{}
	}
	manifest := ReadingAudioManifest{
		WorkID: workID, SourceItemID: sourceItemID, Revision: p.revision, Narrator: p.narrator,
		TotalMs: p.totalMs, Tracks: tracks, Chapters: chapters, AlignmentReason: p.alignmentReason, Cache: info,
	}
	if p.alignment != nil {
		manifest.Aligned, manifest.Alignment = true, p.alignment.manifest()
	}
	return manifest
}

// audioFailure is a book that cannot be streamed, and why.
type audioFailure struct{ reason string }

func (f *audioFailure) Error() string { return "audiobook cannot be streamed: " + f.reason }

var audioNotStreamableMessages = map[string]string{
	audioReasonUnmapped: "The hub has no access to this audiobook's folder.",
	audioReasonMissing:  "A file of this audiobook is missing on the server.",
	audioReasonLayout:   "This audiobook's files are laid out in a way the hub cannot stream.",
}

func audioFailureFor(err error) *audioFailure {
	var failure *audioFailure
	if errors.As(err, &failure) {
		return failure
	}
	var media *readingdomain.MediaError
	if errors.As(err, &media) {
		switch media.Failure {
		case readingdomain.MediaUnmapped:
			return &audioFailure{audioReasonUnmapped}
		case readingdomain.MediaMissing:
			return &audioFailure{audioReasonMissing}
		}
	}
	return &audioFailure{audioReasonLayout}
}

func audioPlanKey(sourceItemID string) string { return "reading:storyteller:audio:" + sourceItemID }

func (s *Server) handleReadingAudioManifest(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	book, freshness, ok := s.resolveStorytellerAudiobook(w, r, ctx)
	if !ok {
		return
	}
	plan, planMeta, err := s.audioPlanFor(ctx, book)
	if err != nil {
		s.writeAudioPlanError(w, r, book.ID, err)
		return
	}
	freshness.add(planMeta)
	writeJSON(w, http.StatusOK, plan.manifest(r.PathValue("workId"), r.PathValue("sourceItemId"), freshness.result()))
}

// resolveStorytellerAudiobook is the audiobook edition of the book a publication
// URL names. The edition is passed in rather than read from the URL: a route
// under /publications/{id}/ does not say which edition it wants, and the EPUB
// resolver's reading of "/position" as "ebook or read-along" would turn every
// audiobook-only book away.
func (s *Server) resolveStorytellerAudiobook(w http.ResponseWriter, r *http.Request, ctx context.Context) (storyteller.Book, cacheSummary, bool) {
	var freshness cacheSummary
	book, meta, ok := s.resolveStorytellerBook(w, r, ctx, "audiobook")
	if !ok {
		return storyteller.Book{}, freshness, false
	}
	freshness.add(meta)
	// A series or a shelf is built from Storyteller's list, which is not where
	// an audiobook's folder and manifest are read from.
	record, recordMeta, err := s.storytellerBookRecord(ctx, book.ID)
	if err != nil {
		writeUpstreamError(w, r, "storyteller", err)
		return storyteller.Book{}, freshness, false
	}
	freshness.add(recordMeta)
	book = s.reconcileStorytellerBook(*record)
	if book.Audiobook == nil || book.Audiobook.Missing {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "this work has no available audiobook"})
		return storyteller.Book{}, freshness, false
	}
	return book, freshness, true
}

func (s *Server) audioPlanFor(ctx context.Context, book storyteller.Book) (*audioPlan, cache.Meta, error) {
	return cache.Fetch(ctx, s.cache, audioPlanKey(strconv.FormatInt(book.ID, 10)), cache.ReadingAudio,
		func(fetchCtx context.Context) (*audioPlan, error) {
			// Whoever asks first builds it for everyone asking while it is built, so
			// one app going away must not abandon the work the others wait for.
			buildCtx, cancel := context.WithTimeout(context.WithoutCancel(fetchCtx), audioPlanTimeout)
			defer cancel()
			return s.buildAudioPlan(buildCtx, book)
		})
}

func (s *Server) writeAudioPlanError(w http.ResponseWriter, r *http.Request, bookID int64, err error) {
	var failure *audioFailure
	if errors.As(err, &failure) {
		// The book and the reason; never the path or the error text, which may
		// carry one.
		slog.Info("audiobook is not streamable", "book", bookID, "reason", failure.reason, "requestId", RequestIDFrom(r.Context()))
		writeError(w, r, http.StatusConflict, Error{Code: codeAudioNotStreamable, Reason: failure.reason, Message: audioNotStreamableMessages[failure.reason]})
		return
	}
	writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Message: "The hub could not read this audiobook's files in time", Retryable: true})
}

// plannedFile is one file of the book as it was when the plan was built.
type plannedFile struct {
	href    string
	remote  string
	path    string
	size    int64
	modNano int64
	kind    readingdomain.AudioKind
	link    storyteller.AudiobookLink
}

// buildAudioPlan reads the book's files. Storyteller's manifest names them (a
// folder of several files) or, for a folder with exactly one .m4b, names its
// chapters, which exist nowhere on disk and are what the file is read as.
func (s *Server) buildAudioPlan(ctx context.Context, book storyteller.Book) (*audioPlan, error) {
	audio := book.Audiobook
	folder := path.Clean(strings.ReplaceAll(strings.TrimSpace(audio.Filepath), "\\", "/"))
	links := audio.Manifest.ReadingOrder
	if !strings.HasPrefix(folder, "/") || len(links) == 0 {
		return nil, &audioFailure{audioReasonLayout}
	}
	plan, err := s.planFiles(ctx, book, folder, links)
	if err != nil {
		return nil, err
	}
	if err := s.alignPlan(ctx, book, plan); err != nil {
		return nil, err
	}
	return plan, nil
}

// planFiles is the plan of the book's own audio: its files, or the one file
// that stands for a manifest of chapters.
func (s *Server) planFiles(ctx context.Context, book storyteller.Book, folder string, links []storyteller.AudiobookLink) (*audioPlan, error) {
	roots := s.cfg.Server.MediaRemovalRoots
	files, failedAt, err := s.resolveAudioFiles(roots, folder, links)
	if err != nil {
		var media *readingdomain.MediaError
		if failedAt == 0 && errors.As(err, &media) && media.Failure == readingdomain.MediaMissing && virtualChapterName.MatchString(links[0].Href) {
			return s.planLoneM4B(roots, folder, book, links)
		}
		return nil, audioFailureFor(err)
	}
	return s.planTracks(ctx, book, files)
}

// Storyteller's names for the chapters of a lone M4B: "00000-00001.mp3".
var virtualChapterName = regexp.MustCompile(`^[0-9]{5}-[0-9]{5}\.[A-Za-z0-9]{2,5}$`)

// resolveAudioFiles opens each of the manifest's files once, to learn its size
// and time, and lets go. The first that cannot be used ends it, with its place.
func (s *Server) resolveAudioFiles(roots []config.MediaRemovalRoot, folder string, links []storyteller.AudiobookLink) ([]plannedFile, int, error) {
	files := make([]plannedFile, 0, len(links))
	seen, seenFiles := map[string]bool{}, map[string]bool{}
	for i, link := range links {
		href, ok := cleanAudioHref(link.Href)
		if !ok || seen[href] {
			return nil, i, &audioFailure{audioReasonLayout}
		}
		seen[href] = true
		remote, file, err := s.openAudioFile(roots, folder, href)
		if err != nil {
			return nil, i, err
		}
		planned := plannedFile{href: href, remote: remote, path: file.Path, size: file.Size, modNano: file.ModTime.UnixNano(), link: link}
		_ = file.Close()
		// Two names for one file (Windows ignores case) would play it twice.
		key := strings.ToLower(planned.path)
		if seenFiles[key] {
			return nil, i, &audioFailure{audioReasonLayout}
		}
		seenFiles[key] = true
		planned.kind, _ = readingdomain.AudioKindOf(href)
		if planned.size == 0 {
			// An empty file is a file that is not there yet.
			return nil, i, &audioFailure{audioReasonMissing}
		}
		files = append(files, planned)
	}
	return files, 0, nil
}

// openAudioFile opens one track of a book's folder, and says which path it was.
// The href is a file name, tried as written. Were a version of Storyteller to
// write it as a URL reference (percent-encoded), the decoded name is tried when
// the written one is not there; a name that merely holds a percent sign
// ("100% Pure.mp3") is found by the first try and never decoded.
func (s *Server) openAudioFile(roots []config.MediaRemovalRoot, folder, href string) (string, readingdomain.MediaFile, error) {
	remote := path.Join(folder, href)
	file, err := s.openMedia(roots, "storyteller", remote)
	var media *readingdomain.MediaError
	if err == nil || !strings.Contains(href, "%") || !errors.As(err, &media) || media.Failure != readingdomain.MediaMissing {
		return remote, file, err
	}
	decoded, decodeErr := url.PathUnescape(href)
	if decodeErr != nil || decoded == href {
		return remote, file, err
	}
	if clean, ok := cleanAudioHref(decoded); ok {
		decodedRemote := path.Join(folder, clean)
		if decodedFile, decodedErr := s.openMedia(roots, "storyteller", decodedRemote); decodedErr == nil {
			return decodedRemote, decodedFile, nil
		}
	}
	return remote, file, err
}

// cleanAudioHref accepts what Storyteller writes: a name, or a path below the
// book's folder, in its cleanest form. Anything that could climb out of the folder,
// or is not one name for one file, is not a track.
func cleanAudioHref(href string) (string, bool) {
	if href == "" || strings.ContainsAny(href, "\\\x00") || strings.HasPrefix(href, "/") || path.Clean(href) != href {
		return "", false
	}
	for _, part := range strings.Split(href, "/") {
		if part == ".." || part == "." {
			return "", false
		}
	}
	return href, true
}

// planLoneM4B is a book whose folder holds exactly one .m4b: Storyteller
// reads that file as its chapters and the manifest lists the chapters. The one
// track is the file; its chapters are the manifest's.
func (s *Server) planLoneM4B(roots []config.MediaRemovalRoot, folder string, book storyteller.Book, links []storyteller.AudiobookLink) (*audioPlan, error) {
	names, err := readingdomain.ListMediaFolder(roots, "storyteller", folder)
	if err != nil {
		return nil, audioFailureFor(err)
	}
	var m4bs []string
	for _, name := range names {
		if strings.EqualFold(path.Ext(name), ".m4b") {
			m4bs = append(m4bs, name)
		}
	}
	switch len(m4bs) {
	case 0:
		return nil, &audioFailure{audioReasonMissing}
	case 1:
	default:
		// Storyteller would have read this folder as its files, not as one book.
		return nil, &audioFailure{audioReasonLayout}
	}
	remote := path.Join(folder, m4bs[0])
	file, err := s.openMedia(roots, "storyteller", remote)
	if err != nil {
		return nil, audioFailureFor(err)
	}
	_ = file.Close()
	if file.Size == 0 {
		return nil, &audioFailure{audioReasonMissing}
	}
	kind, _ := readingdomain.AudioKindOf(m4bs[0])

	var total int64
	complete := true
	entries := make([]manifestEntry, len(links))
	for i, link := range links {
		ms := durationMillis(link.Duration)
		complete = complete && ms > 0
		entries[i] = manifestEntry{href: link.Href, mime: link.Type, title: strings.TrimSpace(link.Title), durationMs: ms, track: 0, startMs: total}
		total += ms
	}
	// Chapters need every length; the whole needs only some.
	var chapters []ReadingAudioChapter
	if complete && len(links) >= 2 {
		for i, entry := range entries {
			title := entry.title
			if title == "" {
				title = fmt.Sprintf("Chapter %d", i+1)
			}
			chapters = append(chapters, ReadingAudioChapter{Title: title, StartMs: entry.startMs, Track: 0})
		}
	}
	title := strings.TrimSpace(book.Title)
	if title == "" {
		title = "Track 1"
	}
	track := audioTrack{
		ReadingAudioTrack: ReadingAudioTrack{
			Index: 0, ID: audioTrackID(m4bs[0]), Title: title, DurationMs: total, Bytes: file.Size, Mime: kind.MIME,
			ETag: audioTrackETag(m4bs[0], file.Size, file.ModTime.UnixNano()),
		},
		remote: remote, href: m4bs[0], manifestIndex: 0, modNano: file.ModTime.UnixNano(),
	}
	plan := newAudioPlan(book, []audioTrack{track}, chapters)
	plan.entries = entries
	return plan, nil
}

// planTracks orders the files, takes their lengths and finds their chapters.
func (s *Server) planTracks(ctx context.Context, book storyteller.Book, files []plannedFile) (*audioPlan, error) {
	probes := make([]probedAudio, len(files))
	var wg sync.WaitGroup
	for i := range files {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			probes[i] = s.probeFile(ctx, probeKey{path: files[i].path, size: files[i].size, modNano: files[i].modNano})
		}(i)
	}
	wg.Wait()
	if err := ctx.Err(); err != nil {
		// Probes that gave up with the request say nothing about the files; a plan
		// built from them would be held for a minute.
		return nil, err
	}

	tracks := make([]audioTrack, len(files))
	entries := make([]manifestEntry, len(files))
	var chapters []ReadingAudioChapter
	for place, index := range tagOrder(probes) {
		file, probe := files[index], probes[index]
		title := strings.TrimSpace(file.link.Title)
		if title == "" {
			title = fmt.Sprintf("Track %d", place+1)
		}
		duration := durationMillis(file.link.Duration)
		if duration == 0 {
			duration = probe.DurationMs
		}
		tracks[place] = audioTrack{
			ReadingAudioTrack: ReadingAudioTrack{
				Index: place, ID: audioTrackID(file.href), Title: title, DurationMs: duration, Bytes: file.size, Mime: file.kind.MIME,
				ETag: audioTrackETag(file.href, file.size, file.modNano),
			},
			remote: file.remote, href: file.href, manifestIndex: index, modNano: file.modNano,
		}
		mime := strings.TrimSpace(file.link.Type)
		if mime == "" {
			mime = file.kind.MIME
		}
		entries[index] = manifestEntry{href: file.link.Href, mime: mime, title: strings.TrimSpace(file.link.Title), durationMs: duration, track: place}
		// Chapters only where a file really has them: two marks or more. One mark
		// is a file that spans itself. Never from file names.
		if marks := chapterMarks(probe.Chapters, duration); len(marks) >= 2 {
			for _, mark := range marks {
				name := mark.Title
				if name == "" {
					name = fmt.Sprintf("Chapter %d", len(chapters)+1)
				}
				chapters = append(chapters, ReadingAudioChapter{Title: name, StartMs: mark.StartMs, Track: place})
			}
		}
	}
	plan := newAudioPlan(book, tracks, chapters)
	plan.entries = entries
	return plan, nil
}

func newAudioPlan(book storyteller.Book, tracks []audioTrack, chapters []ReadingAudioChapter) *audioPlan {
	plan := &audioPlan{narrator: strings.Join(creatorNames(book.Narrators), ", "), tracks: tracks, chapters: chapters}
	for _, track := range tracks {
		plan.totalMs += track.DurationMs
	}
	plan.revision = audioRevision(tracks, "")
	return plan
}

// audioRevision names a list of files: their names, sizes, times and lengths in
// the order they are played, and the read-along edition they are mapped to when
// there is one (its size and time), since a change to that is a change to what
// the manifest says.
func audioRevision(tracks []audioTrack, edition string) string {
	hash := sha256.New()
	for _, track := range tracks {
		fmt.Fprintf(hash, "%s\x00%d\x00%d\x00%d\n", track.href, track.Bytes, track.modNano, track.DurationMs)
	}
	if edition != "" {
		fmt.Fprintf(hash, "edition\x00%s\n", edition)
	}
	return hex.EncodeToString(hash.Sum(nil))[:12]
}

// tagOrder is the order to play the files in, as indexes into Storyteller's
// manifest: by the track numbers in the files' own tags when they are exactly
// 1 to N, each once, and otherwise as Storyteller lists them. Storyteller sorts
// by name, which puts Dark Matter's first track (the file with no suffix) last.
func tagOrder(probes []probedAudio) []int {
	n := len(probes)
	byTag := make([]int, n)
	for i := range byTag {
		byTag[i] = -1
	}
	for index, probe := range probes {
		if probe.Track < 1 || probe.Track > n || byTag[probe.Track-1] != -1 {
			identity := make([]int, n)
			for i := range identity {
				identity[i] = i
			}
			return identity
		}
		byTag[probe.Track-1] = index
	}
	return byTag
}

// chapterMarks keeps the marks a player can use: each later than the one
// before, and inside the track. A file whose marks run backwards, or stand at
// one moment, has none that can be trusted.
func chapterMarks(marks []probedChapter, durationMs int64) []probedChapter {
	var kept []probedChapter
	last := int64(-1)
	for _, mark := range marks {
		if mark.StartMs <= last {
			return nil
		}
		last = mark.StartMs
		if durationMs > 0 && mark.StartMs >= durationMs {
			continue
		}
		kept = append(kept, mark)
	}
	return kept
}

func durationMillis(seconds float64) int64 {
	if seconds <= 0 || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds > 1e9 {
		return 0
	}
	return int64(math.Round(seconds * 1000))
}

// audioTrackID names a track by its file's name in Storyteller, which is stable
// across a rescan and a reordering. Twelve hex digits are enough for the
// tracks of one book.
func audioTrackID(href string) string {
	sum := sha256.Sum256([]byte(href))
	return "t_" + hex.EncodeToString(sum[:])[:12]
}

// audioTrackETag is a strong validator: it changes whenever the file's size or
// time does, and says nothing of the file's name.
func audioTrackETag(href string, size, modNano int64) string {
	sum := sha256.Sum256([]byte(href + "\x00" + strconv.FormatInt(size, 10) + "\x00" + strconv.FormatInt(modNano, 10)))
	return `"` + hex.EncodeToString(sum[:])[:16] + `"`
}
