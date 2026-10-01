package api

import (
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

func TestJellyfinImageIsEmptyWithoutATag(t *testing.T) {
	if got := jellyfinImage("abc", "Primary", "t1"); got != "/v1/img/jf/abc/Primary?tag=t1" {
		t.Fatalf("jellyfinImage = %q", got)
	}
	for _, got := range []string{jellyfinImage("abc", "Primary", ""), jellyfinImage("", "Primary", "t1")} {
		if got != "" {
			t.Fatalf("an image with no tag or no item should be empty, got %q", got)
		}
	}
	// The Library used to build ".../Backdrop?tag=" for an empty first tag.
	if got := backdropImage(jellyfin.Item{ID: "abc", BackdropImageTags: []string{""}}); got != "" {
		t.Fatalf("backdropImage with an empty tag = %q", got)
	}
}
