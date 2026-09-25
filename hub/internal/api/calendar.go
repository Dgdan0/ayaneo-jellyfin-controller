package api

import (
	"ayaneohub/internal/adapters/arr"
	"context"
	"fmt"
	"io"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"
)

type CalendarItem struct {
	ID           string   `json:"id"`
	Service      string   `json:"service"`
	Media        MediaRef `json:"media"`
	Date         string   `json:"date"`
	At           string   `json:"at,omitempty"`
	ReleaseType  string   `json:"releaseType"`
	Season       int      `json:"season,omitempty"`
	Episode      int      `json:"episode,omitempty"`
	EpisodeTitle string   `json:"episodeTitle,omitempty"`
	Overview     string   `json:"overview,omitempty"`
	HasFile      bool     `json:"hasFile"`
}
type CalendarResponse struct {
	Start       string         `json:"start"`
	End         string         `json:"end"`
	Timezone    string         `json:"timezone"`
	GeneratedAt time.Time      `json:"generatedAt"`
	Items       []CalendarItem `json:"items"`
	Partial     []Partial      `json:"partial"`
}

func (s *Server) handleCalendar(w http.ResponseWriter, r *http.Request) {
	zone := r.URL.Query().Get("timezone")
	if zone == "" {
		zone = "UTC"
	}
	loc, err := time.LoadLocation(zone)
	if err != nil {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "invalid timezone"})
		return
	}
	start, e1 := time.ParseInLocation(time.DateOnly, r.URL.Query().Get("start"), loc)
	end, e2 := time.ParseInLocation(time.DateOnly, r.URL.Query().Get("end"), loc)
	if e1 != nil || e2 != nil || !end.After(start) || end.After(start.AddDate(0, 0, 31)) {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "start and exclusive end must describe 1–31 days"})
		return
	}
	ctx, cancel := timeoutFor(r, 20*time.Second)
	defer cancel()
	type fetched struct {
		service  string
		episodes []arr.CalendarEpisode
		movies   []arr.CalendarMovie
		err      error
	}
	results := make(chan fetched, len(s.arrs))
	for name, client := range s.arrs {
		go func(name string, c *arr.Client) {
			ep, mv, err := c.Calendar(ctx, start, end)
			results <- fetched{name, ep, mv, err}
		}(name, client)
	}
	out := CalendarResponse{Start: start.Format(time.DateOnly), End: end.Format(time.DateOnly), Timezone: zone, GeneratedAt: time.Now().UTC(), Items: []CalendarItem{}, Partial: []Partial{}}
	succeeded := 0
	for range s.arrs {
		got := <-results
		if got.err != nil {
			out.Partial = append(out.Partial, Partial{Service: got.service, Reason: "calendar_unavailable", Message: got.service + " schedule could not be loaded", Affects: []string{"calendar"}})
			continue
		}
		succeeded++
		for _, e := range got.episodes {
			if item, ok := episodeCalendarItem(e, start, end, loc); ok {
				out.Items = append(out.Items, item)
			}
		}
		for _, m := range got.movies {
			out.Items = append(out.Items, movieCalendarItems(m, start, end)...)
		}
	}
	if succeeded == 0 {
		writeError(w, r, 503, Error{Code: CodeUpstreamDown, Message: "No configured calendar service is responding", Retryable: len(s.arrs) > 0})
		return
	}
	sort.SliceStable(out.Items, func(i, j int) bool {
		a, b := out.Items[i], out.Items[j]
		if a.Date != b.Date {
			return a.Date < b.Date
		}
		if a.At != b.At {
			if a.At == "" {
				return false
			}
			if b.At == "" {
				return true
			}
			return a.At < b.At
		}
		if a.Media.Title != b.Media.Title {
			return a.Media.Title < b.Media.Title
		}
		return a.ID < b.ID
	})
	sort.Slice(out.Partial, func(i, j int) bool { return out.Partial[i].Service < out.Partial[j].Service })
	// Some upstream versions include duplicate calendar records.
	seen := map[string]bool{}
	unique := out.Items[:0]
	for _, item := range out.Items {
		if !seen[item.ID] {
			unique = append(unique, item)
			seen[item.ID] = true
		}
	}
	out.Items = unique
	writeJSON(w, 200, out)
}

func episodeCalendarItem(e arr.CalendarEpisode, start, end time.Time, loc *time.Location) (CalendarItem, bool) {
	if !e.Monitored || !e.Series.Monitored {
		return CalendarItem{}, false
	}
	date, at := e.AirDate, ""
	if t, err := time.Parse(time.RFC3339, e.AirDateUTC); err == nil {
		date = t.In(loc).Format(time.DateOnly)
		at = t.UTC().Format(time.RFC3339)
	}
	if !calendarDateInRange(date, start, end) {
		return CalendarItem{}, false
	}
	id := e.Series.ID
	if id == 0 {
		id = e.SeriesID
	}
	media := MediaRef{Type: "series", Title: e.Series.Title, Poster: fmt.Sprintf("/v1/img/arr/sonarr/%d", id)}
	if e.Series.TmdbID > 0 {
		media.Key = mediaKey("series", e.Series.TmdbID)
	}
	overview := e.Overview
	if overview == "" {
		overview = e.Series.Overview
	}
	return CalendarItem{ID: fmt.Sprintf("sonarr:episode:%d", e.ID), Service: "sonarr", Media: media, Date: date, At: at, ReleaseType: "Episode", Season: e.SeasonNumber, Episode: e.EpisodeNumber, EpisodeTitle: e.Title, Overview: overview, HasFile: e.HasFile}, true
}
func movieCalendarItems(m arr.CalendarMovie, start, end time.Time) []CalendarItem {
	out := []CalendarItem{}
	if !m.Monitored {
		return out
	}
	media := MediaRef{Type: "movie", Title: m.Title, Year: m.Year, Poster: fmt.Sprintf("/v1/img/arr/radarr/%d", m.ID)}
	if m.TmdbID > 0 {
		media.Key = mediaKey("movie", m.TmdbID)
	}
	for _, date := range []struct{ label, value string }{{"Digital release", m.DigitalRelease}, {"Physical release", m.PhysicalRelease}, {"In cinemas", m.InCinemas}} {
		civil := date.value
		if len(civil) >= 10 {
			civil = civil[:10]
		}
		if !calendarDateInRange(civil, start, end) {
			continue
		}
		out = append(out, CalendarItem{ID: fmt.Sprintf("radarr:movie:%d:%s", m.ID, date.label), Service: "radarr", Media: media, Date: civil, ReleaseType: date.label, Overview: m.Overview, HasFile: m.HasFile})
	}
	return out
}
func calendarDateInRange(date string, start, end time.Time) bool {
	if _, err := time.Parse(time.DateOnly, date); err != nil {
		return false
	}
	return date >= start.Format(time.DateOnly) && date < end.Format(time.DateOnly)
}

func (s *Server) handleArrPoster(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("service")
	id, err := strconv.Atoi(r.PathValue("id"))
	if err != nil || id <= 0 || (name != "sonarr" && name != "radarr") {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "invalid poster target"})
		return
	}
	c := s.arrs[name]
	if c == nil {
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "service not configured"})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 12*time.Second)
	defer cancel()
	response, err := c.Poster(ctx, id)
	if err != nil {
		writeUpstreamError(w, r, name, err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "poster unavailable"})
		return
	}
	mime := response.Header.Get("Content-Type")
	if !strings.HasPrefix(mime, "image/") {
		writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "invalid poster response"})
		return
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, (8<<20)+1))
	if err != nil || len(body) > 8<<20 {
		writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "poster exceeds limit or could not be read"})
		return
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(body)
}
