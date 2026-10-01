package api

import (
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

// A watched episode started again keeps Played and gains a position; its
// Continue watching card must keep the bar, or it shows a tick and no way to
// tell where you stopped. Measured on Bleach S1E2: Played, 3:12 of 24 minutes.
func TestItemToHitKeepsProgressOfARewatch(t *testing.T) {
	runtime := int64(24 * 60 * jellyfin.TicksPerSecond)
	rewatch := jellyfin.Item{ID: "e2", Name: "A Shinigami's Work", Type: "Episode", RunTimeTicks: runtime,
		UserData: &jellyfin.UserData{Played: true, PlaybackPositionTicks: 192 * jellyfin.TicksPerSecond}}
	hit := (&Server{}).itemToHit(rewatch)
	if !hit.Played || hit.Progress < 0.13 || hit.Progress > 0.14 {
		t.Fatalf("rewatch: played=%v progress=%v, want played with ~0.133", hit.Played, hit.Progress)
	}

	watched := jellyfin.Item{ID: "e1", Name: "The Day I Became a Shinigami", Type: "Episode", RunTimeTicks: runtime,
		UserData: &jellyfin.UserData{Played: true}}
	if hit := (&Server{}).itemToHit(watched); !hit.Played || hit.Progress != 0 {
		t.Fatalf("watched: played=%v progress=%v, want played with no progress", hit.Played, hit.Progress)
	}
}
