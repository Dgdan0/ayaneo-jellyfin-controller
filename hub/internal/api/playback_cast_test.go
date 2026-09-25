package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCastGrantStreamsOnlyItsPlaybackAndRevokesOnStop(t *testing.T) {
	upstream := newPlaybackUpstream(t)
	defer upstream.close()
	server := NewServer(libraryAPIConfig(upstream.server.URL, playbackUserID))
	handler := server.Handler()
	plan := preparePlaybackForTest(t, handler)

	path := "/v1/playback/sessions/" + plan.SessionID + "/cast-grant"
	wrongUser := playbackRequest(handler, http.MethodPost, path, "", playbackNextID)
	if wrongUser.Code != http.StatusNotFound {
		t.Fatalf("another user created a TV grant: %d", wrongUser.Code)
	}
	created := playbackRequest(handler, http.MethodPost, path, "", playbackUserID)
	if created.Code != http.StatusOK {
		t.Fatalf("grant returned %d: %s", created.Code, created.Body.String())
	}
	var grant PlaybackCastGrantResponse
	if err := json.NewDecoder(created.Body).Decode(&grant); err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(grant.MediaURL, "/v1/cast/") ||
		strings.Contains(grant.MediaURL, plan.SessionID) || len(grant.SubtitleURLs) != 1 {
		t.Fatalf("unsafe or incomplete grant: %+v", grant)
	}

	request := httptest.NewRequest(http.MethodGet, grant.MediaURL, nil)
	request.Header.Set("Range", "bytes=2-5")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusPartialContent || response.Body.String() != "2345" ||
		response.Header().Get("Content-Range") != "bytes 2-5/8" ||
		response.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Fatalf("TV range = %d %v %q", response.Code, response.Header(), response.Body.String())
	}
	subtitle := httptest.NewRecorder()
	handler.ServeHTTP(subtitle, httptest.NewRequest(http.MethodGet, grant.SubtitleURLs["2"], nil))
	if subtitle.Code != http.StatusOK || !strings.Contains(subtitle.Body.String(), "Hello") {
		t.Fatalf("TV subtitle = %d %q", subtitle.Code, subtitle.Body.String())
	}

	stopped := playbackRequest(handler, http.MethodDelete,
		"/v1/playback/sessions/"+plan.SessionID, "", playbackUserID)
	if stopped.Code != http.StatusOK {
		t.Fatalf("stop returned %d", stopped.Code)
	}
	missing := httptest.NewRecorder()
	handler.ServeHTTP(missing, httptest.NewRequest(http.MethodGet, grant.MediaURL, nil))
	if missing.Code != http.StatusNotFound {
		t.Fatalf("revoked TV grant returned %d", missing.Code)
	}
}

func TestCastManifestUsesGrantURLsAndRejectsOtherItems(t *testing.T) {
	manifest := "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\nsegment.ts?token=hidden\n"
	base := "/v1/cast/secret/hls/"
	got, err := rewriteHLSManifestWithPrefix(strings.NewReader(manifest),
		"/Videos/"+playbackItemID+"/master.m3u8", playbackItemID, base)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Count(string(got), base) != 2 || strings.Contains(string(got), "token=hidden") ||
		strings.Contains(string(got), "/v1/playback/sessions/") {
		t.Fatalf("manifest leaked a private URL: %s", got)
	}
	if _, err := rewriteHLSManifestWithPrefix(strings.NewReader("/Videos/"+playbackNextID+"/x.ts\n"),
		"/Videos/"+playbackItemID+"/master.m3u8", playbackItemID, base); err == nil {
		t.Fatal("cross-item segment was accepted")
	}
}

func TestCastGrantDoesNotAppearInHubRequestLogPath(t *testing.T) {
	secret := "a-very-long-opaque-cast-grant"
	if path := redactedRequestPath("/v1/cast/" + secret + "/hls/segment"); strings.Contains(path, secret) || path != "/v1/cast/[grant]" {
		t.Fatalf("grant leaked through request log path: %q", path)
	}
	if path := redactedRequestPath("/v1/library/items"); path != "/v1/library/items" {
		t.Fatalf("unrelated request path changed: %q", path)
	}
}
