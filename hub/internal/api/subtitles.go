package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/bazarr"
	"ayaneohub/internal/adapters/jellyfin"
)

type subtitleTarget struct {
	Movie        bool
	ID, SeriesID int
	Path         string
	FileID       int
}
type subtitleTicket struct {
	Owner, Item string
	Target      subtitleTarget
	Candidate   bazarr.SubtitleCandidate
	Expires     time.Time
}
type subtitleRecord struct {
	ID          string `json:"id"`
	Language    string `json:"language"`
	Provider    string `json:"provider"`
	Score       string `json:"score"`
	Date        string `json:"date"`
	Description string `json:"description"`
	Installed   bool   `json:"installed"`
	Embedded    bool   `json:"embedded"`
	Forced      bool   `json:"forced"`
	HI          bool   `json:"hi"`
}
type subtitleChoice struct {
	Ticket     string   `json:"ticket"`
	Language   string   `json:"language"`
	Provider   string   `json:"provider"`
	Score      float64  `json:"score"`
	Release    string   `json:"release"`
	Matches    []string `json:"matches"`
	Mismatches []string `json:"mismatches"`
	Forced     bool     `json:"forced"`
	HI         bool     `json:"hi"`
}

func subtitleHash(values ...string) string {
	h := sha256.Sum256([]byte(strings.Join(values, "\x00")))
	return hex.EncodeToString(h[:])
}
func subtitleOwner(r *http.Request, user string) string {
	return subtitleHash(r.Header.Get("Authorization"), user)
}
func (s *Server) resolveSubtitleTarget(ctx context.Context, c *jellyfin.Client, itemID string) (subtitleTarget, error) {
	var target subtitleTarget
	item, err := c.Item(ctx, itemID)
	if err != nil {
		return target, err
	}
	if item.Type == "Movie" && item.ProviderIds != nil && s.arrs["radarr"] != nil {
		id, _ := strconv.Atoi(item.ProviderIds.Tmdb)
		if id <= 0 {
			return target, fmt.Errorf("movie has no TMDB identity")
		}
		movie, e := s.arrs["radarr"].MovieByTmdb(ctx, id)
		if e != nil {
			return target, e
		}
		if movie != nil && movie.TmdbID == id && movie.HasFile {
			target = subtitleTarget{Movie: true, ID: movie.ID, FileID: movie.MovieFileID}
		}
	} else if item.Type == "Episode" && s.arrs["sonarr"] != nil && isHex32(item.SeriesID) {
		series, e := c.Item(ctx, item.SeriesID)
		if e != nil {
			return target, e
		}
		if series.ProviderIds != nil {
			id, _ := strconv.Atoi(series.ProviderIds.Tvdb)
			if id <= 0 {
				return target, fmt.Errorf("series has no TVDB identity")
			}
			show, e := s.arrs["sonarr"].SeriesByTvdb(ctx, id)
			if e != nil {
				return target, e
			}
			if show != nil && show.TvdbID == id {
				episodes, e := s.arrs["sonarr"].Episodes(ctx, show.ID, item.ParentIndexNumber)
				if e != nil {
					return target, e
				}
				for _, episode := range episodes {
					if episode.SeriesID == show.ID && episode.SeasonNumber == item.ParentIndexNumber && episode.EpisodeNumber == item.IndexNumber && episode.HasFile {
						target = subtitleTarget{ID: episode.ID, SeriesID: show.ID, FileID: episode.EpisodeFileID}
						break
					}
				}
			}
		}
	}
	if target.ID <= 0 {
		return target, fmt.Errorf("no downloaded Arr file matches this library item")
	}
	media, err := s.bazarr.SubtitleMedia(ctx, target.Movie, target.ID)
	if err != nil {
		return target, err
	}
	if media.Path == "" {
		return target, fmt.Errorf("Bazarr has no file for this item")
	}
	target.Path = media.Path
	return target, nil
}
func (s *Server) subtitleRequest(w http.ResponseWriter, r *http.Request) (*jellyfin.Client, bool) {
	if !isHex32(r.PathValue("itemId")) {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "Bad library item id"})
		return nil, false
	}
	if s.bazarr == nil {
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "Bazarr is not configured"})
		return nil, false
	}
	return s.jellyfinForRequest(w, r)
}
func (s *Server) handleSubtitles(w http.ResponseWriter, r *http.Request) {
	c, ok := s.subtitleRequest(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, 25*time.Second)
	defer cancel()
	target, err := s.resolveSubtitleTarget(ctx, c, r.PathValue("itemId"))
	if err != nil {
		writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "Could not match this item to a downloaded file in Arr and Bazarr. Check those services and refresh."})
		return
	}
	media, err := s.bazarr.SubtitleMedia(ctx, target.Movie, target.ID)
	if err != nil {
		writeUpstreamError(w, r, "bazarr", err)
		return
	}
	history, historyErr := s.bazarr.SubtitleHistory(ctx, target.Movie, target.ID)
	records := []subtitleRecord{}
	// Bazarr returns newest first. Only acquisition events can supply an installed score.
	matched := map[string]bool{}
	for _, h := range history {
		if h.Action != 1 && h.Action != 2 {
			continue
		}
		record := subtitleRecord{ID: subtitleHash(fmt.Sprint(target.Movie, target.ID), h.ParsedTimestamp, h.Path, h.Provider, h.Score), Language: h.Language.Name, Provider: h.Provider, Score: h.Score, Date: h.ParsedTimestamp, Description: h.Description}
		for _, track := range media.Subtitles {
			if track.Path != nil && *track.Path == h.Path && h.Path != "" && !matched[h.Path] {
				record.Installed = true
				record.Forced = track.Forced
				record.HI = track.HI
				matched[h.Path] = true
				break
			}
		}
		records = append(records, record)
	}
	for i, track := range media.Subtitles {
		path := ""
		if track.Path != nil {
			path = *track.Path
		}
		if matched[path] && path != "" {
			continue
		}
		records = append(records, subtitleRecord{ID: subtitleHash(target.Path, path, track.Code2, fmt.Sprint(track.Forced, track.HI, i)), Language: track.Name, Installed: true, Embedded: path == "", Forced: track.Forced, HI: track.HI})
	}
	warning := ""
	if historyErr != nil {
		warning = "Installed tracks loaded; Bazarr download history is unavailable."
	}
	writeJSON(w, 200, map[string]any{"records": records, "canDownload": TokenFrom(r.Context()).HasScope("control"), "warning": warning})
}
func (s *Server) handleSubtitleSearch(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	c, ok := s.subtitleRequest(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, 100*time.Second)
	defer cancel()
	target, err := s.resolveSubtitleTarget(ctx, c, r.PathValue("itemId"))
	if err != nil {
		writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "No matching downloaded file is available for subtitle search."})
		return
	}
	candidates, err := s.bazarr.SearchSubtitles(ctx, target.Movie, target.ID)
	if err != nil {
		writeUpstreamError(w, r, "bazarr", err)
		return
	}
	sort.SliceStable(candidates, func(i, j int) bool { return candidates[i].Score > candidates[j].Score })
	if len(candidates) > 200 {
		candidates = candidates[:200]
	}
	choices := []subtitleChoice{}
	now := time.Now()
	s.subtitleMu.Lock()
	defer s.subtitleMu.Unlock()
	if s.subtitleTickets == nil {
		s.subtitleTickets = map[string]subtitleTicket{}
	}
	for id, t := range s.subtitleTickets {
		if !t.Expires.After(now) {
			delete(s.subtitleTickets, id)
		}
	}
	for _, v := range candidates {
		if len(s.subtitleTickets) >= 2000 {
			break
		}
		if v.Subtitle == "" || v.Provider == "" {
			continue
		}
		var random [24]byte
		if _, err := rand.Read(random[:]); err != nil {
			writeError(w, r, 500, Error{Code: CodeUpstreamDown, Message: "Could not create subtitle selection"})
			return
		}
		id := hex.EncodeToString(random[:])
		s.subtitleTickets[id] = subtitleTicket{Owner: subtitleOwner(r, c.UserID()), Item: r.PathValue("itemId"), Target: target, Candidate: v, Expires: now.Add(15 * time.Minute)}
		matches := v.Matches
		if matches == nil {
			matches = []string{}
		}
		mismatches := v.DontMatches
		if mismatches == nil {
			mismatches = []string{}
		}
		choices = append(choices, subtitleChoice{Ticket: id, Language: v.Language, Provider: v.Provider, Score: v.Score, Release: strings.Join(v.ReleaseInfo, " · "), Matches: matches, Mismatches: mismatches, Forced: strings.EqualFold(v.Forced, "true"), HI: strings.EqualFold(v.HI, "true")})
	}
	writeJSON(w, 200, map[string]any{"candidates": choices})
}
func (s *Server) handleSubtitleDownload(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	c, ok := s.subtitleRequest(w, r)
	if !ok {
		return
	}
	var body struct {
		Ticket string `json:"ticket"`
	}
	d := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024))
	d.DisallowUnknownFields()
	err := d.Decode(&body)
	var extra any
	if err != nil || len(body.Ticket) != 48 || d.Decode(&extra) != io.EOF {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "Choose a subtitle from a fresh search"})
		return
	}
	s.subtitleMu.Lock()
	ticket, found := s.subtitleTickets[body.Ticket]
	s.subtitleMu.Unlock()
	if !found || ticket.Owner != subtitleOwner(r, c.UserID()) || ticket.Item != r.PathValue("itemId") || !ticket.Expires.After(time.Now()) {
		writeError(w, r, 409, Error{Code: CodeInvalidRequest, Message: "Selection expired. Search again."})
		return
	}
	ctx, cancel := timeoutFor(r, 100*time.Second)
	defer cancel()
	target, err := s.resolveSubtitleTarget(ctx, c, ticket.Item)
	if err != nil || target != ticket.Target {
		writeError(w, r, 409, Error{Code: CodeInvalidRequest, Message: "The media file changed or is unavailable. Refresh and search again."})
		return
	}
	// Consume before the write. Parallel taps and uncertain upstream outcomes must not replay it.
	s.subtitleMu.Lock()
	_, found = s.subtitleTickets[body.Ticket]
	delete(s.subtitleTickets, body.Ticket)
	s.subtitleMu.Unlock()
	if !found {
		writeError(w, r, 409, Error{Code: CodeInvalidRequest, Message: "This selection was already submitted. Refresh subtitle history."})
		return
	}
	if err = s.bazarr.DownloadSubtitle(ctx, target.Movie, target.ID, target.SeriesID, ticket.Candidate); err != nil {
		writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "Download could not be confirmed. Refresh installed tracks and history before searching again."})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "action": "subtitle_download"})
}
