package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/adapters/kavita"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/config"
)

func TestNotificationsIncludeBookDownloadsMissingRequestsAndAlignment(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/downloads":
			if r.Header.Get("Authorization") != "Bearer book-key" {
				t.Error("BookKeeprr authentication missing")
			}
			_, _ = w.Write([]byte(`{"downloads":[{"id":3,"status":"imported","addedAt":"2026-09-20T10:00:00Z","series":{"id":3,"title":"Finished Book","contentType":"ebook"}}]}`))
		case "/api/series":
			_, _ = w.Write([]byte(`{"rows":[{"id":4,"title":"Missing Audio","contentType":"audiobook","monitored":true,"downloaded":0,"addedAt":"2026-09-21T10:00:00Z"}],"total":1,"page":1,"limit":100}`))
		case "/api/v2/token":
			_, _ = w.Write([]byte(`{"access_token":"story-token","token_type":"Bearer","expires_in":3600}`))
		case "/api/v2/books":
			_, _ = w.Write([]byte(`[{"id":9,"title":"Paired Book","readaloud":{"uuid":"r9","status":"PROCESSING","currentStage":"ALIGN_SENTENCES"}}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	cfg := notificationTestConfig("", "", "")
	cfg.Services["bookkeeprr"] = config.ServiceConfig{Enabled: true, BaseURL: upstream.URL, APIKey: config.Secret("book-key")}
	cfg.Services["storyteller"] = config.ServiceConfig{Enabled: true, BaseURL: upstream.URL, Username: "worker", Password: config.Secret("secret")}
	response := libraryRequest(NewServer(cfg).Handler(), "/v1/notifications")
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d: %s", response.Code, response.Body.String())
	}
	var body NotificationsResponse
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Sections) != 5 || body.Sections[3].Service != "bookkeeprr" || body.Sections[4].Service != "storyteller" {
		t.Fatalf("sections = %+v", body.Sections)
	}
	if len(body.Sections[3].Items) != 2 || body.Sections[3].Items[0].Kind != "wanted" || len(body.Sections[4].Items) != 1 {
		t.Fatalf("book notices = %+v / %+v", body.Sections[3].Items, body.Sections[4].Items)
	}
}

func TestKavitaLibraryActivityConfirmsComicIsReadable(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/Library/libraries":
			_, _ = w.Write([]byte(`[{"id":2,"name":"Comics","type":1}]`))
		case "/api/Series/v2":
			if r.Method != http.MethodPost || r.Header.Get("X-Api-Key") != "kavita-key" {
				t.Fatalf("series auth: %s %q", r.Method, r.Header.Get("X-Api-Key"))
			}
			_, _ = w.Write([]byte(`[{"id":7,"name":"A Comic","created":"2026-09-23T10:00:00Z"}]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, err := kavita.New(config.ServiceConfig{Enabled: true, BaseURL: upstream.URL, APIKey: config.Secret("kavita-key")})
	if err != nil {
		t.Fatal(err)
	}
	section, partial := loadKavitaNotifications(context.Background(), client, 40)
	if len(partial) != 0 || len(section.Items) != 1 || section.Items[0].Kind != "library" || section.Items[0].Title != "A Comic · Ready in Library" {
		t.Fatalf("Kavita activity: %+v partial=%+v", section, partial)
	}
}

func TestBookDownloadNoticeKeepsStableIdentityAcrossProgress(t *testing.T) {
	series := &bookkeeprr.DownloadSeries{ID: 17, Title: "The Final Empire", ContentType: bookkeeprr.TypeAudiobook}
	a := bookDownloadNotification(bookkeeprr.Download{ID: 9, Series: series, Status: "downloading"})
	progress := .47
	b := bookDownloadNotification(bookkeeprr.Download{ID: 9, Series: series, Status: "downloading", Progress: &progress})
	if a.ID != b.ID || b.Service != "bookkeeprr" || b.Title == "" {
		t.Fatalf("progress created a fresh unread event or empty notice: %+v %+v", a, b)
	}
	imported := bookDownloadNotification(bookkeeprr.Download{ID: 9, Series: series, Status: "imported"})
	if imported.ID == b.ID || imported.Severity != "success" {
		t.Fatalf("import transition should be a new success event: %+v", imported)
	}
}

func TestBooksActivityDistinguishesChoiceImportAndLibraryReady(t *testing.T) {
	choice, ok := bookWantedNotification(bookkeeprr.SeriesRecord{ID: 7, Title: "A Comic", Monitoring: "none"}, false)
	if !ok || choice.Kind != "choice" || !choice.Active || choice.Title != "A Comic · Choose a release" {
		t.Fatalf("manual choice = %+v %v", choice, ok)
	}
	importing := bookDownloadNotification(bookkeeprr.Download{ID: 3, Status: "importing"})
	if importing.Title != "Reading download · Adding to reading library" || !importing.Active {
		t.Fatalf("importing = %+v", importing)
	}
	imported := bookDownloadNotification(bookkeeprr.Download{ID: 3, Status: "imported"})
	if imported.Title != "Reading download · Imported by BookKeeprr" || imported.Active {
		t.Fatalf("imported = %+v", imported)
	}
	ready, ok := storytellerLibraryNotification(storyteller.Book{ID: 4, Title: "The Book", CreatedAt: "2026-09-23T10:00:00Z", Ebook: &storyteller.Ebook{}})
	if !ok || ready.Kind != "library" || ready.Title != "The Book · Ready in Library" {
		t.Fatalf("library = %+v %v", ready, ok)
	}
}

func TestReadaloudNoticeDoesNotReportCompletedStageAsActive(t *testing.T) {
	book := storyteller.Book{ID: 4, Title: "Dark Matter", Readaloud: &storyteller.Readaloud{
		UUID: "aligned", Status: "ALIGNED", CurrentStage: "SYNC_CHAPTERS", StageProgress: .3,
	}}
	notice, ok := readaloudNotification(book)
	if !ok || notice.Active || notice.Severity != "success" || notice.Detail == "" {
		t.Fatalf("aligned status should not look in progress: %+v %v", notice, ok)
	}
	book.Readaloud.Status = "PROCESSING"
	notice, ok = readaloudNotification(book)
	if !ok || !notice.Active || notice.Severity != "info" {
		t.Fatalf("active alignment = %+v %v", notice, ok)
	}
	book.Readaloud.Status = "ERROR"
	notice, ok = readaloudNotification(book)
	if !ok || !notice.Active || notice.Severity != "error" {
		t.Fatalf("failed alignment = %+v %v", notice, ok)
	}
}
