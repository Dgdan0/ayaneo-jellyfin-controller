package api

import (
	"ayaneohub/internal/adapters/jellyfin"
	"testing"
)

func TestMonitorDoesNotExposeAnotherProfilesPlayback(t *testing.T) {
	values := []jellyfin.Session{{UserID: "first", NowPlayingItem: &jellyfin.Item{Name: "Visible"}}, {UserID: "second", NowPlayingItem: &jellyfin.Item{Name: "Private"}}, {UserID: "first"}}
	result := monitorSessions(values, "first")
	if len(result) != 1 || result[0].Title != "Visible" {
		t.Fatalf("%+v", result)
	}
	if len(monitorSessions(values, "")) != 0 {
		t.Fatal("missing profile must not include sessions")
	}
}
