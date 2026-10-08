package api

import (
	"os"
	"path/filepath"
	"testing"

	"ayaneohub/internal/adapters/storyteller"
)

// What the hub keeps of books started over (#60): a stamp for each work and for each
// Storyteller book and Kavita series that made it up, so a place written before the
// stamp is gone and a write made from one is refused.

func TestResetStoreRemembersWhatWasStartedOverAcrossARestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-resets.json")
	store := newReadingResetStore(path)
	if store.any() || store.work("rw_a") != 0 || store.source("storyteller", "12") != 0 {
		t.Fatal("a new store knows of nothing")
	}
	if err := store.record("rw_a", 1_800_000_060_000, []string{"storyteller:12", "storyteller:13", "kavita:9"}); err != nil {
		t.Fatal(err)
	}
	if info, err := os.Stat(path); err != nil || info.Mode().Perm()&0o077 != 0 && !isWindowsLike() {
		t.Fatalf("the file is private: %v %v", info, err)
	}
	again := newReadingResetStore(path)
	if again.work("rw_a") != 1_800_000_060_000 || again.source("storyteller", "13") != 1_800_000_060_000 || again.source("kavita", "9") != 1_800_000_060_000 || !again.any() {
		t.Fatalf("after a restart: work %d, 13 %d", again.work("rw_a"), again.source("storyteller", "13"))
	}
	if again.work("rw_b") != 0 || again.source("storyteller", "14") != 0 {
		t.Fatal("another work was not started over")
	}
}

func TestResetStoreNeverMovesBackwards(t *testing.T) {
	store := newReadingResetStore("")
	if err := store.record("rw_a", 500, []string{"storyteller:12"}); err != nil {
		t.Fatal(err)
	}
	// A slower writer with an older stamp changes nothing: a device that has seen 500 must not be taken back.
	if err := store.record("rw_a", 300, []string{"storyteller:12"}); err != nil {
		t.Fatal(err)
	}
	if store.work("rw_a") != 500 || store.source("storyteller", "12") != 500 {
		t.Fatalf("stamps moved back: work %d, book %d", store.work("rw_a"), store.source("storyteller", "12"))
	}
	if err := store.record("rw_a", 900, []string{"storyteller:12"}); err != nil || store.work("rw_a") != 900 {
		t.Fatalf("a later start over is later: %d, %v", store.work("rw_a"), err)
	}
}

func TestAPlaceIsGoneWhenItIsOlderThanTheStamp(t *testing.T) {
	store := newReadingResetStore("")
	if store.gone("storyteller", "12", 1) {
		t.Fatal("with nothing started over no place is gone")
	}
	_ = store.record("rw_a", 1000, []string{"storyteller:12"})
	for _, test := range []struct {
		stamp int64
		gone  bool
	}{{0, true}, {999, true}, {1000, false}, {1001, false}} {
		if got := store.gone("storyteller", "12", test.stamp); got != test.gone {
			t.Errorf("a place stamped %d: gone = %v, want %v", test.stamp, got, test.gone)
		}
	}
	if store.gone("storyteller", "13", 1) {
		t.Error("another book's place is not gone")
	}
}

func TestResetStoreKeepsAFileItCannotReadAndRefusesToWriteOverIt(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-resets.json")
	if err := os.WriteFile(path, []byte(`{"version":9,"works":{}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	store := newReadingResetStore(path)
	if err := store.record("rw_a", 10, []string{"storyteller:12"}); err == nil {
		t.Fatal("a file of another version must not be written over")
	}
	if body, _ := os.ReadFile(path); string(body) != `{"version":9,"works":{}}` {
		t.Fatalf("the file was changed: %s", body)
	}
	// Reading with nothing known brings places back rather than hiding every book.
	if store.gone("storyteller", "12", 1) {
		t.Fatal("an unreadable file hides nothing")
	}
}

func TestResetStoreSavesNothingWhenItCannotWrite(t *testing.T) {
	dir := t.TempDir()
	blocker := filepath.Join(dir, "file")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	// The directory of the file is a file: the write fails, and what was held stays what it was.
	store := newReadingResetStore(filepath.Join(blocker, "reading-resets.json"))
	if err := store.record("rw_a", 10, []string{"storyteller:12"}); err == nil {
		t.Fatal("a write that cannot be kept must say so")
	}
	if store.work("rw_a") != 0 || store.any() {
		t.Fatal("a reset that was not kept is not held")
	}
}

// The lists and records the hub reads from Storyteller carry the place; a place started over
// is taken off a copy, never off what the cache holds, so a reset does not need the cache to
// be emptied for it to take.
func TestAPlaceStartedOverIsHiddenFromTheShelvesWithoutChangingTheCachedList(t *testing.T) {
	server := NewServer(readingCatalogConfig("http://127.0.0.1:1", filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"}))
	books := []storyteller.Book{
		{ID: 1, Position: &storyteller.Position{Timestamp: 100}},
		{ID: 2, Position: &storyteller.Position{Timestamp: 300}},
		{ID: 3},
	}
	if got := server.hideResetPlaces(books); &got[0] != &books[0] {
		t.Fatal("with nothing started over the list is passed on as it is")
	}
	_ = server.readingResets.record("rw_a", 200, []string{"storyteller:1", "storyteller:2", "storyteller:3"})
	got := server.hideResetPlaces(books)
	if got[0].Position != nil || got[1].Position == nil || got[2].Position != nil {
		t.Fatalf("places after the reset: %v %v %v", got[0].Position, got[1].Position, got[2].Position)
	}
	if books[0].Position == nil {
		t.Fatal("the cached list was changed")
	}
	record := storyteller.Book{ID: 1, Position: &storyteller.Position{Timestamp: 100}}
	if shown := server.hideResetPlace(&record); shown.Position != nil || record.Position == nil {
		t.Fatalf("a record: shown %v, held %v", shown.Position, record.Position)
	}
}

func isWindowsLike() bool { return filepath.Separator == '\\' }
