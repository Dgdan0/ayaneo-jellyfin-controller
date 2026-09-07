package api

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"time"
)

// Partial names a service that could not be reached, and what it cost.
//
// The rule the app relies on: a screen endpoint returns 2xx if it can render
// anything at all. Fields backed by a dead service come back null, and this
// says which ones and why. "Bazarr is down but the screen still works" is
// therefore ordinary data rather than error handling.
type Partial struct {
	Service string    `json:"service"`
	Reason  string    `json:"reason"`
	Affects []string  `json:"affects,omitempty"`
	Since   time.Time `json:"since,omitempty"`
	RetryAt time.Time `json:"retryAt,omitempty"`
	Message string    `json:"message,omitempty"`
}

// Error is returned only when nothing renderable exists.
type Error struct {
	Code              string `json:"code"`
	Service           string `json:"service,omitempty"`
	Message           string `json:"message"`
	Retryable         bool   `json:"retryable"`
	RetryAfterSeconds int    `json:"retryAfterSeconds,omitempty"`
}

type errorBody struct {
	Error     Error  `json:"error"`
	RequestID string `json:"requestId"`
}

const (
	CodeUnauthorized   = "unauthorized"
	CodeForbiddenScope = "forbidden_scope"
	CodeRateLimited    = "rate_limited"
	CodeBanned         = "banned"
	CodeNotFound       = "not_found"
	CodeInvalidRequest = "invalid_request"
	CodeUpstreamDown   = "upstream_unavailable"
	CodeInternal       = "internal"
)

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	// Nothing this hub returns should ever be cached by an intermediary: it is
	// all per-user and some of it is per-second.
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(body); err != nil {
		slog.Error("writing response", "error", err)
	}
}

func writeError(w http.ResponseWriter, r *http.Request, status int, e Error) {
	if e.RetryAfterSeconds > 0 {
		w.Header().Set("Retry-After", strconv.Itoa(e.RetryAfterSeconds))
	}
	writeJSON(w, status, errorBody{Error: e, RequestID: RequestIDFrom(r.Context())})
}
