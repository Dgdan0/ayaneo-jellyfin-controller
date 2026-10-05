package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"slices"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/cache"
)

// A title's people arrive from Jellyfin with a name, a role, a type and a picture,
// and no provider ids: those are on the person, who is an item of their own. The
// apps open a performer's filmography from a TMDB person id (GET /v1/person/{id}),
// so the hub asks Jellyfin for the cast's items once per title and puts the id on
// each person (#27).

const (
	// maxPersonLookups bounds the one call a title makes. Jellyfin sits behind
	// Kestrel, which refuses a request line over 8 KB, and a hundred ids take
	// about 3.5 KB of it. A cast is in billing order, so the people left out when a
	// title names more are the last ones.
	maxPersonLookups = 100

	// personLookupTimeout keeps a slow answer from holding the title back: the
	// page is complete without these ids, and a failure is simply no ids.
	// Server.personLookupBudget replaces it in a test.
	personLookupTimeout = 4 * time.Second
)

// addPersonTMDBIDs puts a TMDB id on each person of a title that Jellyfin holds
// one for, and leaves the others as they are.
//
// Who a performer is does not change with a watch, so the answer is kept for a
// day under the metadata policy, apart from the item's own 15 seconds. The key is
// the set of people asked about and the profile asking: a cast that gains a person
// is a new question rather than a day of hiding them, and the call is made as the
// profile that is reading, like every other Jellyfin read. It sits under
// "library:", so a library scan or a deletion clears it, but a watched toggle
// (which clears one profile's "library:<user>:") does not.
func (s *Server) addPersonTMDBIDs(ctx context.Context, client *jellyfin.Client, item *LibraryItem) {
	ids := personIDsToLookUp(item.People)
	if len(ids) == 0 {
		return
	}
	timeout := personLookupTimeout
	if s.personLookupBudget > 0 {
		timeout = s.personLookupBudget
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	known, _, err := cache.Fetch(ctx, s.cache, personLookupKey(client.UserID(), ids), cache.Metadata,
		func(ctx context.Context) (map[string]int, error) {
			page, err := client.Items(ctx, jellyfin.ItemsQuery{IDs: ids, Fields: "ProviderIds", Limit: len(ids)})
			if err != nil {
				return nil, err
			}
			return tmdbIDsOfPersons(page.Items), nil
		})
	if err != nil {
		return
	}
	for i := range item.People {
		item.People[i].TmdbID = known[item.People[i].ID]
	}
}

// personIDsToLookUp is the people to ask Jellyfin about: each once however often
// the title credits them, the first maxPersonLookups of them, in a fixed order
// so that the same cast is always the same question.
func personIDsToLookUp(people []LibraryPerson) []string {
	ids := make([]string, 0, len(people))
	for _, person := range people {
		if person.ID == "" || slices.Contains(ids, person.ID) {
			continue
		}
		if len(ids) == maxPersonLookups {
			break
		}
		ids = append(ids, person.ID)
	}
	slices.Sort(ids)
	return ids
}

func personLookupKey(userID string, ids []string) string {
	sum := sha256.Sum256([]byte(strings.Join(ids, ",")))
	return "library:people:" + userID + ":" + hex.EncodeToString(sum[:16])
}

// tmdbIDsOfPersons maps a person's Jellyfin id to their TMDB id. A person whose
// item holds none, or one that is not a positive number, is left out: no id is
// better than one that opens the wrong filmography.
func tmdbIDsOfPersons(items []jellyfin.Item) map[string]int {
	out := make(map[string]int, len(items))
	for _, item := range items {
		if !strings.EqualFold(item.Type, "Person") || item.ProviderIds == nil {
			continue
		}
		if id, err := strconv.Atoi(strings.TrimSpace(item.ProviderIds.Tmdb)); err == nil && id > 0 {
			out[item.ID] = id
		}
	}
	return out
}
