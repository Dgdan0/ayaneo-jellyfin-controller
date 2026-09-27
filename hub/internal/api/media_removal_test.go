package api

import (
	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func removalPost(h http.Handler, path, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest("POST", path, strings.NewReader(body))
	r.Header.Set("Authorization", "Bearer "+libraryTestToken)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestRemovalRequiresConfirmationAndDeletesOnlyVerifiedKavitaFiles(t *testing.T) {
	folder := t.TempDir()
	target := filepath.Join(folder, "test.cbz")
	other := filepath.Join(folder, "keep.cbz")
	os.WriteFile(target, []byte("test book"), 0600)
	os.WriteFile(other, []byte("other book"), 0600)
	deletes := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/Series/9":
			if r.Method == "DELETE" {
				deletes++
				if _, err := os.Stat(target); !os.IsNotExist(err) {
					t.Error("catalog deleted before original file")
				}
				io.WriteString(w, "true")
			} else {
				io.WriteString(w, `{"id":9,"name":"Test comic","libraryId":2}`)
			}
		case "/api/Series/metadata":
			io.WriteString(w, `{}`)
		case "/api/Series/volumes":
			io.WriteString(w, `[{"id":1,"chapters":[{"id":6,"files":[{"filePath":"/reading/test.cbz"}]}]}]`)
		case "/api/Reader/continue-point":
			io.WriteString(w, `{"id":6}`)
		default:
			t.Errorf("unexpected %s %s", r.Method, r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	cfg := readingCatalogConfig(upstream.URL, "", []string{"reading", "control"})
	cfg.Auth.Tokens = append(cfg.Auth.Tokens, config.TokenConfig{Label: "reader", Raw: config.Secret("another-strong-test-token-with-more-than-32-characters"), Scopes: []string{"reading", "control"}})
	cfg.Server.MediaRemovalRoots = []config.MediaRemovalRoot{{Service: "kavita", Remote: "/reading", Local: folder}}
	server := NewServer(cfg)
	id, _ := server.readingCatalog.Bind(readingdomain.WorkBinding{Source: "kavita", SourceID: "9"})
	handler := server.Handler()
	prepare := func() removalPreview {
		w := removalPost(handler, "/v1/media/removal-preview", fmt.Sprintf(`{"kind":"reading","id":%q}`, id))
		if w.Code != 200 {
			t.Fatalf("preview %d %s", w.Code, w.Body.String())
		}
		var v removalPreview
		json.Unmarshal(w.Body.Bytes(), &v)
		return v
	}
	preview := prepare()
	wrongOwner := httptest.NewRequest("POST", "/v1/media/remove", strings.NewReader(fmt.Sprintf(`{"ticket":%q,"confirm":true}`, preview.Ticket)))
	wrongOwner.Header.Set("Authorization", "Bearer another-strong-test-token-with-more-than-32-characters")
	denied := httptest.NewRecorder()
	handler.ServeHTTP(denied, wrongOwner)
	if denied.Code != 409 || deletes != 0 {
		t.Fatal("confirmation crossed device ownership")
	}

	if preview.FileCount != 1 || preview.Files[0] != "test.cbz" || deletes != 0 {
		t.Fatalf("preview %+v deletes=%d", preview, deletes)
	}
	if _, err := os.Stat(target); err != nil {
		t.Fatal("preview removed file")
	}
	if w := removalPost(handler, "/v1/media/remove", fmt.Sprintf(`{"ticket":%q,"confirm":false}`, preview.Ticket)); w.Code != 400 {
		t.Fatalf("unconfirmed %d", w.Code)
	}
	// A changed file invalidates the original review and consumes its capability.
	os.WriteFile(target, []byte("changed test book"), 0600)
	if w := removalPost(handler, "/v1/media/remove", fmt.Sprintf(`{"ticket":%q,"confirm":true}`, preview.Ticket)); w.Code != 409 || deletes != 0 {
		t.Fatalf("changed file %d deletes %d", w.Code, deletes)
	}
	preview = prepare()
	body := fmt.Sprintf(`{"ticket":%q,"confirm":true}`, preview.Ticket)
	if w := removalPost(handler, "/v1/media/remove", body); w.Code != 200 {
		t.Fatalf("delete %d %s", w.Code, w.Body.String())
	}
	if deletes != 1 {
		t.Fatalf("deletes=%d", deletes)
	}
	if _, err := os.Stat(other); err != nil {
		t.Fatal("unselected file removed")
	}
	if w := removalPost(handler, "/v1/media/remove", body); w.Code != 409 || deletes != 1 {
		t.Fatal("ticket replay permitted")
	}
	readonly := NewServer(readingCatalogConfig(upstream.URL, "", []string{"reading"})).Handler()
	if w := removalPost(readonly, "/v1/media/removal-preview", `{"kind":"reading","id":"x"}`); w.Code != 403 {
		t.Fatalf("scope %d", w.Code)
	}
}

func TestVideoRemovalUsesNativeIdentityAndRechecksScope(t *testing.T) {
	const id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	deletes := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "DELETE" {
			if r.URL.Path != "/Items/"+id {
				t.Error("wrong target")
			}
			deletes++
			w.WriteHeader(204)
			return
		}
		if r.URL.Path == "/Items/"+id {
			io.WriteString(w, `{"Id":"`+id+`","Type":"Movie","Name":"Fixture movie","Path":"D:/Movies/fixture.mkv","MediaSources":[{"Id":"source","Path":"D:/Movies/fixture.mkv","Size":10}]}`)
			return
		}
		io.WriteString(w, `{"Items":[],"TotalRecordCount":0}`)
	}))
	defer upstream.Close()
	cfg := libraryAPIConfig(upstream.URL, "user-1")
	cfg.Auth.Tokens[0].Scopes = []string{"control", "play"}
	server := NewServer(cfg)
	handler := server.Handler()
	w := removalPost(handler, "/v1/media/removal-preview", `{"kind":"video","id":"`+id+`"}`)
	if w.Code != 200 {
		t.Fatalf("preview %d %s", w.Code, w.Body.String())
	}
	var preview removalPreview
	json.Unmarshal(w.Body.Bytes(), &preview)
	if deletes != 0 || preview.FileCount != 1 {
		t.Fatal("preview mutated or omitted media")
	}
	w = removalPost(handler, "/v1/media/remove", fmt.Sprintf(`{"ticket":%q,"confirm":true}`, preview.Ticket))
	if w.Code != 200 || deletes != 1 {
		t.Fatalf("delete %d %s", w.Code, w.Body.String())
	}
}

func TestStorytellerRemovalIncludesExactAudioTracksAndReadalong(t *testing.T) {
	folder := t.TempDir()
	os.Mkdir(filepath.Join(folder, "audio"), 0700)
	for _, file := range []string{"book.epub", "aligned.epub", "audio/one.m4b", "audio/keep.m4b"} {
		os.WriteFile(filepath.Join(folder, file), []byte("fixture"), 0600)
	}
	const book = `{"id":12,"uuid":"book-12","title":"Fixture book","ebook":{"filepath":"/library/book.epub"},"readaloud":{"filepath":"/library/aligned.epub"},"audiobook":{"filepath":"/library/audio","manifest":{"readingOrder":[{"href":"one.m4b"}]}}}`
	deletes := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/token":
			io.WriteString(w, `{"access_token":"fixture-token","token_type":"bearer","expires_in":3600}`)
		case "/api/v2/books":
			io.WriteString(w, "["+book+"]")
		case "/api/v2/books/12":
			if r.Method == "DELETE" {
				if r.URL.Query().Get("preventReImport") != "true" || r.Header.Get("Authorization") != "Bearer fixture-token" {
					t.Error("invalid authenticated deletion")
				}
				deletes++
				w.WriteHeader(204)
			} else {
				io.WriteString(w, book)
			}
		default:
			t.Errorf("unexpected %s", r.URL)
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	cfg := readingCatalogConfig(upstream.URL, "", []string{"reading", "control"})
	cfg.Server.MediaRemovalRoots = []config.MediaRemovalRoot{{Service: "storyteller", Remote: "/library", Local: folder}}
	server := NewServer(cfg)
	id, _ := server.readingCatalog.Bind(readingdomain.WorkBinding{Source: "storyteller", SourceID: "12"})
	handler := server.Handler()
	response := removalPost(handler, "/v1/media/removal-preview", fmt.Sprintf(`{"kind":"reading","id":%q}`, id))
	if response.Code != 200 {
		t.Fatalf("preview %d %s", response.Code, response.Body.String())
	}
	var preview removalPreview
	json.Unmarshal(response.Body.Bytes(), &preview)
	if preview.FileCount != 3 {
		t.Fatalf("scope %+v", preview)
	}
	response = removalPost(handler, "/v1/media/remove", fmt.Sprintf(`{"ticket":%q,"confirm":true}`, preview.Ticket))
	if response.Code != 200 || deletes != 1 {
		t.Fatalf("delete %d %s", response.Code, response.Body.String())
	}
	for _, name := range []string{"book.epub", "aligned.epub", "audio/one.m4b"} {
		if _, err := os.Stat(filepath.Join(folder, name)); !os.IsNotExist(err) {
			t.Error("confirmed file survived", name)
		}
	}
	if _, err := os.Stat(filepath.Join(folder, "audio/keep.m4b")); err != nil {
		t.Fatal("unlisted audio track deleted")
	}
}
