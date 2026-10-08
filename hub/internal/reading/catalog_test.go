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

func TestCatalogStoreUnbindDropsOneSourceAndKeepsTheWork(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-catalog.json")
	store := NewCatalogStore(path)
	keys := []string{"isbn:9780345539786", "metadata:a game of thrones|george r r martin||0"}
	id, err := store.Bind(WorkBinding{Source: "storyteller", SourceID: "111", IdentityKeys: keys})
	if err != nil {
		t.Fatal(err)
	}
	if again, _ := store.Bind(WorkBinding{Source: "storyteller", SourceID: "222", IdentityKeys: keys}); again != id {
		t.Fatalf("second edition bound to %q, want %q", again, id)
	}

	removed, err := store.Unbind("Storyteller", " 111 ")
	if err != nil || !removed {
		t.Fatalf("Unbind() = %v, %v", removed, err)
	}
	work, ok := store.Resolve(id)
	if !ok || len(work.Sources) != 1 || work.Sources[0].SourceID != "222" || len(work.IdentityKeys) != 2 {
		t.Fatalf("work after unbind = %+v, %v", work, ok)
	}
	if _, bound := store.WorkIDFor("storyteller", "111"); bound {
		t.Fatal("the unbound record still resolves to a work")
	}

	// What reaches the disk is the same: a restart does not bring the dead record back.
	reopened := NewCatalogStore(path)
	if again, _ := reopened.Resolve(id); len(again.Sources) != 1 || again.Sources[0].SourceID != "222" {
		t.Fatalf("reopened work = %+v", again)
	}
}

func TestCatalogStoreUnbindOfTheLastSourceKeepsTheWorkIDForARecordThatReturns(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-catalog.json")
	store := NewCatalogStore(path)
	keys := []string{"isbn:9780345539786"}
	id, _ := store.Bind(WorkBinding{Source: "storyteller", SourceID: "111", IdentityKeys: keys})
	if removed, err := store.Unbind("storyteller", "111"); err != nil || !removed {
		t.Fatalf("Unbind() = %v, %v", removed, err)
	}
	work, ok := NewCatalogStore(path).Resolve(id)
	if !ok || len(work.Sources) != 0 {
		t.Fatalf("emptied work = %+v, %v (links saved in an app must keep resolving)", work, ok)
	}
	if saved, _ := os.ReadFile(path); !strings.Contains(string(saved), `"sources": []`) {
		t.Fatalf("an emptied work must be saved with an empty list, not null: %s", saved)
	}
	// The book is imported again under a new id and rejoins the same work by its strong identity.
	if back, _ := store.Bind(WorkBinding{Source: "storyteller", SourceID: "333", IdentityKeys: keys}); back != id {
		t.Fatalf("re-imported record bound to %q, want %q", back, id)
	}
}

func TestCatalogStoreUnbindIgnoresUnknownRecordsAndRefusesACorruptCatalog(t *testing.T) {
	store := NewCatalogStore("")
	if removed, err := store.Unbind("storyteller", "404"); err != nil || removed {
		t.Fatalf("Unbind(unknown) = %v, %v", removed, err)
	}
	if _, err := store.Unbind("", "1"); err == nil {
		t.Fatal("a missing source was accepted")
	}

	path := filepath.Join(t.TempDir(), "reading-catalog.json")
	original := []byte(`{"version":1,"works":`)
	if err := os.WriteFile(path, original, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewCatalogStore(path).Unbind("storyteller", "1"); err == nil {
		t.Fatal("a corrupt catalog was written to")
	}
	if got, _ := os.ReadFile(path); string(got) != string(original) {
		t.Fatalf("corrupt state was overwritten: %q", got)
	}
}
