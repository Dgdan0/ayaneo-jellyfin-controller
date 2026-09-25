package api

import (
	"ayaneohub/internal/adapters/qbittorrent"
	"testing"
)

func TestDiagnoseActivity(t *testing.T) {
	tests := []struct {
		name      string
		item      ActivityItem
		partial   []Partial
		code      string
		attention bool
		action    string
	}{
		{name: "missing files", item: ActivityItem{ClientState: "missingFiles", Stage: ActStuck}, code: "missing_files", attention: true},
		{name: "stalled zero seeds", item: ActivityItem{ClientState: "stalledDL", Seeds: 0, SpeedBps: 0}, code: "waiting_peers", attention: true},
		{name: "queued is not broken", item: ActivityItem{ClientState: "queuedDL", Stage: ActQueued}, code: "queued"},
		{name: "paused can resume", item: ActivityItem{ClientStage: ActStopped, Actions: []string{"start"}}, code: "paused", action: "start"},
		{name: "read only paused", item: ActivityItem{ClientStage: ActStopped}, code: "paused"},
		{name: "import evidence takes priority", item: ActivityItem{Stage: ActStuck, ClientStage: ActSeeding, Arr: &ArrRef{Service: "sonarr", Problem: "No files eligible for import"}}, code: "import_blocked", attention: true},
		{name: "unmatched while offline is unknown", item: ActivityItem{Warnings: []string{"unmatched_download"}}, partial: []Partial{{Service: "qbittorrent"}}, code: "client_unavailable", attention: true},
		{name: "unmatched while online", item: ActivityItem{Warnings: []string{"unmatched_download"}}, code: "unmatched_download", attention: true},
		{name: "moving is not failure", item: ActivityItem{ClientState: "moving"}, code: "checking"},
		{name: "downloading with no peers is not stalled", item: ActivityItem{Stage: ActDownloading, SpeedBps: 100, Seeds: 0}, code: "downloading"},
		{name: "finished is not jellyfin availability", item: ActivityItem{Stage: ActSeeding, Progress: 1}, code: "download_complete"},
		{name: "unknown is honest", item: ActivityItem{}, code: "unknown"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			d := diagnoseActivity(tt.item, tt.partial)
			if d.Code != tt.code || d.NeedsAttention != tt.attention || d.Action != tt.action {
				t.Fatalf("got %+v", d)
			}
			if d.Title == "" || d.Explanation == "" || d.NextStep == "" {
				t.Fatalf("incomplete diagnosis: %+v", d)
			}
		})
	}
}

func TestActivityCarriesDiagnosisWithScopedResume(t *testing.T) {
	src := sources([]qbittorrent.Torrent{{Hash: "abc", State: "stoppedDL", Name: "Paused", Progress: 0.5}}, nil)
	for _, scope := range [][]string{{"read"}, {"read", "control"}} {
		out := buildActivity(src, false, scope)
		if len(out.Items) != 1 || out.Items[0].ClientState != "stoppedDL" || out.Items[0].Diagnosis == nil {
			t.Fatalf("missing diagnosis: %+v", out)
		}
		want := ""
		if len(scope) == 2 {
			want = "start"
		}
		if out.Items[0].Diagnosis.Action != want {
			t.Fatalf("scopes %v: %+v", scope, out.Items[0].Diagnosis)
		}
	}
}
