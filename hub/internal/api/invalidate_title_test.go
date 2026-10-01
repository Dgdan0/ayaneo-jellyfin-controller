package api

import (
	"context"
	"testing"

	"ayaneohub/internal/cache"
)

func TestInvalidateTitleDropsEveryViewOfIt(t *testing.T) {
	server := NewServer(libraryAPIConfig("", ""))
	ctx := context.Background()
	calls := map[string]int{}
	fetch := func(key string) {
		_, _, err := cache.Fetch(ctx, server.cache, key, cache.Discover, func(context.Context) (string, error) {
			calls[key]++
			return key, nil
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	keys := []string{"discover:trending:1", "discover:movies:2", "search:dune:1", "detail:tmdb:movie:438631", "activity"}
	for _, key := range keys {
		fetch(key)
	}

	key, err := ParseMediaKey("tmdb:movie:438631")
	if err != nil {
		t.Fatal(err)
	}
	server.invalidateTitle(key)
	for _, key := range keys {
		fetch(key)
	}

	// Discover was missed before: its card kept offering Request for half an hour.
	for _, key := range []string{"discover:trending:1", "discover:movies:2", "search:dune:1", "detail:tmdb:movie:438631"} {
		if calls[key] != 2 {
			t.Errorf("%s was not invalidated (%d fetches)", key, calls[key])
		}
	}
	if calls["activity"] != 1 {
		t.Errorf("activity has nothing to do with one title's state, but was refetched")
	}
}
