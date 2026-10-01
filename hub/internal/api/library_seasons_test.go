package api

import (
	"context"
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

func TestAFolderPosingAsASeasonIsDroppedButSpecialsStay(t *testing.T) {
	seasons := []jellyfin.Item{
		{ID: "s1", Name: "Book 1: Water", IndexNumber: 1},
		{ID: "s2", Name: "Book 2: Earth", IndexNumber: 2},
		{ID: "wrap", Name: "Avatar - The Last Airbender (2005 - 2008) [1080p]"},
		{ID: "sp", Name: "Specials"},
		{ID: "odd", Name: "Unsorted"},
	}
	episodes := map[string][]jellyfin.Item{
		"wrap": {{ParentIndexNumber: 1}, {ParentIndexNumber: 2}},
		"sp":   {{ParentIndexNumber: 0}},
		"odd":  {{ParentIndexNumber: 1}, {ParentIndexNumber: 7}},
	}
	got := withoutFolderSeasons(context.Background(), seasons, func(_ context.Context, id string) ([]jellyfin.Item, error) {
		return episodes[id], nil
	})
	var names []string
	for _, season := range got {
		names = append(names, season.Name)
	}
	want := []string{"Book 1: Water", "Book 2: Earth", "Specials", "Unsorted"}
	if len(names) != len(want) {
		t.Fatalf("seasons = %v, want %v", names, want)
	}
	for i := range want {
		if names[i] != want[i] {
			t.Fatalf("seasons = %v, want %v", names, want)
		}
	}
}
