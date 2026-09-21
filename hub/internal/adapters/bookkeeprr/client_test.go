package bookkeeprr

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"ayaneohub/internal/config"
)

func TestBrowseUsesBearerAuthAndDecodesRows(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/discover/browse" || r.URL.Query().Get("contentType") != "ebook" {
			t.Fatalf("request = %s %s", r.Method, r.URL.String())
		}
		if got := r.Header.Get("Authorization"); got != "Bearer test-key" {
			t.Fatalf("Authorization = %q", got)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"rows":[{"id":"ebook-trending","label":"Trending books","meta":"Popular now","items":[{"contentType":"ebook","source":"openlibrary","sourceId":"OL123W","title":"Red Rising","author":"Pierce Brown","year":2014,"isbn":"9780345539786","coverUrl":"https://covers.example/red-rising.jpg","description":"The first book.","inLib":true}]}]}`))
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("test-key")})
	if err != nil {
		t.Fatal(err)
	}
	got, err := client.Browse(context.Background(), TypeEbook)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Rows) != 1 || got.Rows[0].ID != "ebook-trending" || len(got.Rows[0].Items) != 1 {
		t.Fatalf("Browse() = %+v", got)
	}
	item := got.Rows[0].Items[0]
	if item.Title != "Red Rising" || item.Author != "Pierce Brown" || !item.InLibrary {
		t.Fatalf("item = %+v", item)
	}
}

func TestQualityProfilesAndDownloadsUseReadBearer(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer read-key" {
			t.Fatalf("Authorization = %q", got)
		}
		switch r.URL.Path {
		case "/api/quality-profiles":
			_, _ = w.Write([]byte(`[{"id":7,"name":"Books","isDefault":true,"preferCompleteBatches":true}]`))
		case "/api/downloads":
			_, _ = w.Write([]byte(`{"downloads":[{"id":9,"qbtHash":"abcdef","status":"downloading","progress":0.5,"downloadSpeed":2048,"eta":30,"series":{"id":4,"title":"Red Rising","coverUrl":"/api/img/x","contentType":"ebook"}}]}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("read-key")})
	if err != nil {
		t.Fatal(err)
	}
	profiles, err := client.QualityProfiles(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(profiles) != 1 || profiles[0].ID != 7 || !profiles[0].IsDefault {
		t.Fatalf("profiles = %+v", profiles)
	}
	downloads, err := client.Downloads(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(downloads.Downloads) != 1 || downloads.Downloads[0].Status != "downloading" || downloads.Downloads[0].Series == nil || downloads.Downloads[0].Series.Title != "Red Rising" {
		t.Fatalf("downloads = %+v", downloads)
	}
}

func TestCreateSeriesUsesMobileAdminTokenAndRenewsOnce(t *testing.T) {
	loginCalls := 0
	exchangeCalls := 0
	createCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/auth/login":
			loginCalls++
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body["username"] != "hub-admin" || body["password"] != "secret" || !strings.HasPrefix(body["return_to"].(string), "bookkeeprr://") {
				t.Fatalf("login body = %#v", body)
			}
			_, _ = w.Write([]byte(`{"user":{"id":1,"username":"hub-admin","role":"admin","mustChangePassword":false},"redirect_to":"bookkeeprr://hub/auth?exchange=code-` + string(rune('0'+loginCalls)) + `"}`))
		case "/api/mobile/exchange":
			exchangeCalls++
			var body map[string]string
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			want := "code-" + string(rune('0'+exchangeCalls))
			if body["exchange_code"] != want {
				t.Fatalf("exchange code = %q, want %q", body["exchange_code"], want)
			}
			_, _ = w.Write([]byte(`{"token":"admin-` + string(rune('0'+exchangeCalls)) + `","refresh_token":"refresh","expires_at":"2026-12-20T00:00:00Z"}`))
		case "/api/series":
			createCalls++
			if createCalls == 1 {
				if r.Header.Get("Authorization") != "Bearer admin-1" {
					t.Fatalf("first token = %q", r.Header.Get("Authorization"))
				}
				http.Error(w, "expired", http.StatusUnauthorized)
				return
			}
			if r.Header.Get("Authorization") != "Bearer admin-2" {
				t.Fatalf("renewed token = %q", r.Header.Get("Authorization"))
			}
			var body CreateSeriesRequest
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body.ContentType != TypeEbook || body.Flow != "series" || body.Title != "Red Rising" || body.TotalVolumes != 6 || body.QualityProfileID != 7 {
				t.Fatalf("create body = %+v", body)
			}
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"id":42}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{
		BaseURL: upstream.URL, APIKey: config.Secret("read-key"),
		Username: "hub-admin", Password: config.Secret("secret"),
	})
	if err != nil {
		t.Fatal(err)
	}
	created, err := client.CreateSeries(context.Background(), CreateSeriesRequest{
		ContentType: TypeEbook, Flow: "series", OLID: "OL123W", Title: "Red Rising",
		TotalVolumes: 6, QualityProfileID: 7, Monitoring: "all",
	})
	if err != nil {
		t.Fatal(err)
	}
	if created.ID != 42 || loginCalls != 2 || exchangeCalls != 2 || createCalls != 2 {
		t.Fatalf("created=%+v login=%d exchange=%d create=%d", created, loginCalls, exchangeCalls, createCalls)
	}
}

func TestCreateSeriesWithoutAdminCredentialsDoesNotCallUpstream(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("read-key")})
	if err != nil {
		t.Fatal(err)
	}
	if client.CanRequest() {
		t.Fatal("CanRequest() = true without username/password")
	}
	if _, err := client.CreateSeries(context.Background(), CreateSeriesRequest{}); err == nil {
		t.Fatal("CreateSeries succeeded without admin credentials")
	}
	if calls != 0 {
		t.Fatalf("made %d upstream calls", calls)
	}
}

func TestCreateSeriesRejectsInteractiveTOTPServiceAccount(t *testing.T) {
	seriesCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/auth/login" {
			_, _ = w.Write([]byte(`{"requiresTotp":true,"challengeToken":"challenge"}`))
			return
		}
		if r.URL.Path == "/api/series" {
			seriesCalls++
		}
	}))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{
		BaseURL: upstream.URL, APIKey: config.Secret("read-key"),
		Username: "hub-admin", Password: config.Secret("secret"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.CreateSeries(context.Background(), CreateSeriesRequest{ContentType: TypeEbook}); err == nil || !strings.Contains(err.Error(), "two-factor") {
		t.Fatalf("CreateSeries error = %v", err)
	}
	if seriesCalls != 0 {
		t.Fatalf("made %d create calls", seriesCalls)
	}
}

func TestCategoryCarriesTypeRowAndPage(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		query := r.URL.Query()
		if r.URL.Path != "/api/discover/category" || query.Get("contentType") != "manga" ||
			query.Get("row") != "trending" || query.Get("page") != "3" {
			t.Fatalf("request = %s", r.URL.String())
		}
		_, _ = w.Write([]byte(`{"items":[{"contentType":"manga","source":"anilist","sourceId":42,"title":"Frieren"}],"hasMore":true}`))
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
	if err != nil {
		t.Fatal(err)
	}
	got, err := client.Category(context.Background(), TypeManga, "trending", 3)
	if err != nil {
		t.Fatal(err)
	}
	if !got.HasMore || len(got.Items) != 1 || got.Items[0].SourceID != "42" {
		t.Fatalf("Category() = %+v", got)
	}
}

func TestSearchSupportsAllTypesAndReportsProviderErrors(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/discover/search" || r.URL.Query().Get("q") != "Red Rising" ||
			r.URL.Query().Get("contentType") != "all" {
			t.Fatalf("request = %s", r.URL.String())
		}
		_, _ = w.Write([]byte(`{"results":[{"contentType":"audiobook","source":"audnex","sourceId":"abc","title":"Red Rising"}],"tookMs":91,"errors":[{"source":"openlibrary","message":"timed out"}]}`))
	}))
	defer upstream.Close()

	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
	if err != nil {
		t.Fatal(err)
	}
	got, err := client.Search(context.Background(), "Red Rising", TypeAll)
	if err != nil {
		t.Fatal(err)
	}
	if len(got.Results) != 1 || got.TookMS != 91 || len(got.Errors) != 1 || got.Errors[0].Source != "openlibrary" {
		t.Fatalf("Search() = %+v", got)
	}
}

func TestSearchAcceptsProviderErrorsFromInstalledBookKeeprr(t *testing.T) {
	tests := []struct {
		name string
		body string
		want []ProviderError
	}{
		{
			name: "keyed object",
			body: `{"results":[],"tookMs":12,"errors":{"openlibrary":"timed out","googlebooks":"quota exceeded"}}`,
			want: []ProviderError{
				{Source: "googlebooks", Message: "quota exceeded"},
				{Source: "openlibrary", Message: "timed out"},
			},
		},
		{
			name: "null",
			body: `{"results":[],"tookMs":12,"errors":null}`,
			want: []ProviderError{},
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_, _ = w.Write([]byte(test.body))
			}))
			defer upstream.Close()

			client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
			if err != nil {
				t.Fatal(err)
			}
			got, err := client.Search(context.Background(), "Red Rising", TypeAll)
			if err != nil {
				t.Fatal(err)
			}
			if len(got.Errors) != len(test.want) {
				t.Fatalf("errors = %+v, want %+v", got.Errors, test.want)
			}
			for i := range test.want {
				if got.Errors[i] != test.want[i] {
					t.Fatalf("errors[%d] = %+v, want %+v", i, got.Errors[i], test.want[i])
				}
			}
		})
	}
}

func TestRejectsInvalidInputsBeforeCallingUpstream(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer upstream.Close()
	client, err := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
	if err != nil {
		t.Fatal(err)
	}

	if _, err := client.Browse(context.Background(), ContentType("movie")); err == nil {
		t.Error("Browse accepted a media content type")
	}
	if _, err := client.Category(context.Background(), TypeEbook, "", 1); err == nil {
		t.Error("Category accepted an empty row")
	}
	if _, err := client.Category(context.Background(), TypeEbook, "row", 0); err == nil {
		t.Error("Category accepted page zero")
	}
	if _, err := client.Search(context.Background(), "  ", TypeAll); err == nil {
		t.Error("Search accepted an empty query")
	}
	if calls != 0 {
		t.Fatalf("invalid inputs made %d upstream requests", calls)
	}
}
