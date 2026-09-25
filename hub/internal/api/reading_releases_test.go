package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestReadingInteractiveReleasesKeepLinksPrivateAndGrabChosenTorrent(t *testing.T) {
	grabbed := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/auth/login":
			_, _ = w.Write([]byte(`{"redirect_to":"bookkeeprr://hub/auth?exchange=one"}`))
		case "/api/mobile/exchange":
			_, _ = w.Write([]byte(`{"token":"admin-token"}`))
		case "/api/series/5":
			_, _ = w.Write([]byte(`{"id":5,"contentType":"ebook"}`))
		case "/api/search/interactive":
			if r.Method != http.MethodPost || r.Header.Get("Authorization") != "Bearer admin-token" {
				t.Fatalf("search auth: %s %q", r.Method, r.Header.Get("Authorization"))
			}
			_, _ = w.Write([]byte(`{"results":[{"item":{"guid":"private-guid","title":"Book EPUB","link":"magnet:?xt=secret","seeders":8,"sizeBytes":1234,"indexerId":2,"indexerName":"Indexer"},"parsed":{"targetKind":"volume","targetLow":1,"targetHigh":1},"matchResult":{"matches":false,"score":12.25,"reason":"language"},"ownership":"none","releaseId":0}],"errors":[]}`))
		case "/api/search/interactive/grab":
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body["seriesId"] != float64(5) || body["item"].(map[string]any)["link"] != "magnet:?xt=secret" {
				t.Fatalf("grab body: %#v", body)
			}
			grabbed++
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"downloadId":9,"qbtHash":"secret-hash","status":"queued"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	cfg := readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)
	cfg.Server.ReadingTransfers = filepath.Join(t.TempDir(), "transfers.json")
	handler := NewServer(cfg).Handler()
	searched := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/5/search", `{}`)
	if searched.Code != http.StatusOK {
		t.Fatalf("search: %d %s", searched.Code, searched.Body.String())
	}
	if strings.Contains(searched.Body.String(), "magnet:") || strings.Contains(searched.Body.String(), "private-guid") {
		t.Fatalf("private indexer data escaped: %s", searched.Body.String())
	}
	var body ReadingReleaseResponse
	if err := json.Unmarshal(searched.Body.Bytes(), &body); err != nil || len(body.Releases) != 1 {
		t.Fatalf("decode: %v %s", err, searched.Body.String())
	}
	if !body.Releases[0].Rejected || body.Releases[0].Reason != "language" || body.Releases[0].Score != 12.25 || !strings.HasPrefix(body.Releases[0].ID, "br_") {
		t.Fatalf("release: %+v", body.Releases[0])
	}
	grab := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/5/grab", `{"id":"`+body.Releases[0].ID+`"}`)
	if grab.Code != http.StatusAccepted || grabbed != 1 || strings.Contains(grab.Body.String(), "secret-hash") {
		t.Fatalf("grab: %d %s, calls=%d", grab.Code, grab.Body.String(), grabbed)
	}
	again := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/5/grab", `{"id":"`+body.Releases[0].ID+`"}`)
	if again.Code != http.StatusNotFound || grabbed != 1 {
		t.Fatalf("duplicate grab: %d calls=%d", again.Code, grabbed)
	}
	wrongSeries := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/6/grab", `{"id":"`+body.Releases[0].ID+`"}`)
	if wrongSeries.Code != http.StatusNotFound {
		t.Fatalf("cross-series: %d", wrongSeries.Code)
	}
}

func TestReadingReleasesAcceptFractionalBookKeeprrScores(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/series/18" {
			_, _ = w.Write([]byte(`{"id":18,"contentType":"ebook"}`))
			return
		}
		if r.URL.Path != "/api/series/18/releases" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(`{"releases":[{"id":42,"title":"The Final Empire 1/3","score":12.5,"seeders":3,"sizeBytes":2048,"ownership":"none"}]}`))
	}))
	defer upstream.Close()
	cfg := readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)
	cfg.Server.ReadingTransfers = filepath.Join(t.TempDir(), "transfers.json")
	got := readingJSONRequest(NewServer(cfg).Handler(), http.MethodGet, "/v1/reading/requests/18/releases", "")
	if got.Code != http.StatusOK {
		t.Fatalf("fractional score response: %d %s", got.Code, got.Body.String())
	}
	var body ReadingReleaseResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil || len(body.Releases) != 1 {
		t.Fatalf("release decode: %v %s", err, got.Body.String())
	}
	if body.Releases[0].Score != 12.5 {
		t.Fatalf("fractional score lost: %v", body.Releases[0].Score)
	}
}

func TestReadingReleaseSearchRequiresRequestScope(t *testing.T) {
	cfg := readingAcquisitionConfig("http://127.0.0.1:1", []string{"reading"}, true)
	cfg.Server.ReadingTransfers = filepath.Join(t.TempDir(), "transfers.json")
	handler := NewServer(cfg).Handler()
	got := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/5/search", `{}`)
	if got.Code != http.StatusForbidden {
		t.Fatalf("scope: %d %s", got.Code, got.Body.String())
	}
}

func TestReadingIndexerErrorsCannotExposeUpstreamURLsOrCredentials(t *testing.T) {
	if got := safeReadingIndexerError("https://tracker.example/api?apikey=secret returned 429"); got != "Indexer rate limited (HTTP 429)" {
		t.Fatalf("rate limit = %q", got)
	}
	if got := safeReadingIndexerError("https://tracker.example/api?apikey=secret failed"); got != "Indexer unavailable" {
		t.Fatalf("unexpected upstream text = %q", got)
	}
}

func TestReadingReleaseFormatRejectsKnownWrongFileType(t *testing.T) {
	for _, item := range []struct{ kind, title, parsed string }{
		{"audiobook", "Light Bringer Pierce Brown EPUB", `{"format":"epub"}`},
		{"audiobook", "Light Bringer.pdf", `{}`},
		{"ebook", "Light Bringer M4B", `{"format":"m4b"}`},
		{"comic", "Daredevil issue 3 EPUB", `{"format":"epub"}`},
	} {
		format, status := readingReleaseFormat(item.title, json.RawMessage(item.parsed), item.kind)
		if status != "incompatible" || format == "" {
			t.Errorf("%s %q => %s %s", item.kind, item.title, format, status)
		}
	}
	format, status := readingReleaseFormat("Light Bringer unknown release", nil, "audiobook")
	if format != "" || status != "unknown" {
		t.Fatalf("unknown format: %q %q", format, status)
	}
	format, status = readingReleaseFormat("Light Bringer MP3", json.RawMessage(`{"format":"mp3"}`), "audiobook")
	if format != "MP3" || status != "compatible" {
		t.Fatalf("matching format: %q %q", format, status)
	}
	format, status = readingReleaseFormat("Light Bringer EPUB", json.RawMessage(`{"format":"mp3"}`), "audiobook")
	if status != "incompatible" {
		t.Fatalf("conflicting title and parsed format accepted: %q %q", format, status)
	}
}

func TestAudiobookReleaseCannotGrabKnownEbookFormat(t *testing.T) {
	grabbed := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/auth/login":
			_, _ = w.Write([]byte(`{"redirect_to":"bookkeeprr://hub/auth?exchange=one"}`))
		case "/api/mobile/exchange":
			_, _ = w.Write([]byte(`{"token":"admin-token"}`))
		case "/api/series/14":
			_, _ = w.Write([]byte(`{"id":14,"contentType":"audiobook","title":"Light Bringer"}`))
		case "/api/search/interactive":
			_, _ = w.Write([]byte(`{"results":[{"item":{"guid":"private-guid","title":"Light Bringer Pierce Brown EPUB","link":"magnet:?xt=secret"},"parsed":{"format":"epub"},"matchResult":{"matches":true},"ownership":"none"}],"errors":[]}`))
		case "/api/search/interactive/grab":
			grabbed++
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	cfg := readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)
	cfg.Server.ReadingTransfers = filepath.Join(t.TempDir(), "transfers.json")
	handler := NewServer(cfg).Handler()
	searched := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/14/search", `{}`)
	if searched.Code != http.StatusOK {
		t.Fatalf("search: %d %s", searched.Code, searched.Body.String())
	}
	var body ReadingReleaseResponse
	if err := json.Unmarshal(searched.Body.Bytes(), &body); err != nil || len(body.Releases) != 1 {
		t.Fatalf("decode: %v %s", err, searched.Body.String())
	}
	if !body.Releases[0].Rejected || body.Releases[0].FormatStatus != "incompatible" {
		t.Fatalf("known wrong format accepted: %+v", body.Releases[0])
	}
	grab := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/14/grab", `{"id":"`+body.Releases[0].ID+`"}`)
	if grab.Code != http.StatusBadRequest || grabbed != 0 {
		t.Fatalf("wrong format grabbed: %d calls=%d", grab.Code, grabbed)
	}
}

func TestReadingReleaseGrabRechecksTicketMetadata(t *testing.T) {
	grabbed := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/grab") {
			grabbed++
		}
		http.NotFound(w, r)
	}))
	defer upstream.Close()
	cfg := readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)
	cfg.Server.ReadingTransfers = filepath.Join(t.TempDir(), "transfers.json")
	server := NewServer(cfg)
	id, err := server.readingReleaseTickets.put(readingReleaseTicket{
		Owner: "reader", SeriesID: 14, ReleaseID: 5, Title: "Light Bringer EPUB", ContentType: "audiobook", FormatStatus: "unknown",
	})
	if err != nil {
		t.Fatal(err)
	}
	got := readingJSONRequest(server.Handler(), http.MethodPost, "/v1/reading/requests/14/grab", `{"id":"`+id+`"}`)
	if got.Code != http.StatusBadRequest || grabbed != 0 {
		t.Fatalf("wrong-format ticket reached upstream: %d calls=%d", got.Code, grabbed)
	}
}

func TestReadingReleaseTicketIsBoundToTokenLabelAndSeries(t *testing.T) {
	store := newReadingReleaseTickets()
	id, err := store.put(readingReleaseTicket{Owner: "pocket-ds", SeriesID: 5, ReleaseID: 8})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := store.take(id, "other-device", 5); ok {
		t.Fatal("other token used release ticket")
	}
	if _, ok := store.take(id, "pocket-ds", 6); ok {
		t.Fatal("other series used release ticket")
	}
	if ticket, ok := store.take(id, "pocket-ds", 5); !ok || ticket.ReleaseID != 8 {
		t.Fatalf("owner could not use ticket: %+v %v", ticket, ok)
	}
	if _, ok := store.take(id, "pocket-ds", 5); ok {
		t.Fatal("ticket could be used twice")
	}
}

func TestReadingSavedReleasesCanBeChosenWhenFreshSearchIsUnavailable(t *testing.T) {
	grabbed := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/series/5":
			_, _ = w.Write([]byte(`{"id":5,"contentType":"comic"}`))
		case "/api/series/5/releases":
			_, _ = w.Write([]byte(`{"releases":[{"id":8,"title":"Comic issue 1","indexerGuid":"private-guid","indexerName":"Indexer","seeders":4,"sizeBytes":4096,"ownership":"none"}]}`))
		case "/api/auth/login":
			_, _ = w.Write([]byte(`{"redirect_to":"bookkeeprr://hub/auth?exchange=one"}`))
		case "/api/mobile/exchange":
			_, _ = w.Write([]byte(`{"token":"admin-token"}`))
		case "/api/releases/8/grab":
			grabbed++
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"downloadId":9,"qbtHash":"private-hash","status":"queued"}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	cfg := readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)
	cfg.Server.ReadingTransfers = filepath.Join(t.TempDir(), "transfers.json")
	handler := NewServer(cfg).Handler()
	listed := libraryRequest(handler, "/v1/reading/requests/5/releases")
	if listed.Code != http.StatusOK || strings.Contains(listed.Body.String(), "private-guid") {
		t.Fatalf("list: %d %s", listed.Code, listed.Body.String())
	}
	var list ReadingReleaseResponse
	if err := json.Unmarshal(listed.Body.Bytes(), &list); err != nil || len(list.Releases) != 1 {
		t.Fatalf("list decode: %v %s", err, listed.Body.String())
	}
	grab := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests/5/grab", `{"id":"`+list.Releases[0].ID+`"}`)
	if grab.Code != http.StatusAccepted || grabbed != 1 || strings.Contains(grab.Body.String(), "private-hash") {
		t.Fatalf("grab: %d %s", grab.Code, grab.Body.String())
	}
}
