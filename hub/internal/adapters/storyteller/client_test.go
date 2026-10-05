package storyteller

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"

	"ayaneohub/internal/config"
)

func TestEbookStreamsRangesWithoutBufferingAndKeepsSafeMetadata(t *testing.T) {
	var gotRange, gotIfRange string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"reader-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/12/files":
			if r.URL.Query().Get("format") != "ebook" || r.Header.Get("Authorization") != "Bearer reader-token" {
				t.Fatalf("ebook request = %s auth=%q", r.URL.String(), r.Header.Get("Authorization"))
			}
			gotRange, gotIfRange = r.Header.Get("Range"), r.Header.Get("If-Range")
			w.Header().Set("Content-Type", "application/epub+zip")
			w.Header().Set("Content-Range", "bytes 4-7/12")
			w.Header().Set("Accept-Ranges", "bytes")
			w.Header().Set("ETag", `"edition-1"`)
			w.Header().Set("X-Storyteller-Hash", "sha256:book-hash")
			w.WriteHeader(http.StatusPartialContent)
			_, _ = io.WriteString(w, "4567")
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	response, err := client.OpenEbook(context.Background(), 12, "bytes=4-7", `"edition-1"`)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusPartialContent || string(body) != "4567" || gotRange != "bytes=4-7" || gotIfRange != `"edition-1"` {
		t.Fatalf("ebook = status %d body=%q range=%q if-range=%q", response.StatusCode, body, gotRange, gotIfRange)
	}
}

func TestStartReadaloudUsesAuthenticatedBookProcessRoute(t *testing.T) {
	processes := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"process-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/42/process":
			if r.Method != http.MethodPost || r.Header.Get("Authorization") != "Bearer process-token" || r.URL.RawQuery != "" {
				t.Errorf("process request = %s %s auth=%q", r.Method, r.URL.String(), r.Header.Get("Authorization"))
			}
			processes++
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "worker", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	if err := client.StartReadaloud(context.Background(), 0); err == nil {
		t.Fatal("invalid id accepted")
	}
	if err := client.StartReadaloud(context.Background(), 42); err != nil {
		t.Fatal(err)
	}
	if processes != 1 {
		t.Fatalf("process calls = %d", processes)
	}
}

func TestPositionPreservesReadiumLocatorAndRejectsOlderUpdate(t *testing.T) {
	locator := json.RawMessage(`{"href":"chapter-4.xhtml","type":"application/xhtml+xml","locations":{"progression":0.4,"totalProgression":0.32,"position":44},"text":{"highlight":"Darrow"}}`)
	var saved struct {
		Locator   json.RawMessage `json:"locator"`
		Timestamp int64           `json:"timestamp"`
	}
	conflict := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"reader-token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/12/positions":
			if r.Header.Get("Authorization") != "Bearer reader-token" {
				t.Fatalf("position auth = %q", r.Header.Get("Authorization"))
			}
			switch r.Method {
			case http.MethodGet:
				_, _ = io.WriteString(w, `{"uuid":"position-1","locator":`+string(locator)+`,"timestamp":1700000000000,"updatedAt":"2026-09-21T10:00:00Z"}`)
			case http.MethodPost:
				if conflict {
					http.Error(w, "a newer position exists", http.StatusConflict)
					return
				}
				if err := json.NewDecoder(r.Body).Decode(&saved); err != nil {
					t.Fatal(err)
				}
				w.WriteHeader(http.StatusNoContent)
			default:
				t.Fatalf("method = %s", r.Method)
			}
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	position, err := client.Position(context.Background(), 12)
	if err != nil || position.Timestamp != 1700000000000 || !bytes.Equal(position.Locator, locator) {
		t.Fatalf("Position() = %+v, %v", position, err)
	}
	if err := client.SavePosition(context.Background(), 12, locator, 1700000000123); err != nil {
		t.Fatal(err)
	}
	if saved.Timestamp != 1700000000123 || !bytes.Equal(saved.Locator, locator) {
		t.Fatalf("saved = %+v", saved)
	}
	conflict = true
	if err := client.SavePosition(context.Background(), 12, locator, 1); err == nil {
		t.Fatal("older position should return the upstream conflict")
	}
}

func TestBooksUsesCredentialTokenAndReusesIt(t *testing.T) {
	var mu sync.Mutex
	tokenCalls := 0
	bookCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			tokenCalls++
			body, _ := io.ReadAll(r.Body)
			form, _ := url.ParseQuery(string(body))
			if r.Method != http.MethodPost || form.Get("usernameOrEmail") != "reader" || form.Get("password") != "secret" {
				t.Fatalf("token request = %s %q", r.Method, string(body))
			}
			_, _ = io.WriteString(w, `{"access_token":"token-1","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books":
			mu.Lock()
			bookCalls++
			mu.Unlock()
			if r.Header.Get("Authorization") != "Bearer token-1" {
				t.Fatalf("Authorization = %q", r.Header.Get("Authorization"))
			}
			_, _ = io.WriteString(w, `[{"id":12,"uuid":"book-uuid","title":"Red Rising","language":"en","authors":[{"name":"Pierce Brown"}],"ebook":{"uuid":"ebook-uuid","pageCount":400},"audiobook":{"uuid":"audio-uuid","duration":7200},"position":{"locator":{"locations":{"totalProgression":0.25}}}}]`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		books, err := client.Books(context.Background())
		if err != nil || len(books) != 1 || books[0].Title != "Red Rising" || books[0].Ebook == nil || books[0].Audiobook == nil {
			t.Fatalf("Books() = %+v, %v", books, err)
		}
	}
	if tokenCalls != 1 || bookCalls != 2 {
		t.Fatalf("token calls = %d, book calls = %d", tokenCalls, bookCalls)
	}
}

func TestBookAndCoverRetryOnceAfterExpiredToken(t *testing.T) {
	tokenCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			tokenCalls++
			_, _ = io.WriteString(w, `{"access_token":"token-`+string(rune('0'+tokenCalls))+`","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/12":
			if r.Header.Get("Authorization") == "Bearer token-1" {
				http.Error(w, "expired", http.StatusUnauthorized)
				return
			}
			_, _ = io.WriteString(w, `{"id":12,"uuid":"book-uuid","title":"Red Rising","readaloud":{"uuid":"ra-uuid"}}`)
		case "/api/v2/books/12/cover":
			if r.Header.Get("Authorization") != "Bearer token-2" {
				t.Fatalf("cover Authorization = %q", r.Header.Get("Authorization"))
			}
			w.Header().Set("Content-Type", "image/jpeg")
			_, _ = w.Write([]byte("jpeg"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	book, err := client.Book(context.Background(), 12)
	if err != nil || book.Readaloud == nil {
		t.Fatalf("Book() = %+v, %v", book, err)
	}
	body, contentType, err := client.Cover(context.Background(), 12)
	if err != nil || string(body) != "jpeg" || contentType != "image/jpeg" {
		t.Fatalf("Cover() = %q, %q, %v", body, contentType, err)
	}
	if tokenCalls != 2 {
		t.Fatalf("token calls = %d", tokenCalls)
	}
}

func TestScanAllUsesBookProcessRouteAndRenewsExpiredToken(t *testing.T) {
	tokenCalls := 0
	scanCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			tokenCalls++
			_, _ = io.WriteString(w, `{"access_token":"token-`+string(rune('0'+tokenCalls))+`","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/scan":
			scanCalls++
			if r.Method != http.MethodPost {
				t.Fatalf("scan method = %s", r.Method)
			}
			if r.Header.Get("Authorization") == "Bearer token-1" {
				http.Error(w, "expired", http.StatusUnauthorized)
				return
			}
			if r.Header.Get("Authorization") != "Bearer token-2" {
				t.Fatalf("scan auth = %q", r.Header.Get("Authorization"))
			}
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	if err := client.ScanAll(context.Background()); err != nil {
		t.Fatal(err)
	}
	if tokenCalls != 2 || scanCalls != 2 {
		t.Fatalf("token calls = %d, scan calls = %d", tokenCalls, scanCalls)
	}
}

func TestCoverFallsBackToTheAudiobookCover(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/7/cover":
			if _, audio := r.URL.Query()["audio"]; !audio {
				http.NotFound(w, r)
				return
			}
			w.Header().Set("Content-Type", "image/png")
			_, _ = w.Write([]byte("audio-cover"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}
	body, contentType, err := client.Cover(context.Background(), 7)
	if err != nil || string(body) != "audio-cover" || contentType != "image/png" {
		t.Fatalf("Cover() = %q, %q, %v", body, contentType, err)
	}
}

// An audiobook is a folder plus a manifest snapshot Storyteller made when it
// scanned it. Several files: one link per file, named as the file is. One .m4b:
// one link per chapter, with names that exist nowhere on disk. The hub reads
// both from the same fields, so both are decoded here, with the fields it does
// not read left alone.
func TestBookDecodesAnAudiobookFolderAndItsManifestLinks(t *testing.T) {
	const files = `{"id":12,"uuid":"book-12","title":"Dark Matter","narrators":[{"name":"Jon Lindstrom"}],
		"audiobook":{"uuid":"audio-12","filepath":"/library/audiobooks/Dark Matter","duration":36538.68,"missing":false,
		"manifest":{"metadata":{"title":"Dark Matter"},"links":[{"rel":["self"],"href":"manifest.json"}],
			"readingOrder":[
				{"rel":["chapter"],"href":"Dark Matter (1).mp3","type":"audio/mpeg","title":"Track 2/8","duration":4610.652,"size":36942522,"bitrate":64000},
				{"rel":["chapter"],"href":"Dark Matter.mp3","type":"audio/mpeg","title":"Track 1/8","duration":4012.5,"size":32100000}]}}}`
	const m4b = `{"id":13,"uuid":"book-13","title":"Mistborn",
		"audiobook":{"uuid":"audio-13","filepath":"/library/audiobooks/Mistborn","duration":3600,
		"manifest":{"readingOrder":[
			{"href":"00000-00001.mp3","type":"audio/mpeg","title":"Prologue","duration":600.5},
			{"href":"00001-00001.mp3","type":"audio/mpeg","title":"Track 2","duration":2999.5}]}}}`
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			_, _ = io.WriteString(w, `{"access_token":"token","token_type":"Bearer","expires_in":3600}`)
		case "/api/v2/books/12":
			_, _ = io.WriteString(w, files)
		case "/api/v2/books/13":
			_, _ = io.WriteString(w, m4b)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, Username: "reader", Password: config.Secret("secret")})
	if err != nil {
		t.Fatal(err)
	}

	book, err := client.Book(context.Background(), 12)
	if err != nil {
		t.Fatal(err)
	}
	audio := book.Audiobook
	if audio == nil || audio.Filepath != "/library/audiobooks/Dark Matter" || audio.Missing || audio.Duration != 36538.68 {
		t.Fatalf("audiobook = %+v", audio)
	}
	order := audio.Manifest.ReadingOrder
	if len(order) != 2 {
		t.Fatalf("reading order = %+v", order)
	}
	if first := order[0]; first.Href != "Dark Matter (1).mp3" || first.Type != "audio/mpeg" || first.Title != "Track 2/8" || first.Duration != 4610.652 || first.Size != 36942522 {
		t.Fatalf("first link = %+v", first)
	}
	if second := order[1]; second.Href != "Dark Matter.mp3" || second.Duration != 4012.5 {
		t.Fatalf("second link = %+v", second)
	}
	if len(book.Narrators) != 1 || book.Narrators[0].Name != "Jon Lindstrom" {
		t.Fatalf("narrators = %+v", book.Narrators)
	}

	chapters, err := client.Book(context.Background(), 13)
	if err != nil {
		t.Fatal(err)
	}
	order = chapters.Audiobook.Manifest.ReadingOrder
	if len(order) != 2 || order[0].Href != "00000-00001.mp3" || order[0].Title != "Prologue" || order[0].Duration != 600.5 || order[1].Duration != 2999.5 {
		t.Fatalf("chapter links = %+v", order)
	}
}
