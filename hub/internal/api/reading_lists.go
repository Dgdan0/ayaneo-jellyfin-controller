package api

import (
	"ayaneohub/internal/adapters/kavita"
	"net/http"
	"strconv"
	"time"
)

type ServerReadingList struct {
	ID        int    `json:"id"`
	Title     string `json:"title"`
	Summary   string `json:"summary,omitempty"`
	ItemCount int    `json:"itemCount"`
	Promoted  bool   `json:"promoted"`
}
type ServerReadingListEntry struct {
	ID           int              `json:"id"`
	Order        int              `json:"order"`
	WorkID       string           `json:"workId"`
	SourceItemID string           `json:"sourceItemId"`
	Title        string           `json:"title"`
	SeriesTitle  string           `json:"seriesTitle"`
	Volume       string           `json:"volume,omitempty"`
	Kind         string           `json:"kind"`
	Artwork      string           `json:"artwork"`
	PageCount    int              `json:"pageCount"`
	Progress     *ReadingProgress `json:"progress,omitempty"`
}

func (s *Server) handleServerReadingLists(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	if s.kavita == nil {
		writeError(w, r, 503, Error{Code: CodeUpstreamDown, Message: "Kavita is not configured"})
		return
	}
	ctx, cancel := timeoutFor(r, 30*time.Second)
	defer cancel()
	lists, err := s.kavita.ReadingLists(ctx)
	if err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return
	}
	mapped := []ServerReadingList{}
	for _, list := range lists {
		mapped = append(mapped, ServerReadingList{list.ID, list.Title, list.Summary, list.ItemCount, list.Promoted})
	}
	if r.PathValue("listId") == "" {
		writeJSON(w, 200, struct {
			Lists []ServerReadingList `json:"lists"`
		}{mapped})
		return
	}
	id, err := strconv.Atoi(r.PathValue("listId"))
	if err != nil || id <= 0 {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "Invalid reading list"})
		return
	}
	var selected *ServerReadingList
	for i := range mapped {
		if mapped[i].ID == id {
			selected = &mapped[i]
			break
		}
	}
	if selected == nil {
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "Reading list not found"})
		return
	}
	entries, err := s.kavita.ReadingListItems(ctx, id)
	if err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return
	}
	items := []ServerReadingListEntry{}
	for _, entry := range entries {
		if entry.SeriesID <= 0 || entry.ChapterID <= 0 {
			writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "A reading list entry is unavailable"})
			return
		}
		work, err := s.kavitaSummary("kavita:"+strconv.Itoa(entry.LibraryID), kavitaLibraryKind(entry.LibraryType), kavita.Series{ID: entry.SeriesID, Name: entry.SeriesName})
		if err != nil {
			writeReadingCatalogError(w, r, err)
			return
		}
		items = append(items, ServerReadingListEntry{ID: entry.ID, Order: entry.Order, WorkID: work.ID, SourceItemID: strconv.Itoa(entry.ChapterID), Title: entry.Title, SeriesTitle: entry.SeriesName, Volume: entry.VolumeNumber, Kind: work.Kind, Artwork: work.Artwork, PageCount: entry.PagesTotal, Progress: pageProgress(entry.PagesRead, entry.PagesTotal)})
	}
	writeJSON(w, 200, struct {
		List  *ServerReadingList       `json:"list"`
		Items []ServerReadingListEntry `json:"items"`
	}{selected, items})
}
