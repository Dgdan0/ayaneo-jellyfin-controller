package api

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/kavita"
	"ayaneohub/internal/cache"
)

type ReadingPublicationPage struct {
	Index  int  `json:"index"`
	Width  int  `json:"width,omitempty"`
	Height int  `json:"height,omitempty"`
	IsWide bool `json:"isWide,omitempty"`
}

type ReadingPublicationManifest struct {
	WorkID               string                   `json:"workId"`
	Source               string                   `json:"source"`
	SourceItemID         string                   `json:"sourceItemId"`
	Kind                 string                   `json:"kind"`
	Title                string                   `json:"title"`
	SeriesTitle          string                   `json:"seriesTitle"`
	Number               string                   `json:"number,omitempty"`
	PageCount            int                      `json:"pageCount"`
	CurrentPage          int                      `json:"currentPage"`
	Direction            string                   `json:"direction"`
	Pages                []ReadingPublicationPage `json:"pages"`
	DoublePairs          map[string]int           `json:"doublePairs"`
	PreviousSourceItemID string                   `json:"previousSourceItemId,omitempty"`
	NextSourceItemID     string                   `json:"nextSourceItemId,omitempty"`
}

type readingPublicationContext struct {
	manifest  ReadingPublicationManifest
	chapterID int
	seriesID  int
	volumeID  int
	libraryID int
}

func (s *Server) handleReadingPublication(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	// Kavita may need to extract/cache an archive on the first reader open.
	// Keep that exceptional wait away from normal catalog requests.
	ctx, cancel := timeoutFor(r, 2*time.Minute)
	defer cancel()
	publication, ok := s.resolveReadingPublication(w, r, ctx, true)
	if !ok {
		return
	}
	writeJSON(w, http.StatusOK, publication.manifest)
}

func (s *Server) handleReadingPublicationPage(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	page, err := strconv.Atoi(r.PathValue("page"))
	if err != nil || page < 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading page"})
		return
	}
	publication, ok := s.resolveReadingPublication(w, r, r.Context(), false)
	if !ok {
		return
	}
	if page >= publication.manifest.PageCount {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "reading page is outside this publication"})
		return
	}
	response, err := s.kavita.OpenPage(r.Context(), publication.chapterID, page)
	if err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return
	}
	defer response.Body.Close()
	for _, name := range []string{"Content-Type", "Content-Length", "Cache-Control", "ETag", "Last-Modified"} {
		if value := response.Header.Get(name); value != "" {
			w.Header().Set(name, value)
		}
	}
	if w.Header().Get("Content-Type") == "" {
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	if w.Header().Get("Cache-Control") == "" {
		w.Header().Set("Cache-Control", "private, max-age=300")
	}
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(http.StatusOK)
	_, _ = io.Copy(w, response.Body)
}

func (s *Server) handleReadingPublicationProgress(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	var body struct {
		PageIndex int `json:"pageIndex"`
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil || body.PageIndex < 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading progress"})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	publication, ok := s.resolveReadingPublication(w, r, ctx, false)
	if !ok {
		return
	}
	if body.PageIndex >= publication.manifest.PageCount {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "reading progress is outside this publication"})
		return
	}
	if err := s.kavita.SaveProgress(ctx, kavita.Progress{
		VolumeID:  publication.volumeID,
		ChapterID: publication.chapterID,
		PageNum:   body.PageIndex,
		SeriesID:  publication.seriesID,
		LibraryID: publication.libraryID,
	}); err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return
	}
	s.cache.Invalidate("reading:kavita:work:" + strconv.Itoa(publication.seriesID))
	s.cache.InvalidatePrefix("reading:kavita:library:" + strconv.Itoa(publication.libraryID) + ":")
	writeJSON(w, http.StatusOK, struct {
		OK     bool   `json:"ok"`
		Action string `json:"action"`
	}{OK: true, Action: "save_reading_progress"})
}

func (s *Server) resolveReadingPublication(
	w http.ResponseWriter,
	r *http.Request,
	ctx context.Context,
	includeProgress bool,
) (readingPublicationContext, bool) {
	workID := r.PathValue("workId")
	chapterID, err := strconv.Atoi(r.PathValue("sourceItemId"))
	if !validReadingWorkID(workID) || err != nil || chapterID <= 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading publication"})
		return readingPublicationContext{}, false
	}
	binding, found := s.readingCatalog.Resolve(workID)
	if !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading work not found"})
		return readingPublicationContext{}, false
	}
	seriesID := 0
	for _, source := range binding.Sources {
		if source.Source != "kavita" {
			continue
		}
		parsed, parseErr := strconv.Atoi(source.SourceID)
		if parseErr == nil && parsed > 0 {
			seriesID = parsed
			break
		}
	}
	if seriesID == 0 || s.kavita == nil {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "this work has no Kavita publication"})
		return readingPublicationContext{}, false
	}
	detail, _, err := cache.Fetch(ctx, s.cache, "reading:kavita:work:"+strconv.Itoa(seriesID), cache.Metadata, func(fetchCtx context.Context) (*kavita.Detail, error) {
		return s.kavita.Detail(fetchCtx, seriesID)
	})
	if err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return readingPublicationContext{}, false
	}
	chapter, previous, next, found := kavitaPublicationChapter(detail, chapterID)
	if !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "publication does not belong to this work"})
		return readingPublicationContext{}, false
	}
	info, _, err := cache.Fetch(ctx, s.cache, "reading:kavita:chapter:"+strconv.Itoa(seriesID)+":"+strconv.Itoa(chapterID), cache.Metadata, func(fetchCtx context.Context) (*kavita.ChapterInfo, error) {
		return s.kavita.ChapterInfo(fetchCtx, chapterID)
	})
	if err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return readingPublicationContext{}, false
	}
	if info.SeriesID != seriesID || info.LibraryID != detail.Series.LibraryID {
		writeError(w, r, http.StatusConflict, Error{Code: CodeInvalidRequest, Message: "Kavita publication changed while opening"})
		return readingPublicationContext{}, false
	}
	kind, err := s.kavitaLibraryKind(ctx, detail.Series.LibraryID)
	if err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return readingPublicationContext{}, false
	}
	currentPage := chapter.PagesRead
	if includeProgress {
		if progress, progressErr := s.kavita.Progress(ctx, chapterID); progressErr == nil && progress.ChapterID == chapterID {
			currentPage = progress.PageNum
		}
	}
	currentPage = coerce(currentPage, 0, info.Pages-1)
	pages := make([]ReadingPublicationPage, info.Pages)
	for index := range pages {
		pages[index].Index = index
	}
	for _, dimension := range info.PageDimensions {
		if dimension.PageNumber < 0 || dimension.PageNumber >= len(pages) {
			continue
		}
		pages[dimension.PageNumber] = ReadingPublicationPage{
			Index: dimension.PageNumber, Width: dimension.Width, Height: dimension.Height,
			IsWide: dimension.IsWide || dimension.Width > dimension.Height,
		}
	}
	title := kavitaChapterTitle(info.ChapterTitle, chapter.Number, kavitaChapterTitle(info.Title, "", ""))
	if title == "Publication" {
		title = kavitaChapterTitle(chapter.Title, chapter.Number, "Volume "+strings.TrimSpace(info.VolumeNumber))
	}
	direction := "ltr"
	if kind == "manga" {
		direction = "rtl"
	}
	manifest := ReadingPublicationManifest{
		WorkID: workID, Source: "kavita", SourceItemID: strconv.Itoa(chapterID), Kind: kind,
		Title: title, SeriesTitle: detail.Series.Name, Number: kavitaChapterNumber(chapter.Number),
		PageCount: info.Pages, CurrentPage: currentPage, Direction: direction, Pages: pages,
		DoublePairs: info.DoublePairs, PreviousSourceItemID: previous, NextSourceItemID: next,
	}
	return readingPublicationContext{
		manifest: manifest, chapterID: chapterID, seriesID: seriesID,
		volumeID: info.VolumeID, libraryID: info.LibraryID,
	}, true
}

func kavitaPublicationChapter(detail *kavita.Detail, chapterID int) (kavita.Chapter, string, string, bool) {
	chapters := make([]kavita.Chapter, 0)
	for _, volume := range detail.Volumes {
		chapters = append(chapters, volume.Chapters...)
	}
	for index, chapter := range chapters {
		if chapter.ID != chapterID {
			continue
		}
		previous := ""
		next := ""
		if index > 0 {
			previous = strconv.Itoa(chapters[index-1].ID)
		}
		if index+1 < len(chapters) {
			next = strconv.Itoa(chapters[index+1].ID)
		}
		return chapter, previous, next, true
	}
	return kavita.Chapter{}, "", "", false
}

func coerce(value, minimum, maximum int) int {
	if value < minimum {
		return minimum
	}
	if value > maximum {
		return maximum
	}
	return value
}
