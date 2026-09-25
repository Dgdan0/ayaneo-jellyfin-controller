package qbittorrent

import (
	"ayaneohub/internal/config"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBandwidthUsesBytesAndExplicitModeAndReadsBack(t *testing.T) {
	prefs := map[string]any{"dl_limit": 0, "up_limit": 0, "alt_dl_limit": 10240, "alt_up_limit": 10240, "queueing_enabled": false}
	mode := "0"
	toggled := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v2/app/version":
			w.Write([]byte("v5.0.4"))
		case "/api/v2/app/preferences":
			json.NewEncoder(w).Encode(prefs)
		case "/api/v2/transfer/speedLimitsMode":
			w.Write([]byte(mode))
		case "/api/v2/transfer/setSpeedLimitsMode":
			r.ParseForm()
			mode = r.Form.Get("mode")
		case "/api/v2/transfer/toggleSpeedLimitsMode":
			toggled = true
		case "/api/v2/app/setPreferences":
			r.ParseForm()
			var update map[string]any
			json.Unmarshal([]byte(r.Form.Get("json")), &update)
			for k, v := range update {
				prefs[k] = v
			}
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	client, _ := New(config.ServiceConfig{BaseURL: server.URL})
	state, err := client.Bandwidth(context.Background())
	if err != nil || state.AlternativeDownloadBps != 10240 || state.Mode != "normal" {
		t.Fatalf("%+v %v", state, err)
	}
	down, up := int64(5*1024*1024), int64(1024*1024)
	state, err = client.SetBandwidth(context.Background(), BandwidthChange{Mode: "alternative", LimitsFor: "alternative", DownloadBps: &down, UploadBps: &up})
	if err != nil || state.Mode != "alternative" || state.AlternativeDownloadBps != down || state.DownloadBps != 0 || toggled {
		t.Fatalf("%+v %v toggled=%v", state, err, toggled)
	}
}

func TestBandwidthValidation(t *testing.T) {
	neg, huge := int64(-1), int64(1<<31)
	for _, change := range []BandwidthChange{{}, {Mode: "bad"}, {DownloadBps: &neg, LimitsFor: "normal"}, {UploadBps: &huge, LimitsFor: "normal"}, {DownloadBps: &huge}, {LimitsFor: "normal"}} {
		if change.Validate() == nil {
			t.Fatalf("accepted %+v", change)
		}
	}
}

func TestPriorityRejectsDisabledQueueWithoutMutation(t *testing.T) {
	writes := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			writes++
			t.Error("disabled queue must not mutate")
		}
		switch r.URL.Path {
		case "/api/v2/app/preferences":
			w.Write([]byte(`{"queueing_enabled":false}`))
		case "/api/v2/app/version":
			w.Write([]byte("v5.0.4"))
		case "/api/v2/transfer/speedLimitsMode":
			w.Write([]byte("0"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	client, _ := New(config.ServiceConfig{BaseURL: upstream.URL})
	if _, err := client.ChangePriority(context.Background(), "abc", "priority_up"); err == nil || writes != 0 {
		t.Fatalf("err=%v writes=%d", err, writes)
	}
}
