package index

import (
	"strings"
	"sync"
)

// The Jellyfin provider-id index: "does the library have tmdb:movie:27205?".
//
// It exists because **Jellyfin has no provider-id query**. The public API
// offers `hasTmdbId` and friends as *booleans* only; there is no way to ask
// which item carries a given id. So the hub sweeps every item once and keeps
// the mapping itself. This is the weakest link in the whole cross-service join
// and the plan treated it as the riskiest piece.
//
// **It is a map, not a database, and that is a measured decision.** The plan
// called for SQLite so a large library would not have to be re-swept and so a
// restart would not blind the app for fifteen minutes. On this install the
// whole library is 250 items and a full sweep takes 362ms, which makes both
// concerns disappear -- and keeps the hub's only external dependency
// `gopkg.in/yaml.v3`. If a library ever grows to where 362ms becomes 30s, the
// answer is paging plus persistence, and this type is the seam for it.

// Entry is one library item, reduced to what a join needs.
type Entry struct {
	ItemID string
	Name   string
	Year   int
	// "movie" or "series", already translated out of Jellyfin's vocabulary.
	Type string
}

// Snapshot is what a sweep produced, and the numbers that say how much to
// trust it.
type Snapshot struct {
	Movies int
	Series int
	// How many of each carry a TMDB id. Reported by `hubctl doctor`, because a
	// library at 85% coverage will look broken through no fault of the hub and
	// the user should learn that up front rather than concluding the app lies.
	MoviesWithTmdb int
	SeriesWithTmdb int
	SeriesWithTvdb int
}

// TmdbCoverage is the fraction of items carrying a TMDB id, 0..1.
//
// The single most useful number about an installation: everything the app says
// about "do I have this?" flows through it.
func (s Snapshot) TmdbCoverage() float64 {
	total := s.Movies + s.Series
	if total == 0 {
		return 0
	}
	return float64(s.MoviesWithTmdb+s.SeriesWithTmdb) / float64(total)
}

// Index is safe for concurrent use: it is rebuilt on a timer while requests
// read it.
type Index struct {
	mu       sync.RWMutex
	byTmdb   map[string]Entry
	byTvdb   map[string]Entry
	byImdb   map[string]Entry
	snapshot Snapshot
	built    bool
}

func New() *Index {
	return &Index{
		byTmdb: map[string]Entry{},
		byTvdb: map[string]Entry{},
		byImdb: map[string]Entry{},
	}
}

// Source is one item as the sweep saw it. Taking an interface-free struct keeps
// this package free of any dependency on the Jellyfin adapter, so it can be
// tested with three lines of literal data.
type Source struct {
	ItemID string
	Name   string
	Year   int
	// Jellyfin's own word: "Movie" or "Series".
	JellyfinType string
	Tmdb         string
	Tvdb         string
	Imdb         string
}

// Rebuild replaces the whole index atomically.
//
// Replaced rather than merged: an item deleted from Jellyfin has to disappear
// from the index too, and a merge would keep claiming the library still has it.
func (i *Index) Rebuild(items []Source) Snapshot {
	tmdb := make(map[string]Entry, len(items))
	tvdb := make(map[string]Entry, len(items))
	imdb := make(map[string]Entry, len(items))
	snapshot := Snapshot{}

	for _, item := range items {
		mediaType := mediaTypeOf(item.JellyfinType)
		if mediaType == "" {
			continue
		}
		entry := Entry{ItemID: item.ItemID, Name: item.Name, Year: item.Year, Type: mediaType}
		if mediaType == "movie" {
			snapshot.Movies++
			if item.Tmdb != "" {
				snapshot.MoviesWithTmdb++
			}
		} else {
			snapshot.Series++
			if item.Tmdb != "" {
				snapshot.SeriesWithTmdb++
			}
			if item.Tvdb != "" {
				snapshot.SeriesWithTvdb++
			}
		}
		// Keyed by type as well as id, because TMDB numbers movies and series in
		// separate spaces -- tmdb 1396 is Breaking Bad *and* a different film.
		if item.Tmdb != "" {
			tmdb[key(mediaType, item.Tmdb)] = entry
		}
		if item.Tvdb != "" {
			tvdb[key(mediaType, item.Tvdb)] = entry
		}
		if item.Imdb != "" {
			imdb[key(mediaType, item.Imdb)] = entry
		}
	}

	i.mu.Lock()
	i.byTmdb, i.byTvdb, i.byImdb = tmdb, tvdb, imdb
	i.snapshot = snapshot
	i.built = true
	i.mu.Unlock()
	return snapshot
}

// ByTmdb looks up "is tmdb:movie:27205 in the library?".
func (i *Index) ByTmdb(mediaType, id string) (Entry, bool) {
	return i.lookup(i.byTmdb, mediaType, id)
}

func (i *Index) ByTvdb(mediaType, id string) (Entry, bool) {
	return i.lookup(i.byTvdb, mediaType, id)
}

func (i *Index) ByImdb(mediaType, id string) (Entry, bool) {
	return i.lookup(i.byImdb, mediaType, id)
}

// Ready reports whether a sweep has completed.
//
// The distinction matters: before the first sweep, "not in the index" means
// "we do not know yet", and answering "you do not have this" would be a lie.
func (i *Index) Ready() bool {
	i.mu.RLock()
	defer i.mu.RUnlock()
	return i.built
}

func (i *Index) Snapshot() Snapshot {
	i.mu.RLock()
	defer i.mu.RUnlock()
	return i.snapshot
}

func (i *Index) lookup(from map[string]Entry, mediaType, id string) (Entry, bool) {
	if id == "" {
		return Entry{}, false
	}
	i.mu.RLock()
	defer i.mu.RUnlock()
	entry, ok := from[key(mediaType, id)]
	return entry, ok
}

func key(mediaType, id string) string { return mediaType + ":" + strings.TrimSpace(id) }

// mediaTypeOf translates Jellyfin's vocabulary into the hub's.
//
// @return "" for anything that is not a movie or a series -- seasons, episodes
// and music all come back from a recursive sweep and none of them belong in a
// title-level index.
func mediaTypeOf(jellyfinType string) string {
	switch jellyfinType {
	case "Movie":
		return "movie"
	case "Series":
		return "series"
	}
	return ""
}
