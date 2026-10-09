package api

// Highlights and notes (#62): GET, POST, PUT and DELETE under /v1/reading/works/{workId}/annotations, for the profile
// the request is for (X-Jellyfin-User, else the hub's default), kept by reading_annotations_store.go.
//
//	GET    …/annotations[?since=ms]   what is there, oldest first; with since (even 0), what changed after it, tombstones too
//	POST   …/annotations              keep one (an id of the app's own, or the hub's)
//	PUT    …/annotations/{id}         keep one under this id (an update; one the hub has not seen is made)
//	DELETE …/annotations/{id}[?updatedAt=ms]   a tombstone
//
// Every write answers {annotation, applied}: what the hub holds under the id now, and whether this write is what it is.
// A write older than what is held changes nothing and is answered with what won.

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

const (
	annotationBodyLimit    = 64 << 10
	annotationNoteLimit    = 4000
	annotationQuoteLimit   = 2000
	annotationContextLimit = 1000
	annotationLocatorLimit = 8 << 10
	annotationDocumentMax  = 500
	// An edit stamped further ahead than this is taken as made now (a phone's clock, a year out, must not win for ever).
	annotationClockSlackMs = int64(10 * 60 * 1000)
)

var (
	annotationIDPattern = regexp.MustCompile(`^an_[0-9a-f]{32}$`)
	annotationColors    = map[string]bool{"yellow": true, "blue": true, "pink": true, "green": true}
)

// ReadingAnnotationsResponse answers a list: [ServerTime] is the hub's clock, which an app asks "what changed since" from next time.
type ReadingAnnotationsResponse struct {
	WorkID      string              `json:"workId"`
	Annotations []ReadingAnnotation `json:"annotations"`
	ServerTime  int64               `json:"serverTime"`
}

// ReadingAnnotationResponse answers a write.
type ReadingAnnotationResponse struct {
	WorkID     string            `json:"workId"`
	Annotation ReadingAnnotation `json:"annotation"`
	Applied    bool              `json:"applied"`
}

type annotationInput struct {
	ID        string                 `json:"id"`
	Color     string                 `json:"color"`
	Note      string                 `json:"note"`
	Document  string                 `json:"document"`
	Quote     ReadingAnnotationQuote `json:"quote"`
	Locator   json.RawMessage        `json:"locator"`
	CreatedAt int64                  `json:"createdAt"`
	UpdatedAt int64                  `json:"updatedAt"`
}

// annotationDocument is a document path as the store keeps it: no fragment, no leading slash, and a path inside the book.
func annotationDocument(raw string) (string, bool) {
	path := strings.TrimLeft(strings.SplitN(strings.TrimSpace(raw), "#", 2)[0], "/")
	if path == "" || len(path) > annotationDocumentMax || strings.ContainsAny(path, "\\\x00") || !utf8.ValidString(path) {
		return "", false
	}
	for _, part := range strings.Split(path, "/") {
		if part == ".." || part == "" {
			return "", false
		}
	}
	return path, true
}

func newAnnotationID() string {
	var raw [16]byte
	_, _ = rand.Read(raw[:])
	return "an_" + hex.EncodeToString(raw[:])
}

// annotationScope is what the three routes begin with: the reading scope, a known work, a profile.
func (s *Server) annotationScope(w http.ResponseWriter, r *http.Request) (workID, profile string, ok bool) {
	if !s.requireReading(w, r) {
		return "", "", false
	}
	workID = r.PathValue("workId")
	if !validReadingWorkID(workID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading work id"})
		return "", "", false
	}
	if _, found := s.readingCatalog.Resolve(workID); !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading work not found"})
		return "", "", false
	}
	profile, valid := s.readingProfile(r)
	if !valid {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad Jellyfin user id"})
		return "", "", false
	}
	w.Header().Add("Vary", jellyfinUserHeader)
	return workID, profile, true
}

func (s *Server) handleReadingAnnotationsList(w http.ResponseWriter, r *http.Request) {
	workID, profile, ok := s.annotationScope(w, r)
	if !ok {
		return
	}
	var since *int64
	if raw := r.URL.Query().Get("since"); raw != "" {
		parsed, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || parsed < 0 {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "since is a time in milliseconds"})
			return
		}
		since = &parsed
	}
	writeJSON(w, http.StatusOK, ReadingAnnotationsResponse{
		WorkID: workID, Annotations: s.readingAnnotations.list(profile, workID, since), ServerTime: s.now().UnixMilli(),
	})
}

// handleReadingAnnotationWrite serves POST (an id of the app's own, or one made here) and PUT (the id of the path).
func (s *Server) handleReadingAnnotationWrite(w http.ResponseWriter, r *http.Request) {
	workID, profile, ok := s.annotationScope(w, r)
	if !ok {
		return
	}
	bad := func(message string) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: message})
	}
	var in annotationInput
	decoder := json.NewDecoder(io.LimitReader(r.Body, annotationBodyLimit))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&in); err != nil {
		bad("body must be a highlight: color, document, quote and, when it has one, note")
		return
	}
	id := r.PathValue("id")
	switch {
	case id != "" && !annotationIDPattern.MatchString(id):
		bad("invalid highlight id")
		return
	case id != "" && in.ID != "" && in.ID != id:
		bad("the highlight's id is not the path's")
		return
	case id == "":
		id = in.ID
	}
	switch {
	case id == "":
		id = newAnnotationID()
	case !annotationIDPattern.MatchString(id):
		bad("invalid highlight id")
		return
	}
	if !annotationColors[in.Color] {
		bad("color is yellow, blue, pink or green")
		return
	}
	document, valid := annotationDocument(in.Document)
	if !valid {
		bad("document is a path inside the book")
		return
	}
	if strings.TrimSpace(in.Quote.Highlight) == "" {
		bad("a highlight needs the text it marks")
		return
	}
	if len(in.Note) > annotationNoteLimit || len(in.Quote.Highlight) > annotationQuoteLimit ||
		len(in.Quote.Before) > annotationContextLimit || len(in.Quote.After) > annotationContextLimit {
		bad("the note or the quote is too long")
		return
	}
	if len(in.Locator) > 0 && string(in.Locator) != "null" {
		var hint map[string]json.RawMessage
		if len(in.Locator) > annotationLocatorLimit || json.Unmarshal(in.Locator, &hint) != nil || hint == nil {
			bad("locator is Readium's, an object of at most 8 KB")
			return
		}
	} else {
		in.Locator = nil
	}
	now := s.now().UnixMilli()
	stamp := in.UpdatedAt
	if stamp <= 0 || stamp > now+annotationClockSlackMs {
		stamp = now
	}
	incoming := ReadingAnnotation{
		ID: id, Color: in.Color, Note: in.Note, Document: document, Quote: in.Quote, Locator: in.Locator,
		CreatedAt: min(max(in.CreatedAt, 0), stamp), UpdatedAt: stamp,
	}
	s.keepAnnotation(w, r, workID, profile, incoming, now)
}

func (s *Server) handleReadingAnnotationDelete(w http.ResponseWriter, r *http.Request) {
	workID, profile, ok := s.annotationScope(w, r)
	if !ok {
		return
	}
	id := r.PathValue("id")
	if !annotationIDPattern.MatchString(id) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid highlight id"})
		return
	}
	now := s.now().UnixMilli()
	stamp := now
	if raw := r.URL.Query().Get("updatedAt"); raw != "" {
		parsed, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || parsed <= 0 {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "updatedAt is a time in milliseconds"})
			return
		}
		stamp = min(parsed, now+annotationClockSlackMs)
		if stamp > now {
			stamp = now
		}
	}
	s.keepAnnotation(w, r, workID, profile, ReadingAnnotation{ID: id, CreatedAt: stamp, UpdatedAt: stamp, Deleted: true}, now)
}

func (s *Server) keepAnnotation(w http.ResponseWriter, r *http.Request, workID, profile string, incoming ReadingAnnotation, now int64) {
	held, applied, err := s.readingAnnotations.put(profile, workID, incoming, now)
	switch {
	case errors.Is(err, errAnnotationLimit):
		writeError(w, r, http.StatusConflict, Error{Code: "annotation_limit", Message: err.Error()})
		return
	case err != nil:
		slog.Error("reading annotation not saved", "err", err)
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "your highlights could not be saved"})
		return
	}
	writeJSON(w, http.StatusOK, ReadingAnnotationResponse{WorkID: workID, Annotation: held, Applied: applied})
}
