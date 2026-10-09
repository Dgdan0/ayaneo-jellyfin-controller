package api

// The listening place. Storyteller keeps one position per account and book, a
// Readium locator and a timestamp, and its own apps write an audiobook's place as
// the file's name and a time in it. This is the hub's reading and writing of
// that one row in audio terms: a track of the manifest, and a moment in it.
//
// A place written here is Storyteller's audio form, with the files in
// Storyteller's manifest order (a total taken in that order is what its apps
// work out), whatever order the hub plays them in. A place read here may be
// that, or the text place a reader of the ebook or the read-along wrote: on a
// book with a read-along edition a sentence is a moment of the audio exactly,
// and on any other a proportion of the whole is the best there is, and says so.
//
// It takes the EPUB route's turn (the same lock) and answers its conflict the
// same way, so a reader and a listener of one book cannot lose each other's
// place.

import (
	"encoding/json"
	"errors"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/httpx"
)

const (
	// A player counts a file's end a little differently from the manifest's length,
	// which is Storyteller's. A place within this much past it is the end.
	audioEndSlackMs = 1000
	// The last stretch of the last track is the end of the book.
	audioFinishedMs = 2000
	// A total this far through is what Storyteller counts as finished, for a place
	// whose track the hub does not know.
	audioFinishedShare = 0.999

	actionSaveAudioPosition = "save_audio_position"
)

var audioTrackIDPattern = regexp.MustCompile(`^t_[0-9a-f]{12}$`)

// ReadingAudioPositionResponse is where the book's listener is, or nothing.
type ReadingAudioPositionResponse struct {
	WorkID       string                `json:"workId"`
	SourceItemID string                `json:"sourceItemId"`
	Position     *ReadingAudioPosition `json:"position"`
	// ResetAt is when the book was last started over (#60); absent when it never was.
	ResetAt int64 `json:"resetAt,omitempty"`
	// Device and ByThisDevice are the epub position's (#62).
	Device       string `json:"device,omitempty"`
	ByThisDevice bool   `json:"byThisDevice,omitempty"`
}

// ReadingAudioPosition is a moment of a track of the manifest. GlobalMs is the
// moment in the whole book as the hub plays it (the tracks one after another).
// Exact says the place was written as an audio place, or is a sentence of a
// read-along edition; otherwise it is a proportion of the whole, a guess an app
// should ask about before it jumps. Form is the form the place was written in,
// audio or text, and Sentence, on a book with a read-along edition, is the
// sentence being spoken there. Timestamp is Storyteller's own.
type ReadingAudioPosition struct {
	TrackID   string                `json:"trackId"`
	Track     int                   `json:"track"`
	OffsetMs  int64                 `json:"offsetMs"`
	GlobalMs  int64                 `json:"globalMs"`
	Completed bool                  `json:"completed"`
	Exact     bool                  `json:"exact"`
	Form      string                `json:"form"`
	Timestamp int64                 `json:"timestamp"`
	UpdatedAt string                `json:"updatedAt,omitempty"`
	Sentence  *ReadingAudioSentence `json:"sentence,omitempty"`
}

// ReadingAudioSentence names a sentence of the read-along edition: its text
// document (a path inside the EPUB) and the id of its span.
type ReadingAudioSentence struct {
	Href     string `json:"href"`
	Fragment string `json:"fragment"`
}

// audioPlace is a stored position as the hub reads it.
type audioPlace struct {
	track     int
	offsetMs  int64
	globalMs  int64
	exact     bool
	form      string
	completed bool
}

// positionProblem says why the plan cannot keep a place, or nothing. A lone M4B
// is written as the virtual chapter files of Storyteller's manifest, which needs
// every chapter's length to know where one ends and the next begins.
func (p *audioPlan) positionProblem() string {
	if len(p.tracks) == 0 || len(p.entries) == 0 {
		return audioReasonLayout
	}
	if len(p.entries) != len(p.tracks) {
		for _, entry := range p.entries {
			if entry.durationMs <= 0 {
				return audioReasonLayout
			}
		}
	}
	return ""
}

func (p *audioPlan) trackByID(id string) (int, bool) {
	for i, track := range p.tracks {
		if track.ID == id {
			return i, true
		}
	}
	return 0, false
}

// globalOf is a moment of a track as a moment of the whole, the tracks played one
// after another.
func (p *audioPlan) globalOf(track int, offsetMs int64) int64 {
	var before int64
	for i := 0; i < track && i < len(p.tracks); i++ {
		before += p.tracks[i].DurationMs
	}
	return before + offsetMs
}

// byGlobal is the track and the moment a moment of the whole is in.
func (p *audioPlan) byGlobal(global int64) (int, int64) {
	var start int64
	for i, track := range p.tracks {
		if global < start+track.DurationMs || i == len(p.tracks)-1 {
			return i, min(max(global-start, 0), track.DurationMs)
		}
		start += track.DurationMs
	}
	return 0, 0
}

func (p *audioPlan) placeAt(track int, offsetMs int64, exact bool, form string) audioPlace {
	place := audioPlace{track: track, offsetMs: offsetMs, globalMs: p.globalOf(track, offsetMs), exact: exact, form: form}
	if last := len(p.tracks) - 1; track == last {
		duration := p.tracks[last].DurationMs
		place.completed = duration > 0 && offsetMs >= duration-audioFinishedMs
	}
	return place
}

// storedLocator is the part of a Readium locator the hub reads. A locator
// whose fields are not these types is not one it can use.
type storedLocator struct {
	Href      string `json:"href"`
	Type      string `json:"type"`
	Locations struct {
		Fragments        []string `json:"fragments"`
		Progression      *float64 `json:"progression"`
		TotalProgression *float64 `json:"totalProgression"`
	} `json:"locations"`
}

// placeFor reads a stored locator as a place of the audiobook, or says there is
// none to read.
//
// An audio place (the file's name, in Storyteller's manifest) is exact, and
// finished when it is at the end of the last track the hub plays: not when a
// total taken in the manifest's order says so, which for a book whose files are
// played in another order is the end of some other file. A sentence of the
// read-along edition is exact too. Anything else with a total is a proportion
// of the book.
func (p *audioPlan) placeFor(raw json.RawMessage) (audioPlace, bool) {
	var locator storedLocator
	if len(raw) == 0 || json.Unmarshal(raw, &locator) != nil {
		return audioPlace{}, false
	}
	total := locator.Locations.TotalProgression

	if index, found := p.entryIndex(locator.Href); found {
		entry := p.entries[index]
		offset, known := mediaFragmentMs(locator.Locations.Fragments)
		if !known {
			if progression := locator.Locations.Progression; progression != nil && !math.IsNaN(*progression) {
				offset = int64(math.Round(*progression * float64(entry.durationMs)))
			}
		}
		offset = min(max(offset, 0), entry.durationMs)
		return p.placeAt(entry.track, entry.startMs+offset, true, "audio"), true
	}

	if p.alignment != nil {
		if fragment := textFragment(locator.Locations.Fragments); fragment != "" || strings.Contains(locator.Href, "#") {
			if track, offset, found := p.alignment.placeOf(locator.Href, fragment); found {
				place := p.placeAt(track, offset, true, "text")
				place.completed = place.completed || (total != nil && *total >= audioFinishedShare)
				return place, true
			}
		}
	}

	if total == nil || math.IsNaN(*total) {
		return audioPlace{}, false
	}
	global := min(max(int64(math.Round(*total*float64(p.totalMs))), 0), p.totalMs)
	track, offset := p.byGlobal(global)
	form := "text"
	if strings.HasPrefix(strings.ToLower(locator.Type), "audio/") {
		form = "audio"
	}
	place := p.placeAt(track, offset, false, form)
	place.completed = place.completed || *total >= audioFinishedShare
	return place, true
}

// entryIndex finds the entry of Storyteller's manifest a locator's href names: as
// written, or with %XX decoded and a leading slash dropped, since both spellings
// are in its table.
func (p *audioPlan) entryIndex(href string) (int, bool) {
	want := strings.TrimLeft(strings.TrimSpace(href), "/")
	if want == "" {
		return 0, false
	}
	for i, entry := range p.entries {
		if strings.TrimLeft(entry.href, "/") == want {
			return i, true
		}
	}
	decoded := unescapeHref(want)
	for i, entry := range p.entries {
		if unescapeHref(strings.TrimLeft(entry.href, "/")) == decoded {
			return i, true
		}
	}
	return 0, false
}

// unescapeHref decodes %XX when the text is a valid URL reference. A file name
// that merely holds a percent sign ("100% Pure.mp3") is not one, and stays itself.
func unescapeHref(text string) string {
	if decoded, err := url.PathUnescape(text); err == nil {
		return decoded
	}
	return text
}

// mediaFragmentMs reads the time of a "t=12.5" fragment, which may carry an end
// ("t=12.5,20") or a clock name ("t=npt:12.5").
func mediaFragmentMs(fragments []string) (int64, bool) {
	for _, fragment := range fragments {
		value, found := strings.CutPrefix(fragment, "t=")
		if !found {
			continue
		}
		value = strings.TrimPrefix(value, "npt:")
		value, _, _ = strings.Cut(value, ",")
		seconds, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
		if err != nil || math.IsNaN(seconds) || seconds < 0 || seconds > 1e9 {
			continue
		}
		return int64(math.Round(seconds * 1000)), true
	}
	return 0, false
}

// textFragment is the id of a span a text locator names: the first fragment that
// is not a media time.
func textFragment(fragments []string) string {
	for _, fragment := range fragments {
		if fragment != "" && !strings.HasPrefix(fragment, "t=") {
			return fragment
		}
	}
	return ""
}

type audioLocator struct {
	Href      string         `json:"href"`
	Title     string         `json:"title,omitempty"`
	Type      string         `json:"type,omitempty"`
	Locations audioLocations `json:"locations"`
}

type audioLocations struct {
	Fragments        []string `json:"fragments"`
	Progression      float64  `json:"progression"`
	TotalProgression float64  `json:"totalProgression"`
}

// locatorFor is the place as Storyteller's audio apps write it: the manifest's
// file (a virtual chapter file for a lone M4B) and a time in it, how far through
// that file, and how far through the book with the files in the manifest's order.
// Finishing is the end of the last track the hub plays, and a total of 1.
func (p *audioPlan) locatorFor(track int, offsetMs int64, completed bool) (json.RawMessage, bool) {
	if len(p.tracks) == 0 {
		return nil, false
	}
	if completed {
		track = len(p.tracks) - 1
		offsetMs = p.tracks[track].DurationMs
	}
	index := -1
	for i, entry := range p.entries {
		if entry.track == track && entry.startMs <= offsetMs {
			index = i
		}
	}
	if index < 0 {
		return nil, false
	}
	entry := p.entries[index]
	inside := min(max(offsetMs-entry.startMs, 0), entry.durationMs)
	var before, total int64
	for i, other := range p.entries {
		if i < index {
			before += other.durationMs
		}
		total += other.durationMs
	}
	progression, totalProgression := share(inside, entry.durationMs), share(before+inside, total)
	if completed {
		progression, totalProgression = 1, 1
	}
	locator := audioLocator{
		Href: entry.href, Title: entry.title, Type: entry.mime,
		Locations: audioLocations{Fragments: []string{clockFragment(inside)}, Progression: progression, TotalProgression: totalProgression},
	}
	encoded, err := json.Marshal(locator)
	return encoded, err == nil
}

func share(part, whole int64) float64 {
	if whole <= 0 {
		return 0
	}
	return min(max(float64(part)/float64(whole), 0), 1)
}

// clockFragment is a moment as Storyteller writes it: seconds with three decimals.
func clockFragment(ms int64) string {
	return "t=" + strconv.FormatInt(ms/1000, 10) + "." + leftPad3(ms%1000)
}

func leftPad3(n int64) string {
	text := strconv.FormatInt(n, 10)
	return strings.Repeat("0", 3-len(text)) + text
}

// position is a stored record as the hub's audio route answers it.
func (p *audioPlan) position(record *storyteller.PositionRecord) *ReadingAudioPosition {
	if record == nil {
		return nil
	}
	place, ok := p.placeFor(record.Locator)
	if !ok {
		return nil
	}
	out := &ReadingAudioPosition{
		TrackID: p.tracks[place.track].ID, Track: place.track, OffsetMs: place.offsetMs, GlobalMs: place.globalMs,
		Completed: place.completed, Exact: place.exact, Form: place.form,
		Timestamp: record.Timestamp, UpdatedAt: record.UpdatedAt,
	}
	if p.alignment != nil && place.exact {
		if par, found := p.alignment.sentenceAt(place.track, place.offsetMs); found {
			out.Sentence = &ReadingAudioSentence{Href: par.Text, Fragment: par.Fragment}
		}
	}
	return out
}

func (s *Server) handleReadingAudioPosition(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	book, _, ok := s.resolveStorytellerAudiobook(w, r, ctx)
	if !ok {
		return
	}
	plan, _, err := s.audioPlanFor(ctx, book)
	if err != nil {
		s.writeAudioPlanError(w, r, book.ID, err)
		return
	}
	if reason := plan.positionProblem(); reason != "" {
		s.writeAudioPlanError(w, r, book.ID, &audioFailure{reason})
		return
	}
	workID, sourceItemID := r.PathValue("workId"), r.PathValue("sourceItemId")

	if r.Method == http.MethodGet {
		record, err := s.currentStorytellerPosition(ctx, book.ID)
		if err != nil {
			writeUpstreamError(w, r, "storyteller", err)
			return
		}
		shown := ReadingAudioPositionResponse{WorkID: workID, SourceItemID: sourceItemID, Position: plan.position(record),
			ResetAt: s.readingResets.source("storyteller", strconv.FormatInt(book.ID, 10))}
		if shown.Position != nil {
			shown.Device, shown.ByThisDevice = s.placeDevice(r, book.ID, shown.Position.Timestamp)
		}
		writeJSON(w, http.StatusOK, shown)
		return
	}

	// The client's own clock (`timestamp`) is accepted so a body written for the
	// EPUB route is understood, and is not used: see the stamp below.
	var body struct {
		TrackID   string          `json:"trackId"`
		OffsetMs  *int64          `json:"offsetMs"`
		Completed bool            `json:"completed"`
		Timestamp json.RawMessage `json:"timestamp"`
		Expected  json.RawMessage `json:"expected"`
		// ResetSeen is the start over this device last knew of, when it sends one (#60).
		ResetSeen *int64 `json:"resetSeen"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil || body.OffsetMs == nil || *body.OffsetMs < 0 || !audioTrackIDPattern.MatchString(body.TrackID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid audiobook reading position"})
		return
	}
	track, found := plan.trackByID(body.TrackID)
	offset := *body.OffsetMs
	if !found || offset > plan.tracks[track].DurationMs+audioEndSlackMs {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid audiobook reading position"})
		return
	}
	offset = min(offset, plan.tracks[track].DurationMs)
	expectation, valid := parseExpectedPlace(body.Expected)
	if !valid {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid expected reading position"})
		return
	}
	locator, ok := plan.locatorFor(track, offset, body.Completed)
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid audiobook reading position"})
		return
	}

	// The same turn as the EPUB route's, so a reader and a listener of one book
	// queue rather than race.
	unlock := lockReadingCheckpoint("storyteller", strconv.FormatInt(book.ID, 10))
	defer unlock()
	current, held, err := s.storytellerPlace(ctx, book.ID)
	if err != nil {
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	if s.staleAfterReset("storyteller", strconv.FormatInt(book.ID, 10), body.ResetSeen, held, func() bool {
		return expectation.check && !expectation.nothing && expectation.holds(plan, held)
	}) {
		writePositionReset(w, r)
		return
	}
	if !expectation.holds(plan, current) {
		writePositionChanged(w, r)
		return
	}
	// The hub stamps the write once the check has passed, as the EPUB route does.
	stamp := s.stampAfterReset(book.ID, held)
	if err := s.storyteller.SavePosition(ctx, book.ID, locator, stamp); err != nil {
		var upstream *httpx.Error
		if errors.As(err, &upstream) && upstream.Status == http.StatusConflict {
			writeStorytellerNewerPosition(w, r)
			return
		}
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	s.placeWriters.note("storyteller", strconv.FormatInt(book.ID, 10), TokenFrom(r.Context()).Label, stamp)
	s.invalidateStorytellerPosition(sourceItemID)
	writeJSON(w, http.StatusOK, struct {
		OK        bool   `json:"ok"`
		Action    string `json:"action"`
		Timestamp int64  `json:"timestamp"`
	}{OK: true, Action: actionSaveAudioPosition, Timestamp: stamp})
}

// expectedPlace is the place a writer believes the book is at, which the write
// is checked against: nothing at all (a null), a track and a moment, or no
// opinion (the field absent), which skips the check.
type expectedPlace struct {
	check   bool
	nothing bool
	trackID string
	offset  int64
}

func parseExpectedPlace(raw json.RawMessage) (expectedPlace, bool) {
	if len(raw) == 0 {
		return expectedPlace{}, true
	}
	if string(raw) == "null" {
		return expectedPlace{check: true, nothing: true}, true
	}
	var place struct {
		TrackID  *string `json:"trackId"`
		OffsetMs *int64  `json:"offsetMs"`
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&place); err != nil || place.TrackID == nil || place.OffsetMs == nil || *place.OffsetMs < 0 {
		return expectedPlace{}, false
	}
	return expectedPlace{check: true, trackID: *place.TrackID, offset: *place.OffsetMs}, true
}

// holds compares the believed place with what Storyteller holds now, as the same
// place the hub would read it as (the form it was written in may differ), to the
// millisecond.
func (e expectedPlace) holds(plan *audioPlan, current *storyteller.PositionRecord) bool {
	if !e.check {
		return true
	}
	var place *audioPlace
	if current != nil {
		if read, ok := plan.placeFor(current.Locator); ok {
			place = &read
		}
	}
	if e.nothing {
		return place == nil
	}
	return place != nil && plan.tracks[place.track].ID == e.trackID && place.offsetMs == e.offset
}
