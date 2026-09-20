package reading

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCatalogStoreKeepsWorkIDAcrossSourcesAndRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-catalog.json")
	store := NewCatalogStore(path)

	storyID, err := store.Bind(WorkBinding{
		Source: "storyteller", SourceID: "book-7",
		IdentityKeys: []string{"isbn:9780345539786", "metadata:red rising|pierce brown|red rising|1"},
	})
	if err != nil {
		t.Fatal(err)
	}
	kavitaID, err := store.Bind(WorkBinding{
		Source: "kavita", SourceID: "series-42",
		IdentityKeys: []string{"isbn:9780345539786"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if storyID == "" || storyID != kavitaID {
		t.Fatalf("shared identity produced %q and %q", storyID, kavitaID)
	}

	reopened := NewCatalogStore(path)
	work, ok := reopened.Resolve(storyID)
	if !ok || len(work.Sources) != 2 {
		t.Fatalf("reopened Resolve(%q) = %+v, %v", storyID, work, ok)
	}
	if got, ok := reopened.WorkIDFor("storyteller", "book-7"); !ok || got != storyID {
		t.Fatalf("WorkIDFor() = %q, %v", got, ok)
	}
}

func TestCatalogStoreDoesNotMergeWeakTitleOnlyIdentity(t *testing.T) {
	store := NewCatalogStore("")
	first, err := store.Bind(WorkBinding{
		Source: "kavita", SourceID: "1", IdentityKeys: []string{"title:home"},
	})
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.Bind(WorkBinding{
		Source: "storyteller", SourceID: "2", IdentityKeys: []string{"title:home"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatalf("weak title-only identity merged unrelated works as %q", first)
	}
}

func TestCatalogStoreDoesNotOverwriteCorruptState(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-catalog.json")
	original := []byte(`{"version":1,"works":`)
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}

	store := NewCatalogStore(path)
	_, err := store.Bind(WorkBinding{Source: "kavita", SourceID: "1", IdentityKeys: []string{"isbn:9780345539786"}})
	if err == nil || !strings.Contains(err.Error(), "loading") {
		t.Fatalf("Bind error = %v, want catalog loading error", err)
	}
	got, readErr := os.ReadFile(path)
	if readErr != nil {
		t.Fatal(readErr)
	}
	if string(got) != string(original) {
		t.Fatalf("corrupt state was overwritten: %q", got)
	}
}
