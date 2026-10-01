package api

import (
	"context"
	"strings"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/cache"
)

// LibraryRef names the Jellyfin library an item is in.
type LibraryRef struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// libraryOf is the item's library: the nearest collection folder among its
// ancestors. Libraries do not move, so the answer is cached for a day, and a
// failure is simply no answer -- the caller shows the item without one.
func (s *Server) libraryOf(ctx context.Context, client *jellyfin.Client, itemID string) *LibraryRef {
	ref, _, err := cache.Fetch(ctx, s.cache, "library:of:"+itemID, cache.Metadata,
		func(ctx context.Context) (*LibraryRef, error) {
			ancestors, err := client.Ancestors(ctx, itemID)
			if err != nil {
				return nil, err
			}
			return collectionFolder(ancestors), nil
		})
	if err != nil || ref == nil || ref.ID == "" {
		return nil
	}
	return ref
}

// collectionFolder picks the library out of an ancestor list, nearest first.
func collectionFolder(ancestors []jellyfin.Item) *LibraryRef {
	for _, ancestor := range ancestors {
		if strings.EqualFold(ancestor.Type, "CollectionFolder") || (ancestor.CollectionType != "" && !strings.EqualFold(ancestor.Type, "UserRootFolder")) {
			return &LibraryRef{ID: ancestor.ID, Name: ancestor.Name}
		}
	}
	return &LibraryRef{}
}
