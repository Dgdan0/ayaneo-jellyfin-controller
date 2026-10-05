package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
)

// A Library title's people come from Jellyfin with a name, a role, a type and a
// picture, and no provider ids. Each person is an item of its own, and that item
// holds the TMDB id the apps need to open the filmography. The hub asks for the
// cast's items once per title and puts the id on each person (#27).

const (
	castTitleID = "0123456789abcdef0123456789abcdef"
	castUserID  = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	castOtherID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
)

// castPersonID is a Jellyfin item id for the nth person.
func castPersonID(n int) string { return fmt.Sprintf("%032x", 0xc0de0000+n) }

// castPerson is one entry of an item's People, as Jellyfin writes it.
func castPerson(n int, name, role, kind string) string {
	return fmt.Sprintf(`{"Id":%q,"Name":%q,"Role":%q,"Type":%q,"PrimaryImageTag":"tag%d"}`,
		castPersonID(n), name, role, kind, n)
}

// castJellyfin is a Jellyfin with one title and a cast. Each person is an item
// of its own, found by id, and holds the provider ids Jellyfin knows for them.
type castJellyfin struct {
	server *httptest.Server

	mu          sync.Mutex
	people      string            // the title's People array
	persons     map[string]string // person id -> the ProviderIds object Jellyfin holds
	lookups     []url.Values      // each GET /Items?ids= it was asked
	titleReads  int
	failLookups bool
	gate        chan struct{} // when set, a lookup waits for it to be closed
}

func newCastJellyfin(t *testing.T, people []string, persons map[string]string) *castJellyfin {
	t.Helper()
	upstream := &castJellyfin{people: "[" + strings.Join(people, ",") + "]", persons: persons}
	upstream.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstream.mu.Lock()
		defer upstream.mu.Unlock()
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/Items/"+castTitleID:
			upstream.titleReads++
			_, _ = fmt.Fprintf(w, `{"Id":%q,"Name":"Movie","Type":"Movie","People":%s}`, castTitleID, upstream.people)
		case r.Method == http.MethodGet && r.URL.Path == "/Items":
			upstream.lookups = append(upstream.lookups, r.URL.Query())
			if gate := upstream.gate; gate != nil {
				upstream.mu.Unlock()
				select {
				case <-gate:
				case <-r.Context().Done():
				}
				upstream.mu.Lock()
			}
			if upstream.failLookups {
				http.Error(w, "boom", http.StatusInternalServerError)
				return
			}
			var items []string
			for _, id := range strings.Split(r.URL.Query().Get("ids"), ",") {
				if providers, known := upstream.persons[id]; known {
					items = append(items, fmt.Sprintf(`{"Id":%q,"Name":"Someone","Type":"Person","ProviderIds":%s}`, id, providers))
				}
			}
			_, _ = fmt.Fprintf(w, `{"Items":[%s],"TotalRecordCount":%d}`, strings.Join(items, ","), len(items))
		case r.Method == http.MethodPost && strings.HasPrefix(r.URL.Path, "/Users/"+castUserID+"/PlayedItems/"):
			w.WriteHeader(http.StatusNoContent)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(upstream.server.Close)
	return upstream
}

// hold makes every lookup wait, until the test ends or the caller gives up.
func (j *castJellyfin) hold(t *testing.T) {
	t.Helper()
	gate := make(chan struct{})
	j.mu.Lock()
	j.gate = gate
	j.mu.Unlock()
	t.Cleanup(func() { close(gate) })
}

func (j *castJellyfin) setFailing(failing bool) {
	j.mu.Lock()
	defer j.mu.Unlock()
	j.failLookups = failing
}

func (j *castJellyfin) counts() (lookups, titleReads int) {
	j.mu.Lock()
	defer j.mu.Unlock()
	return len(j.lookups), j.titleReads
}

func (j *castJellyfin) lastLookup(t *testing.T) url.Values {
	t.Helper()
	j.mu.Lock()
	defer j.mu.Unlock()
	if len(j.lookups) == 0 {
		t.Fatal("Jellyfin was never asked for the cast's items")
	}
	return j.lookups[len(j.lookups)-1]
}

// castServer is the hub in front of that Jellyfin, with a clock the test moves.
func castServer(upstream *castJellyfin) (http.Handler, func(time.Duration)) {
	server := NewServer(libraryAPIConfig(upstream.server.URL, castUserID))
	now := time.Now()
	server.cache.WithClock(func() time.Time { return now })
	return server.Handler(), func(d time.Duration) { now = now.Add(d) }
}

// The people a page was given, each as the JSON object the app reads, so that a
// field that is absent can be told from one that is zero.
func peopleOf(t *testing.T, response *httptest.ResponseRecorder) []map[string]any {
	t.Helper()
	if response.Code != http.StatusOK {
		t.Fatalf("the title returned %d: %s", response.Code, response.Body.String())
	}
	var body struct {
		Item struct {
			People []map[string]any `json:"people"`
		} `json:"item"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	return body.Item.People
}

func readCast(t *testing.T, handler http.Handler, user string) []map[string]any {
	t.Helper()
	return peopleOf(t, playbackRequest(handler, http.MethodGet, "/v1/library/items/"+castTitleID, "", user))
}

func TestALibraryTitlesCastCarriesTheTMDBIdsJellyfinHolds(t *testing.T) {
	upstream := newCastJellyfin(t, []string{
		castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor"),
		castPerson(2, "Emma Watson", "Hermione Granger", "Actor"),
		castPerson(3, "Chris Columbus", "", "Director"),
		castPerson(3, "Chris Columbus", "Cameo", "Actor"), // credited twice, one person
		castPerson(4, "No Id Known", "", "Actor"),         // Jellyfin holds no TMDB id for them
		castPerson(5, "Not A Number", "", "Actor"),
		castPerson(6, "Zero", "", "Actor"),
		castPerson(7, "", "", "Actor"),             // no name: never shown, never asked about
		castPerson(8, "Not Found", "", "Actor"),    // Jellyfin has no item for them at all
		castPerson(9, "Negative", "", "GuestStar"), // not an id
	}, map[string]string{
		castPersonID(1): `{"Tmdb":"10980","Imdb":"nm0705356"}`,
		castPersonID(2): `{"Tmdb":"10990"}`,
		castPersonID(3): `{"Tmdb":"9005"}`,
		castPersonID(4): `{}`,
		castPersonID(5): `{"Tmdb":"abc"}`,
		castPersonID(6): `{"Tmdb":"0"}`,
		castPersonID(7): `{"Tmdb":"1"}`,
		castPersonID(9): `{"Tmdb":"-4"}`,
	})
	handler, _ := castServer(upstream)

	people := readCast(t, handler, "")
	if len(people) != 9 {
		t.Fatalf("the cast has %d people, want the 9 with a name: %v", len(people), people)
	}
	for index, want := range []float64{10980, 10990, 9005, 9005} {
		if people[index]["tmdbId"] != want {
			t.Errorf("%v has tmdbId %v, want %v", people[index]["name"], people[index]["tmdbId"], want)
		}
	}
	for _, person := range people[4:] {
		if id, present := person["tmdbId"]; present {
			t.Errorf("%v has tmdbId %v, but Jellyfin does not know a usable one", person["name"], id)
		}
	}

	// What a person was before is what a person still is.
	first := people[0]
	if first["id"] != castPersonID(1) || first["name"] != "Daniel Radcliffe" || first["role"] != "Harry Potter" ||
		first["type"] != "Actor" || first["image"] != "/v1/img/jf/"+castPersonID(1)+"/Primary?tag=tag1" {
		t.Errorf("a person changed shape: %v", first)
	}

	// One call for the title, naming each person once and nobody who is not shown.
	if lookups, _ := upstream.counts(); lookups != 1 {
		t.Fatalf("Jellyfin was asked about the cast %d times, want once", lookups)
	}
	lookup := upstream.lastLookup(t)
	asked := strings.Split(lookup.Get("ids"), ",")
	slices.Sort(asked)
	want := []string{castPersonID(1), castPersonID(2), castPersonID(3), castPersonID(4), castPersonID(5), castPersonID(6), castPersonID(8), castPersonID(9)}
	if !slices.Equal(asked, want) {
		t.Errorf("asked about %v, want %v", asked, want)
	}
	if lookup.Get("fields") != "ProviderIds" || lookup.Get("userId") != castUserID {
		t.Errorf("the lookup was %v", lookup)
	}
}

// Who a performer is does not change with a watch, so the answer is kept for a
// day: a second look at the title, or at it after the item itself has gone stale,
// does not ask again.
func TestThePeopleLookupIsKeptADayAndOutlivesTheItemItWasMadeFor(t *testing.T) {
	upstream := newCastJellyfin(t, []string{castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor")},
		map[string]string{castPersonID(1): `{"Tmdb":"10980"}`})
	handler, advance := castServer(upstream)

	readCast(t, handler, "")
	advance(time.Minute) // the item is past its 45 seconds
	people := readCast(t, handler, "")
	advance(22 * time.Hour)
	readCast(t, handler, "")

	lookups, titleReads := upstream.counts()
	if titleReads != 3 {
		t.Fatalf("the title was read %d times, want 3: the item has a short life", titleReads)
	}
	if lookups != 1 {
		t.Fatalf("the cast was looked up %d times in 22 hours, want 1", lookups)
	}
	if people[0]["tmdbId"] != float64(10980) {
		t.Fatalf("the kept answer was not used: %v", people[0])
	}
}

// A cast that changed is a different question: a person added to the title is
// looked up, not hidden for a day behind the answer for the cast it had.
func TestACastThatChangedIsLookedUpAgain(t *testing.T) {
	upstream := newCastJellyfin(t, []string{castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor")},
		map[string]string{castPersonID(1): `{"Tmdb":"10980"}`, castPersonID(2): `{"Tmdb":"10990"}`})
	handler, advance := castServer(upstream)

	readCast(t, handler, "")
	upstream.mu.Lock()
	upstream.people = "[" + castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor") + "," + castPerson(2, "Emma Watson", "Hermione Granger", "Actor") + "]"
	upstream.mu.Unlock()
	advance(time.Minute)
	people := readCast(t, handler, "")

	if lookups, _ := upstream.counts(); lookups != 2 {
		t.Fatalf("a cast with a new person was looked up %d times in all, want 2", lookups)
	}
	if len(people) != 2 || people[1]["tmdbId"] != float64(10990) {
		t.Fatalf("the person who joined the cast has no id: %v", people)
	}
}

// A lookup is made as the profile that is reading, like everything else the
// hub asks Jellyfin, so one profile's answer is not served to another.
func TestThePeopleLookupIsAskedAndKeptPerProfile(t *testing.T) {
	upstream := newCastJellyfin(t, []string{castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor")},
		map[string]string{castPersonID(1): `{"Tmdb":"10980"}`})
	handler, _ := castServer(upstream)

	readCast(t, handler, castUserID)
	if got := upstream.lastLookup(t).Get("userId"); got != castUserID {
		t.Fatalf("the first profile's lookup was made as %q", got)
	}
	readCast(t, handler, castOtherID)
	if got := upstream.lastLookup(t).Get("userId"); got != castOtherID {
		t.Fatalf("the second profile's lookup was made as %q", got)
	}
	if lookups, _ := upstream.counts(); lookups != 2 {
		t.Fatalf("two profiles made %d lookups, want 2", lookups)
	}
	readCast(t, handler, castOtherID)
	if lookups, _ := upstream.counts(); lookups != 2 {
		t.Fatalf("a profile that had its answer asked again: %d lookups", lookups)
	}
}

// The ids are an enhancement: a title opens with its cast whether or not Jellyfin
// answered, and a failure is not remembered.
func TestAFailedPeopleLookupLeavesTheCastWithoutIdsAndIsNotRemembered(t *testing.T) {
	upstream := newCastJellyfin(t, []string{castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor")},
		map[string]string{castPersonID(1): `{"Tmdb":"10980"}`})
	upstream.setFailing(true)
	handler, advance := castServer(upstream)

	people := readCast(t, handler, "")
	if len(people) != 1 || people[0]["name"] != "Daniel Radcliffe" {
		t.Fatalf("the cast was lost with the lookup: %v", people)
	}
	if id, present := people[0]["tmdbId"]; present {
		t.Fatalf("a failed lookup produced the id %v", id)
	}

	upstream.setFailing(false)
	advance(time.Minute)
	people = readCast(t, handler, "")
	if lookups, _ := upstream.counts(); lookups != 2 {
		t.Fatalf("the failure was remembered: %d lookups", lookups)
	}
	if people[0]["tmdbId"] != float64(10980) {
		t.Fatalf("the lookup after Jellyfin recovered gave %v", people[0])
	}
}

// The page is complete without the ids, so a Jellyfin that is slow to answer
// about them costs the title no more than the lookup's own short budget.
func TestASlowPeopleLookupDoesNotHoldTheTitleBack(t *testing.T) {
	upstream := newCastJellyfin(t, []string{castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor")},
		map[string]string{castPersonID(1): `{"Tmdb":"10980"}`})
	upstream.hold(t)
	server := NewServer(libraryAPIConfig(upstream.server.URL, castUserID))
	server.personLookupBudget = 50 * time.Millisecond
	handler := server.Handler()

	started := time.Now()
	people := readCast(t, handler, "")
	if took := time.Since(started); took > 3*time.Second {
		t.Fatalf("the title waited %v for a lookup that was given 50ms", took)
	}
	if len(people) != 1 || people[0]["name"] != "Daniel Radcliffe" {
		t.Fatalf("the cast was lost with the lookup: %v", people)
	}
	if id, present := people[0]["tmdbId"]; present {
		t.Fatalf("a lookup that never answered produced the id %v", id)
	}
}

// Only a person's own item says who a person is: an item of another kind that
// came back carrying a TMDB id would open the wrong filmography.
func TestOnlyAPersonsOwnTMDBIdIsTaken(t *testing.T) {
	got := tmdbIDsOfPersons([]jellyfin.Item{
		{ID: "a", Type: "Person", ProviderIds: &jellyfin.ProviderIds{Tmdb: "10980"}},
		{ID: "b", Type: "Movie", ProviderIds: &jellyfin.ProviderIds{Tmdb: "603"}},
		{ID: "c", Type: "Person"},
		{ID: "d", Type: "Person", ProviderIds: &jellyfin.ProviderIds{Tmdb: " 77 "}},
	})
	if len(got) != 2 || got["a"] != 10980 || got["d"] != 77 {
		t.Fatalf("ids = %v, want only the two people's", got)
	}
}

func TestATitleWithoutPeopleAsksJellyfinNothingMore(t *testing.T) {
	upstream := newCastJellyfin(t, nil, nil)
	handler, _ := castServer(upstream)

	if people := readCast(t, handler, ""); len(people) != 0 {
		t.Fatalf("people = %v", people)
	}
	if lookups, titleReads := upstream.counts(); lookups != 0 || titleReads != 1 {
		t.Fatalf("a title with nobody on it made %d lookups after %d reads", lookups, titleReads)
	}
}

// Jellyfin sits behind Kestrel, which refuses a request line over 8 KB, and a
// hundred ids are a third of that. A cast is in billing order, so the people left
// out when a title names more are the last ones.
func TestALargeCastIsLookedUpInOneCallForItsFirstHundred(t *testing.T) {
	var people []string
	persons := map[string]string{}
	for n := 1; n <= 130; n++ {
		people = append(people, castPerson(n, fmt.Sprintf("Person %d", n), "", "Actor"))
		persons[castPersonID(n)] = fmt.Sprintf(`{"Tmdb":"%d"}`, 1000+n)
	}
	upstream := newCastJellyfin(t, people, persons)
	handler, _ := castServer(upstream)

	cast := readCast(t, handler, "")
	if len(cast) != 130 {
		t.Fatalf("the cast has %d people, want all 130", len(cast))
	}
	if lookups, _ := upstream.counts(); lookups != 1 {
		t.Fatalf("a large cast took %d lookups, want one", lookups)
	}
	asked := strings.Split(upstream.lastLookup(t).Get("ids"), ",")
	if len(asked) != 100 {
		t.Fatalf("the lookup named %d people, want 100", len(asked))
	}
	if !slices.Contains(asked, castPersonID(1)) || !slices.Contains(asked, castPersonID(100)) || slices.Contains(asked, castPersonID(101)) {
		t.Errorf("the lookup did not name the first hundred: %v", asked)
	}
	if cast[99]["tmdbId"] != float64(1100) {
		t.Errorf("the hundredth person has %v", cast[99]["tmdbId"])
	}
	if id, present := cast[100]["tmdbId"]; present {
		t.Errorf("the 101st person has %v without being asked about", id)
	}
}

// The app draws the page again from the answer to a watched or favourite toggle,
// so that answer has to carry what the page had.
func TestTheStateRouteKeepsTheCastsIds(t *testing.T) {
	upstream := newCastJellyfin(t, []string{castPerson(1, "Daniel Radcliffe", "Harry Potter", "Actor")},
		map[string]string{castPersonID(1): `{"Tmdb":"10980"}`})
	handler, _ := castServer(upstream)

	response := playbackRequest(handler, http.MethodPost, "/v1/library/items/"+castTitleID+"/state", `{"played":true}`, castUserID)
	people := peopleOf(t, response)
	if len(people) != 1 || people[0]["tmdbId"] != float64(10980) {
		t.Fatalf("the answer to a watched toggle dropped the id: %v", people)
	}
}
