package index

import (
	"sync"
	"testing"
)

func movie(id, name, tmdb string) Source {
	return Source{ItemID: id, Name: name, JellyfinType: "Movie", Tmdb: tmdb}
}

func series(id, name, tmdb, tvdb string) Source {
	return Source{ItemID: id, Name: name, JellyfinType: "Series", Tmdb: tmdb, Tvdb: tvdb}
}

func TestLookupByEachProvider(t *testing.T) {
	idx := New()
	idx.Rebuild([]Source{
		{ItemID: "a", Name: "Gran Torino", JellyfinType: "Movie",
			Tmdb: "13223", Imdb: "tt1205489"},
		series("b", "Breaking Bad", "1396", "81189"),
	})

	if e, ok := idx.ByTmdb("movie", "13223"); !ok || e.ItemID != "a" {
		t.Errorf("tmdb movie: %v %v", e, ok)
	}
	if e, ok := idx.ByImdb("movie", "tt1205489"); !ok || e.ItemID != "a" {
		t.Errorf("imdb: %v %v", e, ok)
	}
	if e, ok := idx.ByTvdb("series", "81189"); !ok || e.ItemID != "b" {
		t.Errorf("tvdb: %v %v", e, ok)
	}
}

func TestTypeIsPartOfTheKey(t *testing.T) {
	// TMDB numbers films and series in separate spaces: 1396 is Breaking Bad
	// and also a completely unrelated film. Without the type in the key one
	// would silently answer for the other.
	idx := New()
	idx.Rebuild([]Source{
		movie("film", "Some Film", "1396"),
		series("show", "Breaking Bad", "1396", "81189"),
	})
	if e, _ := idx.ByTmdb("movie", "1396"); e.ItemID != "film" {
		t.Errorf("movie lookup got %q", e.ItemID)
	}
	if e, _ := idx.ByTmdb("series", "1396"); e.ItemID != "show" {
		t.Errorf("series lookup got %q", e.ItemID)
	}
}

func TestMissesAreReportedAsMisses(t *testing.T) {
	idx := New()
	idx.Rebuild([]Source{movie("a", "Gran Torino", "13223")})
	if _, ok := idx.ByTmdb("movie", "99999"); ok {
		t.Error("unknown id should miss")
	}
	if _, ok := idx.ByTmdb("movie", ""); ok {
		t.Error("an empty id should miss rather than match something")
	}
}

func TestItemsWithoutIdsAreCountedButNotIndexed(t *testing.T) {
	// One item on this library has no provider ids at all -- a mis-scanned file
	// named after a release group. It must still count towards coverage, or the
	// number stops meaning anything.
	idx := New()
	snapshot := idx.Rebuild([]Source{
		movie("a", "Gran Torino", "13223"),
		movie("junk", "ETRG", ""),
	})
	if snapshot.Movies != 2 {
		t.Errorf("movies %d", snapshot.Movies)
	}
	if snapshot.MoviesWithTmdb != 1 {
		t.Errorf("withTmdb %d", snapshot.MoviesWithTmdb)
	}
}

func TestNonTitleTypesAreIgnored(t *testing.T) {
	// A recursive sweep returns seasons, episodes and music too, and none of
	// them belong in a title-level index.
	idx := New()
	snapshot := idx.Rebuild([]Source{
		movie("a", "Gran Torino", "13223"),
		{ItemID: "e", Name: "The Stake Out", JellyfinType: "Episode", Tmdb: "999"},
		{ItemID: "s", Name: "Season 1", JellyfinType: "Season"},
	})
	if snapshot.Movies+snapshot.Series != 1 {
		t.Errorf("counted %d titles", snapshot.Movies+snapshot.Series)
	}
	if _, ok := idx.ByTmdb("movie", "999"); ok {
		t.Error("an episode must not be indexed as a title")
	}
}

func TestRebuildReplacesRatherThanMerges(t *testing.T) {
	// An item deleted in Jellyfin has to disappear here too; a merge would keep
	// claiming the library still has it.
	idx := New()
	idx.Rebuild([]Source{movie("a", "Gone", "111"), movie("b", "Kept", "222")})
	idx.Rebuild([]Source{movie("b", "Kept", "222")})
	if _, ok := idx.ByTmdb("movie", "111"); ok {
		t.Error("removed item is still indexed")
	}
	if _, ok := idx.ByTmdb("movie", "222"); !ok {
		t.Error("kept item vanished")
	}
}

func TestReadyDistinguishesEmptyFromUnknown(t *testing.T) {
	// Before the first sweep, "not in the index" means "we do not know yet".
	// Answering "you do not have this" would be a lie.
	idx := New()
	if idx.Ready() {
		t.Error("a fresh index must not claim to be ready")
	}
	idx.Rebuild(nil)
	if !idx.Ready() {
		t.Error("an empty library is still a known answer")
	}
}

func TestCoverage(t *testing.T) {
	idx := New()
	// The real shape of this install: 179 movies, 178 with a Tmdb id.
	sources := make([]Source, 0, 250)
	for n := 0; n < 178; n++ {
		sources = append(sources, movie("m", "film", "t"))
	}
	sources = append(sources, movie("junk", "ETRG", ""))
	for n := 0; n < 70; n++ {
		sources = append(sources, series("s", "show", "t", "v"))
	}
	sources = append(sources, series("s71", "show", "", ""))

	snapshot := idx.Rebuild(sources)
	if snapshot.Movies != 179 || snapshot.Series != 71 {
		t.Fatalf("counts %+v", snapshot)
	}
	coverage := snapshot.TmdbCoverage()
	if coverage < 0.98 || coverage > 0.995 {
		t.Errorf("coverage %.3f, expected about 0.992", coverage)
	}
}

func TestCoverageOfAnEmptyLibraryIsZeroNotNaN(t *testing.T) {
	if got := (Snapshot{}).TmdbCoverage(); got != 0 {
		t.Errorf("got %v", got)
	}
}

func TestConcurrentReadsDuringRebuild(t *testing.T) {
	// The index is rebuilt on a timer while requests read it.
	idx := New()
	idx.Rebuild([]Source{movie("a", "Gran Torino", "13223")})

	var wg sync.WaitGroup
	for n := 0; n < 8; n++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for k := 0; k < 200; k++ {
				idx.ByTmdb("movie", "13223")
				idx.Snapshot()
				idx.Ready()
			}
		}()
	}
	for n := 0; n < 4; n++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for k := 0; k < 50; k++ {
				idx.Rebuild([]Source{movie("a", "Gran Torino", "13223")})
			}
		}()
	}
	wg.Wait()
}

func TestIdsAreTrimmed(t *testing.T) {
	idx := New()
	idx.Rebuild([]Source{movie("a", "Gran Torino", " 13223 ")})
	if _, ok := idx.ByTmdb("movie", "13223"); !ok {
		t.Error("a padded id should still match")
	}
}
