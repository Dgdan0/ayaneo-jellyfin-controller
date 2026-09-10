package api

import (
	"fmt"

	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/cache"
)

func cacheInfoFrom(m cache.Meta) CacheInfo {
	return CacheInfo{
		Hit:        m.Hit,
		AgeSeconds: int(m.Age.Seconds()),
		Stale:      m.Stale,
		Degraded:   m.FromError,
	}
}

// Availability is the one word the app puts on a card.
//
// Deliberately a string rather than Jellyseerr's integer: the app should never
// learn that 5 means available, and these names survive Jellyseerr renumbering
// its enum, which it has already done once.
const (
	AvailNotInLibrary       = "not_in_library"
	AvailRequested          = "requested"
	AvailProcessing         = "processing"
	AvailDownloading        = "downloading"
	AvailPartiallyAvailable = "partially_available"
	AvailAvailable          = "available"
	AvailBlocked            = "blocked"
	AvailDeleted            = "deleted"
)

// MediaRef is the atom of the API: enough to render a card and to ask for more.
type MediaRef struct {
	Key   string  `json:"key"`
	Type  string  `json:"type"` // movie | series
	Title string  `json:"title"`
	Year  int     `json:"year,omitempty"`
	IDs   MediaID `json:"ids"`
	// Hub-relative, so the handheld never learns a TMDB path or an API key.
	Poster   string `json:"poster,omitempty"`
	Backdrop string `json:"backdrop,omitempty"`
}

type MediaID struct {
	Tmdb int    `json:"tmdb,omitempty"`
	Tvdb int    `json:"tvdb,omitempty"`
	Imdb string `json:"imdb,omitempty"`
}

// SearchHit is one row of a search or discover response.
type SearchHit struct {
	Media        MediaRef `json:"media"`
	Subtitle     string   `json:"subtitle"`
	Overview     string   `json:"overview,omitempty"`
	Availability string   `json:"availability"`
	Rating       float64  `json:"rating,omitempty"`
	// Present when the title is already in the library.
	JellyfinItemID string `json:"jellyfinItemId,omitempty"`
	Played         bool   `json:"played"`
	Favorite       bool   `json:"favorite"`
	UnplayedCount  int    `json:"unplayedCount,omitempty"`
	// Populated while something is actually transferring.
	Progress  float64 `json:"progress,omitempty"`
	ETA       string  `json:"eta,omitempty"`
	RequestID int     `json:"requestId,omitempty"`
	// Computed server-side from availability and the caller's scopes, so the app
	// renders buttons from data rather than reimplementing the rules.
	Actions []string `json:"actions"`
}

// CacheInfo tells the app where a response came from.
//
// Surfaced rather than hidden because the app is expected to say "showing
// results from 4 minutes ago" when it is looking at something stale. Being
// explicit about staleness is the difference between a useful offline mode and a
// confusing one.
type CacheInfo struct {
	Hit        bool `json:"hit"`
	AgeSeconds int  `json:"ageSeconds"`
	Stale      bool `json:"stale"`
	// True when this was served only because the upstream is failing.
	Degraded bool `json:"degraded,omitempty"`
}

type SearchResponse struct {
	Query        string      `json:"query"`
	Page         int         `json:"page"`
	TotalPages   int         `json:"totalPages"`
	TotalResults int         `json:"totalResults"`
	Results      []SearchHit `json:"results"`
	Partial      []Partial   `json:"partial"`
	Cache        CacheInfo   `json:"cache"`
}

// mediaKey is the canonical identity used everywhere in this API.
//
// TMDB is the spine because Jellyseerr, Radarr and Jellyfin's default metadata
// provider all speak it. Series carry their TVDB id too where it is known, since
// Sonarr is TVDB-native.
func mediaKey(mediaType string, tmdbID int) string {
	return fmt.Sprintf("tmdb:%s:%d", normaliseType(mediaType), tmdbID)
}

func tmdbImage(size, path string) string {
	if path == "" {
		return ""
	}
	return imagePrefix + "/" + size + path
}

// normaliseType maps Jellyseerr's "tv" onto the word the rest of the API uses.
func normaliseType(mediaType string) string {
	if mediaType == "tv" {
		return "series"
	}
	return mediaType
}

// availabilityFor turns Jellyseerr's numeric status into the app's vocabulary.
//
// downloadStatus is checked before the status code, because a title Jellyseerr
// still calls "processing" while bytes are actually moving is more usefully
// described as downloading -- that is the difference between "it is stuck" and
// "it is nearly here", which is the whole question the app exists to answer.
func availabilityFor(info *jellyseerr.MediaInfo) string {
	if info == nil {
		return AvailNotInLibrary
	}
	if len(info.DownloadStatus) > 0 {
		return AvailDownloading
	}
	switch info.Status {
	case jellyseerr.StatusAvailable:
		return AvailAvailable
	case jellyseerr.StatusPartiallyAvailable:
		return AvailPartiallyAvailable
	case jellyseerr.StatusProcessing:
		return AvailProcessing
	case jellyseerr.StatusPending:
		return AvailRequested
	case jellyseerr.StatusBlocklisted:
		return AvailBlocked
	case jellyseerr.StatusDeleted:
		return AvailDeleted
	}
	return AvailNotInLibrary
}

// actionsFor is what the app is allowed to offer for this row.
func actionsFor(availability string, scopes []string) []string {
	has := func(scope string) bool {
		for _, s := range scopes {
			if s == scope {
				return true
			}
		}
		return false
	}

	actions := []string{"detail"}
	switch availability {
	case AvailAvailable, AvailPartiallyAvailable:
		if has("play") {
			actions = append([]string{"play"}, actions...)
		}
	case AvailNotInLibrary, AvailDeleted:
		if has("request") {
			actions = append(actions, "request")
		}
	}
	return actions
}

func hitFrom(r jellyseerr.Result, scopes []string, imageBase string) SearchHit {
	availability := availabilityFor(r.MediaInfo)
	hit := SearchHit{
		Media: MediaRef{
			Key:   mediaKey(r.MediaType, r.ID),
			Type:  normaliseType(r.MediaType),
			Title: r.DisplayTitle(),
			Year:  r.Year(),
			IDs:   MediaID{Tmdb: r.ID},
		},
		Overview:     r.Overview,
		Availability: availability,
		Rating:       r.VoteAverage,
	}
	if r.PosterPath != "" {
		hit.Media.Poster = imageBase + "/w342" + r.PosterPath
	}
	if r.BackdropPath != "" {
		hit.Media.Backdrop = imageBase + "/w780" + r.BackdropPath
	}
	if info := r.MediaInfo; info != nil {
		hit.JellyfinItemID = info.JellyfinMediaID
		if info.TvdbID != nil {
			hit.Media.IDs.Tvdb = *info.TvdbID
		}
		hit.Media.IDs.Imdb = info.ImdbID
		if len(info.DownloadStatus) > 0 {
			d := info.DownloadStatus[0]
			hit.Progress = d.Progress()
			hit.ETA = d.TimeLeft
		}
	}

	kind := "Movie"
	if hit.Media.Type == "series" {
		kind = "Series"
	}
	hit.Subtitle = kind
	if hit.Media.Year > 0 {
		hit.Subtitle = fmt.Sprintf("%d · %s", hit.Media.Year, kind)
	}

	hit.Actions = actionsFor(availability, scopes)
	return hit
}

// enrichHitWithLibrary corrects Jellyseerr's delayed title state at render
// time. Search and Discover cache the raw upstream response, so a fresh
// Jellyfin index sweep can fix even a cached card without another TMDB call.
func (s *Server) enrichHitWithLibrary(hit SearchHit, scopes []string) SearchHit {
	key := MediaKey{Source: "tmdb", Type: hit.Media.Type, ID: hit.Media.IDs.Tmdb}
	entry, present := s.libraryEntry(key, hit.Media.IDs.Tvdb, hit.Media.IDs.Imdb)
	if !present {
		return hit
	}
	hit.JellyfinItemID = entry.ItemID
	switch hit.Media.Type {
	case "movie":
		hit.Availability = AvailAvailable
	case "series":
		// The title index proves that at least one episode exists, but cannot
		// claim that every requested season is complete. Keep a live download
		// visible while later episodes are still arriving.
		if hit.Availability != AvailAvailable && hit.Availability != AvailDownloading {
			hit.Availability = AvailPartiallyAvailable
		}
	}
	hit.Actions = actionsFor(hit.Availability, scopes)
	return hit
}
