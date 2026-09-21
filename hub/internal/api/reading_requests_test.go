package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"ayaneohub/internal/adapters/bookkeeprr"
	"ayaneohub/internal/config"
)

func readingAcquisitionConfig(baseURL string, scopes []string, withAdmin bool) *config.Config {
	cfg := readingAPIConfig(baseURL, scopes)
	service := cfg.Services["bookkeeprr"]
	if withAdmin {
		service.Username = "hub-admin"
		service.Password = config.Secret("secret")
	}
	cfg.Services["bookkeeprr"] = service
	return cfg
}

func readingJSONRequest(handler http.Handler, method, path, body string) *httptest.ResponseRecorder {
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(method, path, strings.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	request.Header.Set("Content-Type", "application/json")
	handler.ServeHTTP(recorder, request)
	return recorder
}

func TestReadingRequestOptionsAndSeriesRequest(t *testing.T) {
	createCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/discover/search":
			_, _ = w.Write([]byte(`{"results":[{"contentType":"ebook","source":"openlibrary","sourceId":"OL123W","title":"Red Rising","author":"Pierce Brown","year":2014,"isbn":"9780345539786","coverUrl":"https://covers.example/red-rising.jpg"}],"tookMs":4}`))
		case "/api/quality-profiles":
			if r.Header.Get("Authorization") != "Bearer book-key" {
				t.Fatalf("quality profile auth = %q", r.Header.Get("Authorization"))
			}
			_, _ = w.Write([]byte(`[{"id":7,"name":"English EPUB","preferCompleteBatches":true,"preferredLanguagesJson":"[\"en\"]","isDefault":true}]`))
		case "/api/auth/login":
			_, _ = w.Write([]byte(`{"user":{"id":1,"username":"hub-admin","role":"admin","mustChangePassword":false},"redirect_to":"bookkeeprr://hub/auth?exchange=one"}`))
		case "/api/mobile/exchange":
			_, _ = w.Write([]byte(`{"token":"admin-token","refresh_token":"refresh","expires_at":"2026-12-20T00:00:00Z"}`))
		case "/api/series":
			createCalls++
			if r.Header.Get("Authorization") != "Bearer admin-token" {
				t.Fatalf("create auth = %q", r.Header.Get("Authorization"))
			}
			var body map[string]any
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Fatal(err)
			}
			if body["contentType"] != "ebook" || body["flow"] != "series" || body["olid"] != "OL123W" || body["title"] != "Red Rising" || body["totalVolumes"] != float64(6) || body["qualityProfileId"] != float64(7) {
				t.Fatalf("create body = %#v", body)
			}
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"id":42}`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()

	handler := NewServer(readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)).Handler()
	search := libraryRequest(handler, "/v1/reading/search?q=Red%20Rising&type=ebook")
	if search.Code != http.StatusOK {
		t.Fatalf("search = %d: %s", search.Code, search.Body.String())
	}
	var found ReadingSearchResponse
	if err := json.Unmarshal(search.Body.Bytes(), &found); err != nil {
		t.Fatal(err)
	}
	if len(found.Results) != 1 {
		t.Fatalf("search body = %+v", found)
	}
	key := found.Results[0].Key

	optionsResponse := libraryRequest(handler, "/v1/reading/requests/options?key="+key)
	if optionsResponse.Code != http.StatusOK {
		t.Fatalf("options = %d: %s", optionsResponse.Code, optionsResponse.Body.String())
	}
	var options ReadingRequestOptions
	if err := json.Unmarshal(optionsResponse.Body.Bytes(), &options); err != nil {
		t.Fatal(err)
	}
	if options.Key != key || options.Title != "Red Rising" || len(options.Modes) != 2 || options.Modes[1].ID != "series" || !options.Modes[1].RequiresTotalBooks || len(options.QualityProfiles) != 1 || !options.QualityProfiles[0].Default {
		t.Fatalf("options = %+v", options)
	}

	createdResponse := readingJSONRequest(handler, http.MethodPost, "/v1/reading/requests",
		`{"key":"`+key+`","mode":"series","totalBooks":6,"qualityProfileId":7,"monitoring":"all"}`)
	if createdResponse.Code != http.StatusAccepted {
		t.Fatalf("create = %d: %s", createdResponse.Code, createdResponse.Body.String())
	}
	var created ReadingRequestResponse
	if err := json.Unmarshal(createdResponse.Body.Bytes(), &created); err != nil {
		t.Fatal(err)
	}
	if created.SeriesID != 42 || created.State != "accepted" || createCalls != 1 {
		t.Fatalf("created = %+v, calls = %d", created, createCalls)
	}
}

func TestReadingRequestValidationAndScopesPreventWrites(t *testing.T) {
	upstreamCalls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { upstreamCalls++ }))
	defer upstream.Close()

	withoutRequest := NewServer(readingAcquisitionConfig(upstream.URL, []string{"reading"}, true)).Handler()
	if got := libraryRequest(withoutRequest, "/v1/reading/requests/options?key=reading:00000000000000000000000000000000"); got.Code != http.StatusForbidden {
		t.Fatalf("options without request scope = %d", got.Code)
	}
	if got := readingJSONRequest(withoutRequest, http.MethodPost, "/v1/reading/requests", `{}`); got.Code != http.StatusForbidden {
		t.Fatalf("create without request scope = %d", got.Code)
	}

	withRequest := NewServer(readingAcquisitionConfig(upstream.URL, []string{"reading", "request"}, true)).Handler()
	for _, body := range []string{
		`{}`,
		`{"key":"bad","mode":"single","qualityProfileId":1}`,
		`{"key":"reading:00000000000000000000000000000000","mode":"single","qualityProfileId":1}`,
	} {
		got := readingJSONRequest(withRequest, http.MethodPost, "/v1/reading/requests", body)
		if got.Code != http.StatusBadRequest && got.Code != http.StatusNotFound {
			t.Errorf("body %s = %d: %s", body, got.Code, got.Body.String())
		}
	}
	if upstreamCalls != 0 {
		t.Fatalf("rejected requests made %d upstream calls", upstreamCalls)
	}
}

func TestReadingDownloadsAreNormalizedAndHideTorrentHash(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/downloads" {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write([]byte(`{"downloads":[{"id":9,"qbtHash":"0123456789abcdef0123456789abcdef01234567","status":"downloading","addedAt":"2026-09-21T10:00:00Z","progress":0.25,"downloadSpeed":4096,"eta":90,"seeds":4,"sizeBytes":12345,"release":{"id":3,"title":"Red Rising EPUB","indexerGuid":"secret-guid","indexerName":"Books"},"series":{"id":4,"title":"Red Rising","coverUrl":"/api/img/x","contentType":"ebook"}}]}`))
	}))
	defer upstream.Close()

	handler := NewServer(readingAcquisitionConfig(upstream.URL, []string{"reading"}, false)).Handler()
	got := libraryRequest(handler, "/v1/reading/downloads")
	if got.Code != http.StatusOK {
		t.Fatalf("downloads = %d: %s", got.Code, got.Body.String())
	}
	if strings.Contains(got.Body.String(), "0123456789abcdef") || strings.Contains(got.Body.String(), "secret-guid") {
		t.Fatalf("raw transport identity leaked: %s", got.Body.String())
	}
	var body ReadingDownloadsResponse
	if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if len(body.Items) != 1 || body.Items[0].ID != "reading:download:9" || body.Items[0].Title != "Red Rising" || body.Items[0].ProgressPercent != 25 || body.Items[0].Status != "downloading" {
		t.Fatalf("body = %+v", body)
	}
}

func TestReadingCreatePayloadMapsEverySupportedProviderIdentity(t *testing.T) {
	profile := 7
	tests := []struct {
		name  string
		item  bookkeeprr.Item
		body  readingCreateRequestBody
		check func(t *testing.T, got bookkeeprr.CreateSeriesRequest)
	}{
		{
			name: "audnex audiobook",
			item: bookkeeprr.Item{ContentType: bookkeeprr.TypeAudiobook, Source: "audnex", SourceID: "B001", Title: "Red Rising"},
			body: readingCreateRequestBody{Mode: "single", QualityProfileID: profile},
			check: func(t *testing.T, got bookkeeprr.CreateSeriesRequest) {
				if got.ASIN != "B001" || got.Title != "Red Rising" {
					t.Fatalf("got = %+v", got)
				}
			},
		},
		{
			name: "comicvine comic",
			item: bookkeeprr.Item{ContentType: bookkeeprr.TypeComic, Source: "comicvine", SourceID: "123", Title: "Saga", Author: "Image"},
			body: readingCreateRequestBody{Mode: "series", QualityProfileID: profile},
			check: func(t *testing.T, got bookkeeprr.CreateSeriesRequest) {
				if got.ComicvineID != 123 || got.TitleEnglish != "Saga" {
					t.Fatalf("got = %+v", got)
				}
			},
		},
		{
			name: "cross-linked manga",
			item: bookkeeprr.Item{ContentType: bookkeeprr.TypeManga, Title: "Nana", Sources: bookkeeprr.ItemSources{Anilist: intPointer(877), Mangadex: "md-1", Mal: intPointer(88)}},
			body: readingCreateRequestBody{Mode: "series", QualityProfileID: profile},
			check: func(t *testing.T, got bookkeeprr.CreateSeriesRequest) {
				if got.AnilistID == nil || *got.AnilistID != 877 || got.MangadexID != "md-1" || got.MalID == nil || *got.MalID != 88 {
					t.Fatalf("got = %+v", got)
				}
			},
		},
		{
			name: "novel updates light novel",
			item: bookkeeprr.Item{ContentType: bookkeeprr.TypeLightNovel, Source: "novelupdates", SourceID: "nu:solo-leveling", Title: "Solo Leveling"},
			body: readingCreateRequestBody{Mode: "series", QualityProfileID: profile},
			check: func(t *testing.T, got bookkeeprr.CreateSeriesRequest) {
				if got.NovelUpdatesSlug != "solo-leveling" || got.TitleEnglish != "Solo Leveling" {
					t.Fatalf("got = %+v", got)
				}
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := readingCreatePayload(test.item, test.body)
			if err != nil {
				t.Fatal(err)
			}
			test.check(t, got)
		})
	}
	if _, err := readingCreatePayload(
		bookkeeprr.Item{ContentType: bookkeeprr.TypeEbook, Title: "Series"},
		readingCreateRequestBody{Mode: "series", TotalBooks: 0, QualityProfileID: profile},
	); err == nil {
		t.Fatal("series request accepted zero books")
	}
}

func TestReadingCandidateStoresNormalizedFallbackType(t *testing.T) {
	server := NewServer(&config.Config{})
	result := server.readingItem(bookkeeprr.Item{
		Source: "openlibrary", SourceID: "OL123W", Title: "Red Rising",
	}, bookkeeprr.TypeEbook)

	stored, ok := server.readingCandidates.get(result.Key)
	if !ok {
		t.Fatal("normalized reading candidate was not stored")
	}
	if result.ContentType != string(bookkeeprr.TypeEbook) || stored.ContentType != bookkeeprr.TypeEbook {
		t.Fatalf("response type = %q, stored type = %q", result.ContentType, stored.ContentType)
	}
}

func intPointer(value int) *int { return &value }
