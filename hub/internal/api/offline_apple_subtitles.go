package api

// An Apple download's subtitles, kept beside the MP4 and refreshed (#45).
//
// The MP4 an Apple device downloads (#5) holds its text subtitles as mov_text, which
// is a fallback: nothing in it can be improved afterwards, so a better subtitle that
// Bazarr fetches next week never reaches the device. The grant therefore lists the
// source's text subtitles as they are now, each with a signature that changes when
// its text does, and serves each as WebVTT, so the app keeps the files beside the
// MP4, fetches only what changed and draws them with its own renderer.
//
// Nothing here touches the hub's repackaged file, so it works after the app has
// released it. Nothing here names a path: Jellyfin hands over the text and the hub
// converts it.

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/httpx"
	"ayaneohub/internal/repackage"
	"ayaneohub/internal/webvtt"
)

const (
	appleSubtitleMaxExternal = 24
	appleSubtitleWorkers     = 4
	codeSubtitleUnreadable   = "subtitle_unreadable"
	codeSourceChanged        = "source_changed"

	// Why a text subtitle is not offered.
	reasonUnreadable = "unreadable"
	reasonTooMany    = "too_many"
)

var appleSubtitleKeyPattern = regexp.MustCompile(`^[a-z0-9-]{1,48}$`)

// AppleSubtitleTrack is one text subtitle the app can keep.
type AppleSubtitleTrack struct {
	// Key names the track for as long as it exists: the app stores its file under it.
	Key string `json:"key"`
	// SourceIndex is Jellyfin's index now, which moves when a sidecar is added; the app never keys on it.
	SourceIndex     int    `json:"sourceIndex"`
	Language        string `json:"language"`
	Title           string `json:"title"`
	Label           string `json:"label"`
	Codec           string `json:"codec"`
	External        bool   `json:"external"`
	Default         bool   `json:"default"`
	Forced          bool   `json:"forced"`
	HearingImpaired bool   `json:"hearingImpaired"`
	// RTL is whether the language is written right to left.
	RTL bool `json:"rtl"`
	// Signature changes when the track's WebVTT does; the same value, quoted, is the
	// ETag of the track's file.
	Signature string `json:"signature"`
	URL       string `json:"url"`
	// MP4Index is where the same track sits among the MP4's subtitle options; absent
	// for a track that arrived after the MP4 was made.
	MP4Index *int `json:"mp4Index,omitempty"`
}

// AppleSubtitleOmitted is a subtitle the hub does not offer as WebVTT, and why.
type AppleSubtitleOmitted struct {
	// Key is set for a text track that cannot be read right now: it has not gone, so
	// the app keeps the file it has.
	Key         string `json:"key,omitempty"`
	SourceIndex int    `json:"sourceIndex"`
	Language    string `json:"language"`
	Label       string `json:"label"`
	Codec       string `json:"codec"`
	External    bool   `json:"external"`
	Reason      string `json:"reason"`
}

type AppleSubtitleList struct {
	GrantID string                 `json:"grantId"`
	Format  string                 `json:"format"`
	Tracks  []AppleSubtitleTrack   `json:"tracks"`
	Omitted []AppleSubtitleOmitted `json:"omitted"`
}

// appleSubtitle is a text subtitle of the source as Jellyfin has it now.
type appleSubtitle struct {
	key    string
	stream jellyfin.MediaStream
	plan   repackage.Subtitle
	// fileIndex is where a track inside the file sits in it, which Jellyfin's index is
	// not: Jellyfin numbers the sidecar subtitles first, so every stream of the file
	// moves up by one when Bazarr adds one (repackage.FileIndexes).
	fileIndex int
}

type appleSubtitleSet struct {
	tracks  []appleSubtitle
	omitted []AppleSubtitleOmitted
}

// appleSourceChanged is a source that is no longer the video the app downloaded, so
// what it has to say of subtitles belongs to something else.
type appleSourceChanged struct{ reason string }

func (e *appleSourceChanged) Error() string {
	return "the video on the media PC is not the one this download was made from"
}

// subtitleUnreadable is a track whose text cannot be had: not a failure to ask.
type subtitleUnreadable struct{ cause string }

func (e *subtitleUnreadable) Error() string { return "the subtitle cannot be read: " + e.cause }

// appleKeyInput is what names a track, taken the same way from a stream of the
// source today and from an entry of the manifest made when it was downloaded.
type appleKeyInput struct {
	// Index is where an embedded track sits inside the file; an external track has
	// none, and its Jellyfin index is never used.
	Index           int
	External        bool
	Language        string
	Forced          bool
	HearingImpaired bool
}

// sourceFileIndexes is where each stream inside a file sits in it, by Jellyfin's
// index, from a manifest's description of the source.
func sourceFileIndexes(tracks []PlaybackTrack) map[int]int {
	streams := make([]repackage.Stream, 0, len(tracks))
	for _, track := range tracks {
		streams = append(streams, repackage.Stream{Index: track.Index, External: track.External})
	}
	return repackage.FileIndexes(streams)
}

// inFile is the index inside the file of a stream of the source, by Jellyfin's.
func inFile(fileIndexes map[int]int, index int) int {
	if at, found := fileIndexes[index]; found {
		return at
	}
	return index
}

// appleSubtitleKeys names a source's text subtitles, in the order given.
//
// An embedded track is emb-<index inside the file>: that index does not move, where
// Jellyfin's own number of it moves up by one whenever a sidecar is added, since
// Jellyfin numbers the sidecars first. An external one is ext-<language>, then
// -forced and -sdh, then -2, -3 for a second and third that would otherwise share
// it. So Bazarr replacing a file in place keeps its key (only the signature moves),
// and a new language is a new key that leaves the others alone. An external track's
// index is never used: it moves when a file is added.
func appleSubtitleKeys(tracks []appleKeyInput) []string {
	keys := make([]string, len(tracks))
	seen := map[string]int{}
	for at, track := range tracks {
		if !track.External {
			keys[at] = fmt.Sprintf("emb-%d", track.Index)
			continue
		}
		key := "ext-" + repackage.NormalizeLanguage(track.Language)
		if track.Forced {
			key += "-forced"
		}
		if track.HearingImpaired {
			key += "-sdh"
		}
		seen[key]++
		if seen[key] > 1 {
			key = fmt.Sprintf("%s-%d", key, seen[key])
		}
		keys[at] = key
	}
	return keys
}

// appleMP4Positions says where each of the manifest's text subtitles sits among the
// MP4's subtitle options: the nth entry that was available, from 0. The manifest's
// indexes are Jellyfin's of the day it was made, so an embedded track is keyed by its
// place inside the file as that manifest's own source description gives it.
func appleMP4Positions(manifest OfflineManifest) map[string]int {
	positions := map[string]int{}
	if manifest.Apple == nil {
		return positions
	}
	fileIndexes := sourceFileIndexes(manifest.Source.Tracks)
	var inputs []appleKeyInput
	for _, track := range manifest.Apple.Subtitles {
		if track.Available {
			inputs = append(inputs, appleKeyInput{
				Index: inFile(fileIndexes, track.SourceIndex), External: track.External, Language: track.Language,
				Forced: track.Forced, HearingImpaired: track.HearingImpaired,
			})
		}
	}
	for at, key := range appleSubtitleKeys(inputs) {
		positions[key] = at
	}
	return positions
}

// appleSubtitleSet reads the grant's source as Jellyfin has it now and sorts its
// subtitle streams into the text ones the app can keep and the rest. It fails with
// *appleSourceChanged when the source is not the video that was downloaded.
func (s *Server) appleSubtitleSet(ctx context.Context, client *jellyfin.Client, grant offlineGrant) (appleSubtitleSet, error) {
	item, err := client.Item(ctx, grant.ItemID)
	if err != nil {
		var upstream *httpx.Error
		if errors.As(err, &upstream) && upstream.Kind == httpx.KindNotFound {
			return appleSubtitleSet{}, &appleSourceChanged{"source_missing"}
		}
		return appleSubtitleSet{}, err
	}
	source, found := mediaSourceOf(*item, grant.MediaSourceID)
	if !found {
		return appleSubtitleSet{}, &appleSourceChanged{"source_missing"}
	}
	if source.Size != grant.Manifest.Source.SizeBytes {
		return appleSubtitleSet{}, &appleSourceChanged{"source_differs"}
	}
	// The plan is how the MP4 decides what is text, so the two cannot disagree.
	planned := planSourceFrom(source, *item)
	plan, err := repackage.PlanApple(planned)
	if err != nil {
		return appleSubtitleSet{}, &appleSourceChanged{"source_differs"}
	}
	fileIndexes := repackage.FileIndexes(planned.Streams)
	streams := map[int]jellyfin.MediaStream{}
	for _, stream := range source.MediaStreams {
		streams[stream.Index] = stream
	}

	var set appleSubtitleSet
	var inputs []appleKeyInput
	externals := 0
	for _, track := range plan.Subtitles {
		omit := func(reason string) {
			set.omitted = append(set.omitted, AppleSubtitleOmitted{
				SourceIndex: track.SourceIndex, Language: track.Language, Label: cleanTrackName(track.Label), Codec: track.Codec,
				External: track.External, Reason: reason,
			})
		}
		if !track.Available {
			omit(track.Reason)
			continue
		}
		if track.External {
			if externals++; externals > appleSubtitleMaxExternal {
				omit(reasonTooMany)
				continue
			}
		}
		at := inFile(fileIndexes, track.SourceIndex)
		set.tracks = append(set.tracks, appleSubtitle{stream: streams[track.SourceIndex], plan: track, fileIndex: at})
		inputs = append(inputs, appleKeyInput{
			Index: at, External: track.External, Language: track.Language,
			Forced: track.Forced, HearingImpaired: track.HearingImpaired,
		})
	}
	for at, key := range appleSubtitleKeys(inputs) {
		set.tracks[at].key = key
	}
	return set, nil
}

// subtitleAskFormat is the format Jellyfin is asked for. Those it can hand over as
// they are stay as they are, so the converter reads the file itself; anything else
// is asked for as SRT.
func subtitleAskFormat(codec string) string {
	switch strings.ToLower(codec) {
	case "ass", "ssa":
		return "ass"
	case "webvtt", "vtt":
		return "vtt"
	}
	return "srt"
}

var errSubtitleTooLarge = errors.New("the subtitle is too large")

// subtitleStatusError is Jellyfin saying no to a subtitle.
type subtitleStatusError struct{ status int }

func (e *subtitleStatusError) Error() string { return fmt.Sprintf("jellyfin answered %d", e.status) }

// readSubtitleText asks Jellyfin for one subtitle's text. Jellyfin hands it over in
// UTF-8 whatever the file's own encoding was (a Hebrew SRT is commonly Windows-1255),
// which is why it is asked for rather than read from disk. At most
// maxSubtitleSidecarBytes are taken.
func readSubtitleText(ctx context.Context, client *jellyfin.Client, itemID, sourceID string, index int, format string) ([]byte, error) {
	resource := fmt.Sprintf("/Videos/%s/%s/Subtitles/%d/0/Stream.%s", itemID, url.PathEscape(sourceID), index, format)
	if !validPlaybackResource(resource, itemID) {
		return nil, errors.New("the subtitle resource is invalid")
	}
	response, err := client.OpenResource(ctx, http.MethodGet, resource, nil)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, &subtitleStatusError{status: response.StatusCode}
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxSubtitleSidecarBytes+1))
	if err != nil {
		return nil, err
	}
	if len(body) > maxSubtitleSidecarBytes {
		return nil, errSubtitleTooLarge
	}
	return body, nil
}

// subtitleSaysNo is whether a failure to read a subtitle is about the track (it is
// not there, it is too large, it is not subtitles) rather than about asking:
// Jellyfin down, slow or turning the hub's credential away says nothing of it.
func subtitleSaysNo(err error) bool {
	if errors.Is(err, errSubtitleTooLarge) {
		return true
	}
	var refused *subtitleStatusError
	if errors.As(err, &refused) {
		switch refused.status {
		case http.StatusUnauthorized, http.StatusForbidden, http.StatusRequestTimeout, http.StatusTooManyRequests:
			return false
		}
		return refused.status >= 400 && refused.status < 500
	}
	return false
}

// appleSubtitleVTT is one track's text as WebVTT. It fails with *subtitleUnreadable
// when the track cannot be had, and with the error itself when asking failed.
func (s *Server) appleSubtitleVTT(ctx context.Context, client *jellyfin.Client, grant offlineGrant, track appleSubtitle) (webvtt.Result, error) {
	format := subtitleAskFormat(track.stream.Codec)
	body, err := readSubtitleText(ctx, client, grant.ItemID, grant.MediaSourceID, track.stream.Index, format)
	var refused *subtitleStatusError
	if errors.As(err, &refused) && format != "srt" && subtitleSaysNo(err) {
		// A Jellyfin that will not give a track in its own format will still give SRT,
		// the format it converts to most readily.
		body, err = readSubtitleText(ctx, client, grant.ItemID, grant.MediaSourceID, track.stream.Index, "srt")
	}
	if err != nil {
		if subtitleSaysNo(err) {
			return webvtt.Result{}, &subtitleUnreadable{cause: err.Error()}
		}
		return webvtt.Result{}, err
	}
	result, err := webvtt.Convert(body)
	if err != nil {
		return webvtt.Result{}, &subtitleUnreadable{cause: err.Error()}
	}
	return result, nil
}

// signatureOf is the signature of a track whose text was read: a hash of the WebVTT,
// so any change to a word changes it.
func signatureOf(text string) string {
	sum := sha256.Sum256([]byte(text))
	return hex.EncodeToString(sum[:16])
}

// embeddedSubtitleSignature signs a track that lives inside the video's file without
// reading it: the file cannot change while the source is the one that was downloaded,
// so what identifies the track and the converter's rules are all that can.
func embeddedSubtitleSignature(grant offlineGrant, stream jellyfin.MediaStream, fileIndex int) string {
	// The index is the track's place inside the file, not Jellyfin's number, which
	// moves when a sidecar is added and would make every app fetch every track again.
	text := fmt.Sprintf("embedded|webvtt%d|%s|%d|%d|%s|%s", webvtt.Version, grant.MediaSourceID,
		grant.Manifest.Source.SizeBytes, fileIndex, strings.ToLower(stream.Codec), repackage.NormalizeLanguage(stream.Language))
	return signatureOf(text)
}

// cleanTrackName keeps a track's own name to one short printable line. A name that
// is a path (a sidecar whose title Jellyfin took from its file name) is no name: the
// hub names no file.
func cleanTrackName(name string) string {
	name = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return ' '
		}
		return r
	}, name)
	name = strings.Join(strings.Fields(name), " ")
	if strings.Contains(name, `\`) || strings.Contains(name, ":/") || strings.HasPrefix(name, "/") {
		return ""
	}
	if runes := []rune(name); len(runes) > 120 {
		name = string(runes[:120])
	}
	return name
}

func appleSubtitleURL(grantID, key string) string {
	return "/v1/offline/grants/" + grantID + "/subtitle-tracks/" + key
}

// writeAppleSubtitleError answers a failure of the two subtitle routes.
func writeAppleSubtitleError(w http.ResponseWriter, r *http.Request, err error) {
	var changed *appleSourceChanged
	var unreadable *subtitleUnreadable
	switch {
	case errors.As(err, &changed):
		writeError(w, r, http.StatusConflict, Error{
			Code: codeSourceChanged, Reason: changed.reason,
			Message: "The video on the media PC is not the one this download was made from, so its subtitles were not refreshed.",
		})
	case errors.As(err, &unreadable):
		writeError(w, r, http.StatusUnprocessableEntity, Error{
			Code: codeSubtitleUnreadable, Message: "The media PC cannot read this subtitle.",
		})
	default:
		writeUpstreamError(w, r, "jellyfin", err)
	}
}

// appleSubtitleGrant is the checks both routes make: the scope, the grant (this
// token's, this Jellyfin user's, not expired) and that it is an Apple one.
func (s *Server) appleSubtitleGrant(w http.ResponseWriter, r *http.Request) (offlineGrant, *jellyfin.Client, bool) {
	if !s.requireDownload(w, r) {
		return offlineGrant{}, nil, false
	}
	grant, client, ok := s.offlineGrantForRequest(w, r, false)
	if !ok {
		return offlineGrant{}, nil, false
	}
	if grant.Format != offlineFormatApple {
		// An original download keeps its sidecars through the subtitles route, and
		// has no MP4 to refresh them beside.
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such offline subtitle list"})
		return offlineGrant{}, nil, false
	}
	return grant, client, true
}

// handleOfflineSubtitleTracks serves GET /v1/offline/grants/{grantId}/subtitle-tracks:
// the grant's text subtitles as they are now.
func (s *Server) handleOfflineSubtitleTracks(w http.ResponseWriter, r *http.Request) {
	grant, client, ok := s.appleSubtitleGrant(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	set, err := s.appleSubtitleSet(ctx, client, grant)
	if err != nil {
		writeAppleSubtitleError(w, r, err)
		return
	}

	// An external track is signed by what it says now, so it is read. An embedded
	// one is not read at all: that would make Jellyfin extract every track of a
	// 39-subtitle film to answer a question about three.
	signatures := make([]string, len(set.tracks))
	failures := make([]error, len(set.tracks))
	var group sync.WaitGroup
	slots := make(chan struct{}, appleSubtitleWorkers)
	for at, track := range set.tracks {
		if !track.plan.External {
			signatures[at] = embeddedSubtitleSignature(grant, track.stream, track.fileIndex)
			continue
		}
		group.Add(1)
		slots <- struct{}{}
		go func() {
			defer group.Done()
			defer func() { <-slots }()
			result, readErr := s.appleSubtitleVTT(ctx, client, grant, track)
			if readErr != nil {
				failures[at] = readErr
				return
			}
			signatures[at] = signatureOf(result.Text)
		}()
	}
	group.Wait()

	positions := appleMP4Positions(grant.Manifest)
	list := AppleSubtitleList{GrantID: grant.ID, Format: offlineFormatApple, Tracks: []AppleSubtitleTrack{}, Omitted: set.omitted}
	for at, track := range set.tracks {
		if failures[at] != nil {
			var unreadable *subtitleUnreadable
			if !errors.As(failures[at], &unreadable) {
				// Asking failed, which says nothing of the track. Leaving it out would
				// tell the app it had gone and make it delete a good file, so the whole
				// list fails and the app keeps what it has.
				slog.Warn("a subtitle of an Apple download could not be read", "grant", grant.ID, "index", track.stream.Index, "error", failures[at])
				writeAppleSubtitleError(w, r, failures[at])
				return
			}
			slog.Warn("a subtitle of an Apple download is unreadable", "grant", grant.ID, "index", track.stream.Index, "error", failures[at])
			list.Omitted = append(list.Omitted, AppleSubtitleOmitted{
				Key: track.key, SourceIndex: track.plan.SourceIndex, Language: track.plan.Language, Label: cleanTrackName(track.plan.Label),
				Codec: track.plan.Codec, External: track.plan.External, Reason: reasonUnreadable,
			})
			continue
		}
		entry := AppleSubtitleTrack{
			Key: track.key, SourceIndex: track.plan.SourceIndex, Language: track.plan.Language,
			Title: cleanTrackName(track.stream.Title), Label: cleanTrackName(track.plan.Label), Codec: track.plan.Codec,
			External: track.plan.External, Default: track.stream.IsDefault, Forced: track.plan.Forced,
			HearingImpaired: track.plan.HearingImpaired, RTL: webvtt.IsRTL(track.plan.Language),
			Signature: signatures[at], URL: appleSubtitleURL(grant.ID, track.key),
		}
		if position, inMP4 := positions[track.key]; inMP4 {
			entry.MP4Index = &position
		}
		list.Tracks = append(list.Tracks, entry)
	}
	sort.SliceStable(list.Omitted, func(i, j int) bool { return list.Omitted[i].SourceIndex < list.Omitted[j].SourceIndex })
	if list.Omitted == nil {
		list.Omitted = []AppleSubtitleOmitted{}
	}
	writeJSON(w, http.StatusOK, list)
}

// handleOfflineSubtitleTrack serves GET /v1/offline/grants/{grantId}/subtitle-tracks/{trackKey}:
// one track as WebVTT, with its signature as the ETag.
func (s *Server) handleOfflineSubtitleTrack(w http.ResponseWriter, r *http.Request) {
	grant, client, ok := s.appleSubtitleGrant(w, r)
	if !ok {
		return
	}
	key := r.PathValue("trackKey")
	if !appleSubtitleKeyPattern.MatchString(key) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such offline subtitle"})
		return
	}
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	set, err := s.appleSubtitleSet(ctx, client, grant)
	if err != nil {
		writeAppleSubtitleError(w, r, err)
		return
	}
	var track *appleSubtitle
	for at := range set.tracks {
		if set.tracks[at].key == key {
			track = &set.tracks[at]
			break
		}
	}
	if track == nil {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such offline subtitle"})
		return
	}
	result, err := s.appleSubtitleVTT(ctx, client, grant, *track)
	if err != nil {
		slog.Warn("a subtitle of an Apple download could not be served", "grant", grant.ID, "index", track.stream.Index, "error", err)
		writeAppleSubtitleError(w, r, err)
		return
	}
	// The ETag is the signature the list gives the track. For an embedded one that is
	// not a hash of the text, but the text is a function of what it hashes.
	signature := signatureOf(result.Text)
	if !track.plan.External {
		signature = embeddedSubtitleSignature(grant, track.stream, track.fileIndex)
	}
	header := w.Header()
	header.Set("Content-Type", "text/vtt; charset=utf-8")
	header.Set("ETag", `"`+signature+`"`)
	header.Set("Cache-Control", "private, no-store")
	header.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.Time{}, strings.NewReader(result.Text))
}
