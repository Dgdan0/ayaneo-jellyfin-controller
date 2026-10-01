package api

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"ayaneohub/internal/httpx"
)

func upstreamFailure(service string, status int, kind httpx.Kind, body string) error {
	return &httpx.Error{Service: service, Status: status, Kind: kind, Err: errors.New(body)}
}

func TestRequestErrorsReadTheServicesOwnWords(t *testing.T) {
	// Jellyseerr's reason is in the body; the hub's summary is only
	// "jellyseerr: HTTP 403 (auth)", so a quota refusal used to read as a bad
	// API key.
	cases := []struct {
		body   string
		status int
		kind   httpx.Kind
		code   string
	}{
		{`{"message":"Quota exceeded"}`, 403, httpx.KindAuth, "quota_exceeded"},
		{`{"message":"Request for this media already exists."}`, 409, httpx.KindBadRequest, "duplicate_request"},
		{`{"message":"This title is on the blocklist"}`, 403, httpx.KindAuth, "blocklisted"},
	}
	for _, tc := range cases {
		recorder := httptest.NewRecorder()
		writeRequestError(recorder, httptest.NewRequest(http.MethodPost, "/v1/requests", nil),
			upstreamFailure("jellyseerr", tc.status, tc.kind, tc.body))
		if !strings.Contains(recorder.Body.String(), `"code":"`+tc.code+`"`) {
			t.Fatalf("%s: got %d %s", tc.body, recorder.Code, recorder.Body.String())
		}
	}
}

func TestUpstreamMessagePrefersTheServicesSentence(t *testing.T) {
	cases := map[string]string{
		`{"message":"Series is not monitored"}`:                                       "Series is not monitored",
		`[{"propertyName":"Path","errorMessage":"Path is not a valid Windows path"}]`: "Path is not a valid Windows path",
		`plain text reason`:                     "plain text reason",
		`<html><body>Bad Gateway</body></html>`: "",
		`{"unrelated":true}`:                    "",
	}
	for body, want := range cases {
		if got := upstreamMessage(upstreamFailure("radarr", 400, httpx.KindBadRequest, body)); got != want {
			t.Errorf("upstreamMessage(%s) = %q, want %q", body, got, want)
		}
	}
	if got := upstreamMessage(errors.New("dial tcp: refused")); got != "" {
		t.Errorf("a transport error has no service sentence, got %q", got)
	}
}

func TestBadRequestShowsTheReasonNotTheStatusLine(t *testing.T) {
	recorder := httptest.NewRecorder()
	writeUpstreamError(recorder, httptest.NewRequest(http.MethodGet, "/v1/x", nil), "radarr",
		upstreamFailure("radarr", 400, httpx.KindBadRequest, `{"message":"Movie is not monitored"}`))
	if recorder.Code != http.StatusBadRequest || !strings.Contains(recorder.Body.String(), "Movie is not monitored") ||
		strings.Contains(recorder.Body.String(), "HTTP 400") {
		t.Fatalf("got %d %s", recorder.Code, recorder.Body.String())
	}
}

func TestServiceOfNamesTheServiceThatFailed(t *testing.T) {
	if got := serviceOf(upstreamFailure("sonarr", 503, httpx.KindUpstream5xx, ""), "radarr or sonarr"); got != "sonarr" {
		t.Fatalf("serviceOf = %q", got)
	}
	if got := serviceOf(errors.New("boom"), "radarr or sonarr"); got != "radarr or sonarr" {
		t.Fatalf("fallback = %q", got)
	}
}
