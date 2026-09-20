package reading

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type layoutFixture struct {
	SchemaVersion int `json:"schemaVersion"`
	Assets        []struct {
		Name  string `json:"name"`
		Kind  string `json:"kind"`
		Path  string `json:"path"`
		Valid bool   `json:"valid"`
	} `json:"assets"`
}

func TestCanonicalLayoutFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("testdata", "canonical-layout.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture layoutFixture
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.SchemaVersion != 1 {
		t.Fatalf("schema version = %d, want 1", fixture.SchemaVersion)
	}
	for _, asset := range fixture.Assets {
		t.Run(asset.Name, func(t *testing.T) {
			kind, kindErr := ParseMediaKind(asset.Kind)
			_, pathErr := NormalizeAssetPath(kind, asset.Path)
			valid := kindErr == nil && pathErr == nil
			if valid != asset.Valid {
				t.Fatalf("kind error = %v, path error = %v; valid = %v, want %v", kindErr, pathErr, valid, asset.Valid)
			}
		})
	}
}

func TestCanonicalRootNamesStayJellyfinFriendly(t *testing.T) {
	want := map[MediaKind]string{
		MediaBook:      "Books",
		MediaAudiobook: "Audiobooks",
		MediaComic:     "Comics",
		MediaManga:     "Manga",
	}
	for kind, root := range want {
		if got := CanonicalRoot(kind); got != root {
			t.Errorf("CanonicalRoot(%q) = %q, want %q", kind, got, root)
		}
	}
}

func TestNormalizeAssetPathUsesPortableSeparators(t *testing.T) {
	got, err := NormalizeAssetPath(MediaBook, `Author\Series\01 - Title\Title.epub`)
	if err != nil {
		t.Fatal(err)
	}
	if got != "Author/Series/01 - Title/Title.epub" {
		t.Fatalf("normalized path = %q", got)
	}
}

func TestNormalizeAssetPathRejectsTraversalEvenWhenCleanWouldHideIt(t *testing.T) {
	for _, value := range []string{
		"Author/../Other/Title.epub",
		"Author/.hidden/../Title/Title.epub",
		"Author/%2e%2e/Title.epub",
	} {
		if _, err := NormalizeAssetPath(MediaBook, value); err == nil {
			t.Errorf("accepted unsafe path %q", value)
		}
	}
}

func TestSidecarsAreRestrictedToTheirMediaType(t *testing.T) {
	if _, err := NormalizeAssetPath(MediaComic, "Series/ComicInfo.xml"); err != nil {
		t.Fatalf("comic sidecar rejected: %v", err)
	}
	if _, err := NormalizeAssetPath(MediaBook, "Author/Title/ComicInfo.xml"); err == nil {
		t.Fatal("ComicInfo.xml accepted for a prose book")
	}
	if _, err := NormalizeAssetPath(MediaBook, "Author/Title/metadata.opf"); err != nil {
		t.Fatalf("book OPF rejected: %v", err)
	}
}
