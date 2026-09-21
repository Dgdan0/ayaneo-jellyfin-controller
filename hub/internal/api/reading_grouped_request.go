package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/httpx"
)

func (s *Server) handleReadingGroupedEbookRequest(
	w http.ResponseWriter,
	r *http.Request,
	ctx context.Context,
	body readingCreateRequestBody,
	preview ReadingSeriesPreview,
) {
	manifest, err := s.readingAcquisitions.begin(
		strings.TrimSpace(body.Key), preview, preview.Books, body.QualityProfileID,
		normalizeReadingMonitoring(body.Monitoring),
	)
	if err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: "persistence_failed", Message: "Could not save the grouped book request"})
		return
	}
	parentID := manifest.ParentSeriesID
	if parentID <= 0 {
		parentID, err = s.ensureReadingBookSeries(ctx, preview)
		if err != nil {
			writeUpstreamError(w, r, "bookkeeprr", err)
			return
		}
		if err := s.readingAcquisitions.setParent(manifest.ID, parentID); err != nil {
			writeError(w, r, http.StatusInternalServerError, Error{Code: "persistence_failed", Message: "Could not save the BookKeeprr series link"})
			return
		}
	}

	requested, alreadyPresent, failed := 0, 0, 0
	for _, book := range manifest.Books {
		seriesID := book.BookKeeprrSeriesID
		state := book.State
		// accepted/present is written only after the child was linked to the
		// parent. An identical request is therefore already complete and must
		// not create or relink anything.
		if state == "accepted" {
			requested++
			continue
		}
		if state == "present" {
			alreadyPresent++
			continue
		}
		if strings.HasPrefix(state, "link_failed_") {
			state = strings.TrimPrefix(state, "link_failed_")
			if state != "accepted" && state != "present" {
				state = "accepted"
			}
		} else if seriesID > 0 && state == "failed" {
			// Compatibility with manifests written before link failures carried
			// their original child state.
			state = "accepted"
		}
		if seriesID <= 0 {
			existing, findErr := s.bookkeeprr.FindSeriesByOpenLibraryID(ctx, book.ID, book.Title)
			if findErr != nil {
				failed++
				_ = s.readingAcquisitions.setBook(manifest.ID, book.ID, 0, "failed", findErr.Error())
				continue
			}
			if existing != nil {
				seriesID, state = existing.ID, "present"
				alreadyPresent++
			} else {
				created, createErr := s.bookkeeprr.CreateSeries(ctx, bookkeeprr.CreateSeriesRequest{
					ContentType: bookkeeprr.TypeEbook, Flow: "single", OLID: book.ID,
					ISBN: book.ISBN, Author: book.Author, Title: book.Title, Year: book.Year,
					CoverURL: book.CoverURL, QualityProfileID: body.QualityProfileID,
					Monitoring: normalizeReadingMonitoring(body.Monitoring),
				})
				if createErr != nil {
					// A previous attempt may have reached BookKeeprr but lost the
					// response. Resolve the provider id once before recording failure.
					var upstream *httpx.Error
					if errors.As(createErr, &upstream) && upstream.Status == http.StatusConflict {
						existing, _ = s.bookkeeprr.FindSeriesByOpenLibraryID(ctx, book.ID, book.Title)
					}
					if existing == nil {
						failed++
						_ = s.readingAcquisitions.setBook(manifest.ID, book.ID, 0, "failed", createErr.Error())
						continue
					}
					seriesID, state = existing.ID, "present"
					alreadyPresent++
				} else {
					seriesID, state = created.ID, "accepted"
					requested++
				}
			}
		} else if state == "present" {
			alreadyPresent++
		} else {
			requested++
		}

		if _, linkErr := s.bookkeeprr.AddBookSeriesMember(ctx, parentID, seriesID, book.Position); linkErr != nil {
			failed++
			if state == "present" {
				alreadyPresent--
			} else {
				requested--
			}
			_ = s.readingAcquisitions.setBook(manifest.ID, book.ID, seriesID, "link_failed_"+state, linkErr.Error())
			continue
		}
		_ = s.readingAcquisitions.setBook(manifest.ID, book.ID, seriesID, state, "")
	}

	state := "accepted"
	message := fmt.Sprintf("BookKeeprr is searching for %d selected books", requested)
	if alreadyPresent > 0 {
		message += fmt.Sprintf("; %d already in BookKeeprr", alreadyPresent)
	}
	if failed > 0 {
		state = "partial"
		message += fmt.Sprintf("; %d need retry", failed)
	}
	status := http.StatusAccepted
	if requested+alreadyPresent == 0 {
		status = http.StatusBadGateway
	}
	writeJSON(w, status, ReadingRequestResponse{
		RequestID: manifest.ID, ParentSeriesID: parentID, Requested: requested,
		AlreadyPresent: alreadyPresent, Failed: failed, State: state, Message: message,
	})
}

func (s *Server) ensureReadingBookSeries(ctx context.Context, preview ReadingSeriesPreview) (int, error) {
	list, err := s.bookkeeprr.BookSeries(ctx, bookkeeprr.TypeEbook)
	if err != nil {
		return 0, err
	}
	for _, candidate := range list.BookSeries {
		if normalizeReadingIdentity(candidate.Name) == normalizeReadingIdentity(preview.Name) {
			return candidate.ID, nil
		}
	}
	created, err := s.bookkeeprr.CreateBookSeries(ctx, bookkeeprr.CreateBookSeriesRequest{
		Name: preview.Name, ContentType: bookkeeprr.TypeEbook,
		Description: preview.Description, CoverURL: firstReadingSeriesCover(preview.Books),
	})
	if err != nil {
		return 0, err
	}
	return created.ID, nil
}

func firstReadingSeriesCover(books []ReadingSeriesPreviewBook) string {
	for _, book := range books {
		if book.CoverURL != "" {
			return book.CoverURL
		}
	}
	return ""
}

func normalizeReadingMonitoring(value string) string {
	if strings.EqualFold(strings.TrimSpace(value), "none") {
		return "none"
	}
	return "all"
}
