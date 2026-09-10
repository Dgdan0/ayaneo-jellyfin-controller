package cache

import "time"

// The TTL table. One place, so "why is this stale" has one answer.
//
// The numbers come from how fast each thing can actually change, not from a
// feeling. Anything describing where a file *is* gets seconds; anything
// describing what a film *is* gets hours.
var (
	// TMDB metadata: title, overview, poster path, runtime, genres, cast.
	// A film released in 2021 will not change its runtime. This is the whole
	// reason metadata and availability are cached separately -- bundling them
	// means either re-fetching this constantly or showing stale progress.
	Metadata = Spec{Fresh: 24 * time.Hour, Stale: 48 * time.Hour, IfError: 7 * 24 * time.Hour}

	// Availability and download progress. Short, because "63% done" being ten
	// seconds old is fine and being ten minutes old is a lie.
	Availability = Spec{Fresh: 20 * time.Second, Stale: 40 * time.Second, IfError: 10 * time.Minute}

	// A search is a fresh question, but people re-run the same one while
	// deciding, and paging back and forth should not re-hit TMDB.
	Search = Spec{Fresh: 60 * time.Second, Stale: time.Hour, IfError: time.Hour}

	// Trending changes on TMDB's schedule, not ours.
	Discover = Spec{Fresh: 30 * time.Minute, Stale: 2 * time.Hour, IfError: 24 * time.Hour}

	// The request list and its counts.
	Requests = Spec{Fresh: 30 * time.Second, Stale: 2 * time.Minute, IfError: 10 * time.Minute}

	// Quality profiles and root folders. Changed by hand, rarely.
	ServiceOptions = Spec{Fresh: 10 * time.Minute, Stale: time.Hour, IfError: 24 * time.Hour}

	// One interactive search.
	//
	// Kept only long enough to grab from: the *arr caches the same result set on
	// its own side and a grab is looked up in *that* cache, so an id from a
	// search half an hour old would no longer resolve there either. Re-running
	// the search refreshes both.
	Releases = Spec{Fresh: 5 * time.Minute, Stale: 0, IfError: 0}

	// Jellyfin library pages.
	LibraryPage = Spec{Fresh: 60 * time.Second, Stale: 10 * time.Minute, IfError: time.Hour}

	// Watched state and resume positions -- these change when *you* watch
	// something, so a long TTL means the app disagrees with the TV you just
	// paused.
	UserData = Spec{Fresh: 15 * time.Second, Stale: 30 * time.Second, IfError: 5 * time.Minute}

	// Live transfer state.
	//
	// Stale is deliberately zero. A torrent list from two minutes ago is not
	// slightly out of date, it is wrong: it shows finished downloads as running
	// and misses the one that just failed. Showing nothing is better, because at
	// least nothing does not mislead.
	Downloads = Spec{Fresh: 3 * time.Second, Stale: 0, IfError: 0}

	// Service health, for the same reason.
	Health = Spec{Fresh: 10 * time.Second, Stale: 0, IfError: 0}

	// Recent service events and current service warnings. A short fresh window
	// collapses the rail badge and open-screen poll into one upstream fetch;
	// stale-if-error keeps the previous timeline visible during a brief outage.
	Notifications = Spec{Fresh: 15 * time.Second, Stale: 45 * time.Second, IfError: 10 * time.Minute}
)

// Images are handled separately and much more aggressively.
//
// A TMDB poster at a given path is immutable -- the path changes when the image
// changes -- so it can be cached effectively forever. This is the single largest
// bandwidth saving available: a poster grid is a few hundred KB of images and a
// few KB of JSON.
const ImageMaxAge = 30 * 24 * time.Hour
