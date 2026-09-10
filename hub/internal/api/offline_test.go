package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"ayaneohub/internal/config"
)

const (
	offlineSeriesID  = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	offlineSeasonID  = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	offlineItemID    = "cccccccccccccccccccccccccccccccc"
	offlineSourceID  = "source-original"
	offlineOtherUser = "dddddddddddddddddddddddddddddddd"
)

type offlineUpstream struct {
	t              *testing.T
	server         *httptest.Server
	mu             sync.Mutex
	stopped        int
	lastPlayedDate string
}

func newOfflineUpstream(t *testing.T) *offlineUpstream {
	u := &offlineUpstream{t: t}
	u.server = httptest.NewServer(http.HandlerFunc(u.serve))
	return u
}

func (u *offlineUpstream) close() { u.server.Close() }

func (u *offlineUpstream) episodeJSON() string {
	return `{"Id":"` + offlineItemID + `","Name":"Arrival","Type":"Episode",` +
		`"SeriesName":"A Series","SeriesId":"` + offlineSeriesID + `","SeasonId":"` + offlineSeasonID + `",` +
		`"ParentIndexNumber":1,"IndexNumber":1,"RunTimeTicks":24000000000,` +
		`"ImageTags":{"Primary":"episode-image"},` +
		`"UserData":{"Played":false,"PlaybackPositionTicks":120000000` +
		func() string {
			if u.lastPlayedDate == "" {
				return ""
			}
			return `,"LastPlayedDate":"` + u.lastPlayedDate + `"`
		}() + `},` +
		`"MediaSources":[{"Id":"` + offlineSourceID + `","Name":"Original 1080p","Container":"mkv",` +
		`"Size":8,"Bitrate":8000000,"MediaStreams":[` +
		`{"Index":0,"Type":"Video","Codec":"h264","Width":1920,"Height":1080},` +
		`{"Index":1,"Type":"Audio","Codec":"aac","Language":"eng","DisplayTitle":"English","IsDefault":true},` +
		`{"Index":2,"Type":"Subtitle","Codec":"srt","Language":"heb","DisplayTitle":"Hebrew",` +
		`"IsExternal":true,"DeliveryUrl":"/Videos/` + offlineItemID + `/` + offlineSourceID + `/Subtitles/2/0/Stream.srt"}]}]}`
}

func (u *offlineUpstream) serve(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("X-Emby-Token") != "test-key" {
		u.t.Errorf("missing Jellyfin credential on %s", r.URL.Path)
	}
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/Items/"+offlineSeriesID:
		_, _ = io.WriteString(w, `{"Id":"`+offlineSeriesID+`","Name":"A Series","Type":"Series"}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Shows/"+offlineSeriesID+"/Seasons":
		_, _ = io.WriteString(w, `{"Items":[{"Id":"`+offlineSeasonID+`","Name":"Season 1","Type":"Season","IndexNumber":1}]}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Shows/"+offlineSeriesID+"/Episodes":
		if !strings.Contains(r.URL.Query().Get("fields"), "MediaSources") {
			u.t.Errorf("selection did not request media sources: %v", r.URL.Query())
		}
		_, _ = io.WriteString(w, `{"Items":[`+u.episodeJSON()+`]}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Shows/NextUp":
		_, _ = io.WriteString(w, `{"Items":[{"Id":"`+offlineItemID+`"}]}`)
	case r.Method == http.MethodGet && r.URL.Path == "/Items/"+offlineItemID:
		_, _ = io.WriteString(w, u.episodeJSON())
	case r.Method == http.MethodGet && r.URL.Path == "/Videos/"+offlineItemID+"/stream":
		if r.URL.Query().Get("static") != "true" || r.URL.Query().Get("mediaSourceId") != offlineSourceID {
			u.t.Errorf("media query = %v", r.URL.Query())
		}
		if r.Header.Get("Range") != "bytes=2-5" {
			u.t.Errorf("range = %q", r.Header.Get("Range"))
		}
		w.Header().Set("Content-Range", "bytes 2-5/8")
		w.Header().Set("Accept-Ranges", "bytes")
		w.Header().Set("Content-Type", "video/x-matroska")
		w.WriteHeader(http.StatusPartialContent)
		_, _ = io.WriteString(w, "2345")
	case r.Method == http.MethodGet && strings.Contains(r.URL.Path, "/Subtitles/2/"):
		_, _ = io.WriteString(w, "subtitle")
	case r.Method == http.MethodPost && r.URL.Path == "/Sessions/Playing/Stopped":
		u.mu.Lock()
		u.stopped++
		u.mu.Unlock()
		w.WriteHeader(http.StatusNoContent)
	default:
		http.NotFound(w, r)
	}
}

func offlineConfig(upstreamURL, registry string) *config.Config {
	cfg := libraryAPIConfig(upstreamURL, playbackUserID)
	cfg.Server.OfflineRegistry = registry
	return cfg
}

func prepareOffline(t *testing.T, handler http.Handler) OfflineManifest {
	t.Helper()
	body := `{"batchKey":"batch-1","seriesId":"` + offlineSeriesID + `","items":[{` +
		`"clientItemKey":"episode-1","itemId":"` + offlineItemID + `"}]}`
	recorder := playbackRequest(handler, http.MethodPost, "/v1/offline/prepare", body, playbackUserID)
	if recorder.Code != http.StatusOK {
		t.Fatalf("prepare returned %d: %s", recorder.Code, recorder.Body.String())
	}
	var response OfflinePrepareResponse
	if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if len(response.Items) != 1 {
		t.Fatalf("manifests = %+v", response.Items)
	}
	return response.Items[0]
}

func TestOfflineSelectionPreparePersistenceRangeAndSubtitle(t *testing.T) {
	upstream := newOfflineUpstream(t)
	defer upstream.close()
	registry := filepath.Join(t.TempDir(), "offline-grants.json")
	server := NewServer(offlineConfig(upstream.server.URL, registry))
	handler := server.Handler()

	selection := playbackRequest(handler, http.MethodGet,
		"/v1/offline/series/"+offlineSeriesID+"/selection", "", playbackUserID)
	if selection.Code != http.StatusOK {
		t.Fatalf("selection returned %d: %s", selection.Code, selection.Body.String())
	}
	var catalog OfflineSelectionResponse
	if err := json.NewDecoder(selection.Body).Decode(&catalog); err != nil {
		t.Fatal(err)
	}
	if catalog.EpisodeCount != 1 || catalog.EstimatedSizeBytes != 8 || len(catalog.Seasons) != 1 ||
		!catalog.Seasons[0].Episodes[0].Available || len(catalog.Seasons[0].Episodes[0].Sources[0].Tracks) != 3 {
		t.Fatalf("selection = %+v", catalog)
	}

	manifest := prepareOffline(t, handler)
	if manifest.Source.SizeBytes != 8 || len(manifest.Subtitles) != 1 || manifest.Item.SeriesID != offlineSeriesID {
		t.Fatalf("manifest = %+v", manifest)
	}
	if duplicate := prepareOffline(t, handler); duplicate.GrantID != manifest.GrantID {
		t.Fatalf("idempotent prepare changed grant %q to %q", manifest.GrantID, duplicate.GrantID)
	}

	// Recreate the API server to prove the capability survives a Hub restart.
	restarted := NewServer(offlineConfig(upstream.server.URL, registry)).Handler()
	request := httptest.NewRequest(http.MethodGet, manifest.MediaURL, nil)
	request.Header.Set("Authorization", "Bearer "+libraryTestToken)
	request.Header.Set(jellyfinUserHeader, playbackUserID)
	request.Header.Set("Range", "bytes=2-5")
	media := httptest.NewRecorder()
	restarted.ServeHTTP(media, request)
	if media.Code != http.StatusPartialContent || media.Body.String() != "2345" ||
		media.Header().Get("Content-Range") != "bytes 2-5/8" {
		t.Fatalf("media = %d %v %q", media.Code, media.Header(), media.Body.String())
	}
	subtitle := playbackRequest(restarted, http.MethodGet, manifest.Subtitles[0].URL, "", playbackUserID)
	if subtitle.Code != http.StatusOK || subtitle.Body.String() != "subtitle" {
		t.Fatalf("subtitle = %d %q", subtitle.Code, subtitle.Body.String())
	}
	wrongUser := playbackRequest(restarted, http.MethodGet, manifest.MediaURL, "", offlineOtherUser)
	if wrongUser.Code != http.StatusNotFound {
		t.Fatalf("other user received %d", wrongUser.Code)
	}
}

func TestOfflineGrantRenewalAndDownloadScope(t *testing.T) {
	upstream := newOfflineUpstream(t)
	defer upstream.close()
	cfg := offlineConfig(upstream.server.URL, filepath.Join(t.TempDir(), "registry.json"))
	server := NewServer(cfg)
	manifest := prepareOffline(t, server.Handler())
	grant, _ := server.offline.get(manifest.GrantID)
	grant.ExpiresAt = time.Now().Add(-time.Minute).UnixMilli()
	grant.Manifest.ExpiresAt = grant.ExpiresAt
	if err := server.offline.put(grant); err != nil {
		t.Fatal(err)
	}
	if expired := playbackRequest(server.Handler(), http.MethodGet, manifest.MediaURL, "", playbackUserID); expired.Code != http.StatusGone {
		t.Fatalf("expired media returned %d", expired.Code)
	}
	renewed := playbackRequest(server.Handler(), http.MethodPost,
		"/v1/offline/grants/"+manifest.GrantID+"/renew", `{}`, playbackUserID)
	if renewed.Code != http.StatusOK {
		t.Fatalf("renew returned %d: %s", renewed.Code, renewed.Body.String())
	}
	var renewedManifest OfflineManifest
	if err := json.NewDecoder(renewed.Body).Decode(&renewedManifest); err != nil {
		t.Fatal(err)
	}
	if renewedManifest.GrantID != manifest.GrantID || renewedManifest.ExpiresAt <= time.Now().UnixMilli() {
		t.Fatalf("renewed manifest = %+v", renewedManifest)
	}

	noDownload := offlineConfig(upstream.server.URL, "")
	noDownload.Auth.Tokens[0].Scopes = []string{"read", "play"}
	denied := playbackRequest(NewServer(noDownload).Handler(), http.MethodGet,
		"/v1/offline/series/"+offlineSeriesID+"/selection", "", playbackUserID)
	if denied.Code != http.StatusForbidden {
		t.Fatalf("token without download scope returned %d", denied.Code)
	}
}

func TestOfflineProgressIsIdempotentAndDoesNotOverwriteNewerServerState(t *testing.T) {
	upstream := newOfflineUpstream(t)
	defer upstream.close()
	server := NewServer(offlineConfig(upstream.server.URL, filepath.Join(t.TempDir(), "registry.json")))
	handler := server.Handler()
	body := `{"events":[{"clientEventKey":"watch-1","itemId":"` + offlineItemID + `",` +
		`"positionMillis":90000,"durationMillis":2400000,"occurredAt":` +
		strconv.FormatInt(time.Now().Add(-time.Minute).UnixMilli(), 10) + `}]}`
	first := playbackRequest(handler, http.MethodPost, "/v1/offline/progress/sync", body, playbackUserID)
	if first.Code != http.StatusOK || !strings.Contains(first.Body.String(), `"status":"applied"`) {
		t.Fatalf("first sync = %d %s", first.Code, first.Body.String())
	}
	second := playbackRequest(handler, http.MethodPost, "/v1/offline/progress/sync", body, playbackUserID)
	if second.Code != http.StatusOK || !strings.Contains(second.Body.String(), `"status":"duplicate"`) {
		t.Fatalf("second sync = %d %s", second.Code, second.Body.String())
	}
	upstream.mu.Lock()
	if upstream.stopped != 1 {
		t.Fatalf("stopped events = %d", upstream.stopped)
	}
	upstream.mu.Unlock()

	upstream.lastPlayedDate = time.Now().Add(time.Minute).Format(time.RFC3339Nano)
	newerBody := strings.Replace(body, "watch-1", "watch-2", 1)
	newer := playbackRequest(handler, http.MethodPost, "/v1/offline/progress/sync", newerBody, playbackUserID)
	if newer.Code != http.StatusOK || !strings.Contains(newer.Body.String(), `"status":"server_newer"`) {
		t.Fatalf("newer sync = %d %s", newer.Code, newer.Body.String())
	}
}
