package api

import (
	"path/filepath"
	"testing"
)

func TestReadingAcquisitionStoreIsDurableAndIdempotent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "reading-acquisitions.json")
	preview := ReadingSeriesPreview{
		SeriesID: "OL100L", Name: "Red Rising Saga", AuthorID: "OL1A", Author: "Pierce Brown",
		Books: []ReadingSeriesPreviewBook{
			{ID: "OL1W", Title: "Red Rising", Author: "Pierce Brown", ISBN: "9780345539786", Position: 1},
			{ID: "OL2W", Title: "Golden Son", Author: "Pierce Brown", ISBN: "9780345539816", Position: 2},
		},
	}
	store := newReadingAcquisitionStore(path)
	first, err := store.begin("reading:key", preview, preview.Books, 7, "all")
	if err != nil {
		t.Fatal(err)
	}
	second, err := store.begin("reading:key", preview, preview.Books, 7, "all")
	if err != nil {
		t.Fatal(err)
	}
	if first.ID != second.ID || len(first.Books) != 2 {
		t.Fatalf("first=%+v second=%+v", first, second)
	}
	if err := store.setParent(first.ID, 90); err != nil {
		t.Fatal(err)
	}
	if err := store.setBook(first.ID, "OL1W", 44, "accepted", ""); err != nil {
		t.Fatal(err)
	}

	reloaded := newReadingAcquisitionStore(path)
	got, ok := reloaded.get(first.ID)
	if !ok || got.ParentSeriesID != 90 || got.Books[0].BookKeeprrSeriesID != 44 || got.Books[0].State != "accepted" {
		t.Fatalf("reloaded = %+v, found=%v", got, ok)
	}
}

func TestReadingAcquisitionManifestMatchesImportedBookMetadata(t *testing.T) {
	store := newReadingAcquisitionStore(filepath.Join(t.TempDir(), "reading-acquisitions.json"))
	preview := ReadingSeriesPreview{
		SeriesID: "OL100L", Name: "Red Rising Saga", Author: "Pierce Brown",
		Books: []ReadingSeriesPreviewBook{{
			ID: "OL2W", Title: "Golden Son", Author: "Pierce Brown", ISBN: "9780345539816", Position: 2,
		}},
	}
	manifest, err := store.begin("reading:key", preview, preview.Books, 7, "all")
	if err != nil {
		t.Fatal(err)
	}
	if err := store.setBook(manifest.ID, "OL2W", 45, "accepted", ""); err != nil {
		t.Fatal(err)
	}

	match, ok := store.matchBook("A different edition title", []string{"P. Brown"}, map[string]string{"isbn": "978-0-345-53981-6"})
	if !ok || match.SeriesName != "Red Rising Saga" || match.Position != 2 {
		t.Fatalf("match = %+v, found=%v", match, ok)
	}
}

func TestReadingAcquisitionManifestKeepsFullSeriesRosterForLibraryAvailability(t *testing.T) {
	store := newReadingAcquisitionStore(filepath.Join(t.TempDir(), "reading-acquisitions.json"))
	fullRoster := []ReadingSeriesPreviewBook{
		{ID: "OL1W", Title: "Red Rising", Author: "Pierce Brown", Position: 1},
		{ID: "OL2W", Title: "Golden Son", Author: "Pierce Brown", Position: 2},
		{ID: "OL3W", Title: "Morning Star", Author: "Pierce Brown", Position: 3},
	}
	preview := ReadingSeriesPreview{
		SeriesID: "OL100L", Name: "Red Rising Saga", Author: "Pierce Brown",
		Description: "A long series description.", Books: fullRoster[:1], FullBooks: fullRoster,
	}
	manifest, err := store.begin("reading:key", preview, preview.Books, 7, "all")
	if err != nil {
		t.Fatal(err)
	}
	if len(manifest.Books) != 1 || len(manifest.Roster) != 3 {
		t.Fatalf("manifest selected=%d roster=%d", len(manifest.Books), len(manifest.Roster))
	}
	if manifest.Roster[1].Title != "Golden Son" || manifest.Description == "" {
		t.Fatalf("manifest = %+v", manifest)
	}
}
