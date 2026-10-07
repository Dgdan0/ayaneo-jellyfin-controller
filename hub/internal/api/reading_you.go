package api

// The book page's "you" (#39): a person's rating of a book, the month they finished it,
// how many times they have read it, their shelves and their status for it. It comes from
// two places, kept apart: their Goodreads export (reading_goodreads.go), and what they set
// from an app, which Goodreads cannot be told. A value set or cleared from an app wins
// over the import and keeps winning when the import is replaced.

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	readingdomain "ayaneohub/internal/reading"
)

// ReadingYou is what one profile has to say of one work. It is absent from a work with
// nothing to say.
type ReadingYou struct {
	// Rating is 1 to 5; absent when not rated.
	Rating int `json:"rating,omitempty"`
	// Finished is "YYYY-MM".
	Finished  string `json:"finished,omitempty"`
	ReadCount int    `json:"readCount,omitempty"`
	// Shelves are the person's own shelves, never the three statuses.
	Shelves []string `json:"shelves"`
	// Status is "read", "to-read" or "currently-reading".
	Status string `json:"status,omitempty"`
	// Source is "app" when anything shown was set from an app, else "goodreads".
	Source string `json:"source"`
}

// ReadingCommunity is what readers at large think of a work.
type ReadingCommunity struct {
	Rating float64 `json:"rating"`
	Count  int     `json:"count,omitempty"`
	Source string  `json:"source"`
}

// ReadingYouResponse answers PATCH /v1/reading/works/{workId}/you with what is now
// true; You is null when nothing is left to say.
type ReadingYouResponse struct {
	WorkID string      `json:"workId"`
	You    *ReadingYou `json:"you"`
}

const (
	youSourceApp       = "app"
	youSourceGoodreads = "goodreads"
	youBodyLimit       = 4 << 10
	youMaxReadCount    = 99
)

// mergeYou is what an import record and an app's edit come to together: the edit's
// values replace the record's, and a value the edit cleared is gone. It is nil when
// nothing is left.
func mergeYou(record *youRecord, edit *youEdit) *ReadingYou {
	you := &ReadingYou{Shelves: []string{}, Source: youSourceGoodreads}
	importedStatus := ""
	if record != nil {
		you.Rating, you.Finished, you.ReadCount = record.Rating, record.Finished, record.ReadCount
		you.Shelves = append(you.Shelves, record.Shelves...)
		you.Status, importedStatus = record.Status, record.Status
	}
	if !edit.empty() {
		you.Source = youSourceApp
		if edit.Rating != nil {
			you.Rating = edit.Rating.Value
		}
		if edit.ReadCount != nil {
			you.ReadCount = edit.ReadCount.Value
		}
		if edit.Finished != nil {
			you.Finished = edit.Finished.Value
			switch {
			case you.Finished != "" && importedStatus != readingdomain.StatusCurrentlyReading:
				you.Status = readingdomain.StatusRead
			case you.Finished == "" && importedStatus == readingdomain.StatusRead:
				you.Status = ""
			}
		}
	}
	if you.Rating == 0 && you.Finished == "" && you.ReadCount == 0 && len(you.Shelves) == 0 && you.Status == "" {
		return nil
	}
	return you
}

var youMonth = regexp.MustCompile(`^(\d{4})-(\d{2})$`)

// parseYouMonth accepts "YYYY-MM" from 1900-01 to the current month.
func parseYouMonth(value, current string) bool {
	match := youMonth.FindStringSubmatch(value)
	if match == nil {
		return false
	}
	year, _ := strconv.Atoi(match[1])
	month, _ := strconv.Atoi(match[2])
	return year >= 1900 && month >= 1 && month <= 12 && value <= current
}

// readingProfile is whose reading life a request is about: the device's chosen Jellyfin
// profile, else the hub's default (as the library order does).
func (s *Server) readingProfile(r *http.Request) (string, bool) { return s.libraryOrderProfile(r) }

// handleReadingYou serves PATCH /v1/reading/works/{workId}/you: set or clear the rating,
// the month finished and the read count. A key in the body that is present sets, null
// clears, absent is left alone.
func (s *Server) handleReadingYou(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	workID := r.PathValue("workId")
	if !validReadingWorkID(workID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading work id"})
		return
	}
	if _, found := s.readingCatalog.Resolve(workID); !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading work not found"})
		return
	}
	profile, ok := s.readingProfile(r)
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad Jellyfin user id"})
		return
	}
	bad := func(message string) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: message})
	}
	var body map[string]json.RawMessage
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, youBodyLimit))
	if err != nil || json.Unmarshal(raw, &body) != nil || body == nil {
		bad(`body must be a JSON object such as {"rating":4,"finished":"2026-10","readCount":1}`)
		return
	}
	for key := range body {
		if key != "rating" && key != "finished" && key != "readCount" {
			bad("unknown field " + strconv.Quote(key))
			return
		}
	}
	if len(body) == 0 {
		bad("nothing to change: send rating, finished or readCount")
		return
	}

	isNull := func(value json.RawMessage) bool { return strings.TrimSpace(string(value)) == "null" }
	integer := func(value json.RawMessage, low, high int) (int, bool) {
		// encoding/json reads "4" into a Number as it reads 4; a string is not a count.
		if strings.HasPrefix(strings.TrimSpace(string(value)), `"`) {
			return 0, false
		}
		var number json.Number
		if json.Unmarshal(value, &number) != nil {
			return 0, false
		}
		parsed, err := strconv.Atoi(number.String())
		return parsed, err == nil && parsed >= low && parsed <= high
	}
	var rating, readCount *editInt
	var finished *editString
	if value, present := body["rating"]; present {
		if isNull(value) {
			rating = &editInt{Cleared: true}
		} else if parsed, ok := integer(value, 1, 5); ok {
			rating = &editInt{Value: parsed}
		} else {
			bad("rating must be a whole number from 1 to 5, or null")
			return
		}
	}
	if value, present := body["readCount"]; present {
		if isNull(value) {
			readCount = &editInt{Cleared: true}
		} else if parsed, ok := integer(value, 1, youMaxReadCount); ok {
			readCount = &editInt{Value: parsed}
		} else {
			bad("readCount must be a whole number from 1 to 99, or null")
			return
		}
	}
	if value, present := body["finished"]; present {
		var month string
		switch {
		case isNull(value):
			finished = &editString{Cleared: true}
		case json.Unmarshal(value, &month) == nil && parseYouMonth(month, s.now().Format("2006-01")):
			finished = &editString{Value: month}
		default:
			bad(`finished must be a month such as "2026-10", from 1900-01 to this month, or null`)
			return
		}
	}

	record, edit, err := s.readingYou.update(profile, workID, func(record *youRecord, edit *youEdit) {
		if rating != nil {
			edit.Rating = rating
		}
		if readCount != nil {
			edit.ReadCount = readCount
		}
		if finished != nil {
			edit.Finished = finished
		}
		// A finished month is at least one time read, unless the person says how many.
		if finished != nil && !finished.Cleared && readCount == nil {
			if merged := mergeYou(record, edit); merged == nil || merged.ReadCount == 0 {
				edit.ReadCount = &editInt{Value: 1}
			}
		}
	})
	if err != nil {
		slog.Error("reading data not saved", "err", err)
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "your reading data could not be saved"})
		return
	}
	w.Header().Add("Vary", jellyfinUserHeader)
	writeJSON(w, http.StatusOK, ReadingYouResponse{WorkID: workID, You: mergeYou(record, edit)})
}
