package api

import (
	"context"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyseerr"
	"ayaneohub/internal/cache"
)

// CastMember is one performer on a title.
type CastMember struct {
	ID        int    `json:"id"`
	Name      string `json:"name"`
	Character string `json:"character,omitempty"`
	// Hub-relative, like every other image in this API.
	Profile string `json:"profile,omitempty"`
}

// PersonResponse is a performer and what they have been in.
type PersonResponse struct {
	ID        int         `json:"id"`
	Name      string      `json:"name"`
	Profile   string      `json:"profile,omitempty"`
	Biography string      `json:"biography,omitempty"`
	KnownFor  string      `json:"knownFor,omitempty"`
	Credits   []SearchHit `json:"credits"`
	SortedBy  string      `json:"sortedBy"`
	Partial   []Partial   `json:"partial"`
	Cache     CacheInfo   `json:"cache"`
}

// maxCast is how many performers a detail screen carries.
//
// A film routinely lists forty, most of them one-line parts. The first dozen in
// billing order is the cast anyone actually recognises, and it keeps the payload
// small on a phone connection.
const maxCast = 12

func castFrom(credits *jellyseerr.Credits) []CastMember {
	if credits == nil {
		return nil
	}
	people := make([]CastMember, 0, maxCast)
	for _, c := range credits.Cast {
		if len(people) >= maxCast {
			break
		}
		member := CastMember{ID: c.ID, Name: c.Name, Character: c.Character}
		if c.ProfilePath != "" {
			member.Profile = imagePrefix + "/w185" + c.ProfilePath
		}
		people = append(people, member)
	}
	return people
}

func (s *Server) handlePerson(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.Atoi(r.PathValue("id"))
	if err != nil || id <= 0 {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "person id must be a positive number",
		})
		return
	}
	client := s.jellyseerr
	if client == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code:    CodeUpstreamDown,
			Service: "jellyseerr",
			Message: "Jellyseerr is not configured",
		})
		return
	}

	sortBy := r.URL.Query().Get("sort")
	if sortBy != "popularity" {
		// Release date by default, and that is a considered choice rather than an
		// arbitrary one. TMDB's combined credits include every talk-show
		// appearance, and those carry the *show's* popularity -- so sorting by it
		// puts Saturday Night Live and The Tonight Show above the films the
		// person is actually known for.
		sortBy = "release"
	}

	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()

	// Metadata about a person changes about as often as a film's runtime.
	person, meta, err := cache.Fetch(ctx, s.cache, "person:"+strconv.Itoa(id), cache.Metadata,
		func(ctx context.Context) (*jellyseerr.Person, error) {
			return client.Person(ctx, id)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyseerr", err)
		return
	}

	credits, _, err := cache.Fetch(ctx, s.cache, "person-credits:"+strconv.Itoa(id), cache.Metadata,
		func(ctx context.Context) (*jellyseerr.CombinedCredits, error) {
			return client.PersonCredits(ctx, id)
		})
	if err != nil {
		writeUpstreamError(w, r, "jellyseerr", err)
		return
	}

	scopes := TokenFrom(r.Context()).Scopes
	out := PersonResponse{
		ID:        person.ID,
		Name:      person.Name,
		Biography: person.Biography,
		KnownFor:  person.KnownForDepartment,
		SortedBy:  sortBy,
		Credits:   s.filmographyFrom(credits, sortBy, scopes),
		Partial:   []Partial{},
		Cache:     cacheInfoFrom(meta),
	}
	if person.ProfilePath != "" {
		out.Profile = imagePrefix + "/w185" + person.ProfilePath
	}

	writeJSON(w, http.StatusOK, out)
}

// filmographyFrom turns raw combined credits into something worth showing.
//
// Two passes of cleanup, both necessary:
//
//   - **Self-appearances are dropped.** A chat-show guest spot is not a role, and
//     leaving them in means the top of a filmography is Jimmy Fallon rather than
//     anything the person acted in. Measured on one real actor, this removed 27
//     of 67 credits.
//   - **Duplicates are collapsed.** A recurring show appears once per credited
//     episode block, so the same title arrives several times.
func (s *Server) filmographyFrom(
	credits *jellyseerr.CombinedCredits, sortBy string, scopes []string,
) []SearchHit {
	if credits == nil {
		return []SearchHit{}
	}

	seen := map[int]bool{}
	kept := make([]jellyseerr.PersonCredit, 0, len(credits.Cast))
	for _, c := range credits.Cast {
		if c.MediaType != "movie" && c.MediaType != "tv" {
			continue
		}
		if seen[c.ID] || isSelfRole(c.Character) {
			continue
		}
		seen[c.ID] = true
		kept = append(kept, c)
	}

	sort.SliceStable(kept, func(a, b int) bool {
		if sortBy == "popularity" {
			return kept[a].Popularity > kept[b].Popularity
		}
		return creditDate(kept[a]) > creditDate(kept[b])
	})

	hits := make([]SearchHit, 0, len(kept))
	for _, c := range kept {
		hit := hitFrom(jellyseerr.Result{
			ID:           c.ID,
			MediaType:    c.MediaType,
			Title:        c.Title,
			Name:         c.Name,
			Overview:     c.Overview,
			ReleaseDate:  c.ReleaseDate,
			FirstAirDate: c.FirstAirDate,
			PosterPath:   c.PosterPath,
			BackdropPath: c.BackdropPath,
			VoteAverage:  c.VoteAverage,
			// Deliberately nil: combined credits carry no mediaInfo, so we do not
			// know whether these are in the library.
			MediaInfo: nil,
		}, scopes, imagePrefix)

		// Overriding the availability that hitFrom inferred. Without mediaInfo,
		// "not_in_library" would be a guess presented as fact -- and a wrong
		// green badge is worse than no badge. The app shows nothing for unknown.
		//
		// The provider-id index turns most of these into a real answer: it knows
		// what Jellyfin holds, which is exactly the question mediaInfo would
		// have answered. A miss still reports "unknown" rather than "you do not
		// have this", because a library item whose metadata was never scraped is
		// genuinely unknown rather than genuinely absent.
		hit.Availability = "unknown"
		if key, err := ParseMediaKey(hit.Media.Key); err == nil {
			if _, inLibrary := s.libraryEntry(key, 0, ""); inLibrary {
				hit.Availability = AvailAvailable
			}
		}
		hit.Actions = []string{"detail"}
		if c.Character != "" {
			hit.Subtitle = hit.Subtitle + " · " + c.Character
		}
		hits = append(hits, hit)
	}
	return hits
}

// isSelfRole catches the ways TMDB spells "appeared as themselves".
func isSelfRole(character string) bool {
	lower := strings.ToLower(strings.TrimSpace(character))
	switch {
	case lower == "":
		return false
	case strings.HasPrefix(lower, "self"),
		strings.HasPrefix(lower, "himself"),
		strings.HasPrefix(lower, "herself"),
		strings.HasPrefix(lower, "themselves"):
		return true
	}
	return false
}

func creditDate(c jellyseerr.PersonCredit) string {
	if c.ReleaseDate != "" {
		return c.ReleaseDate
	}
	return c.FirstAirDate
}
