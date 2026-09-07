package api

import (
	"ayaneohub/internal/index"
	"testing"

	"ayaneohub/internal/adapters/jellyseerr"
)

func credit(id int, title, character, date string, popularity float64) jellyseerr.PersonCredit {
	return jellyseerr.PersonCredit{
		ID: id, MediaType: "movie", Title: title,
		Character: character, ReleaseDate: date, Popularity: popularity,
	}
}

func TestSelfAppearancesAreDropped(t *testing.T) {
	// This is not tidying: TMDB's combined credits are full of chat-show guest
	// spots, and they carry the *show's* popularity. Leaving them in means the
	// top of a filmography is Jimmy Fallon rather than anything acted in. On one
	// real actor this removed 27 of 67 credits.
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		credit(1, "The Tonight Show", "Self - Guest", "2014-01-01", 227),
		credit(2, "Late Night", "Self", "2014-01-01", 140),
		credit(3, "SNL", "Self - Host", "1975-01-01", 102),
		credit(4, "A Documentary", "Himself", "2026-01-01", 5),
		credit(5, "Dune", "Paul Atreides", "2021-09-15", 45),
	}}
	got := testServer().filmographyFrom(credits, "release", []string{"read"})
	if len(got) != 1 {
		t.Fatalf("kept %d credits, want only the acted role", len(got))
	}
	if got[0].Media.Title != "Dune" {
		t.Fatalf("kept %q", got[0].Media.Title)
	}
}

func TestARealRoleIsNeverMistakenForASelfAppearance(t *testing.T) {
	// "Selfie" and "Selma" start with "self" as a substring but are not roles as
	// oneself, so the check is a prefix on the whole word.
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		credit(1, "A Film", "Selina Kyle", "2020-01-01", 1),
		credit(2, "Another", "Herschel", "2020-01-01", 1),
	}}
	if got := testServer().filmographyFrom(credits, "release", nil); len(got) != 2 {
		t.Fatalf("kept %d, want 2 -- a real name was mistaken for a self role", len(got))
	}
}

func TestDuplicatesAreCollapsed(t *testing.T) {
	// A recurring show arrives once per credited episode block.
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		credit(7, "Same Show", "Eric", "2019-01-01", 1),
		credit(7, "Same Show", "Eric", "2019-01-01", 1),
		credit(7, "Same Show", "Eric", "2019-01-01", 1),
	}}
	if got := testServer().filmographyFrom(credits, "release", nil); len(got) != 1 {
		t.Fatalf("kept %d copies of one title", len(got))
	}
}

func TestSortedByReleaseIsNewestFirst(t *testing.T) {
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		credit(1, "Older", "A", "2015-01-01", 500),
		credit(2, "Newest", "B", "2026-01-01", 1),
		credit(3, "Middle", "C", "2020-01-01", 250),
	}}
	got := testServer().filmographyFrom(credits, "release", nil)
	want := []string{"Newest", "Middle", "Older"}
	for i, title := range want {
		if got[i].Media.Title != title {
			t.Fatalf("position %d = %q, want %q", i, got[i].Media.Title, title)
		}
	}
}

func TestSortedByPopularityIsStillAvailable(t *testing.T) {
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		credit(1, "Obscure", "A", "2026-01-01", 1),
		credit(2, "Famous", "B", "2015-01-01", 500),
	}}
	got := testServer().filmographyFrom(credits, "popularity", nil)
	if got[0].Media.Title != "Famous" {
		t.Fatalf("first = %q, want Famous", got[0].Media.Title)
	}
}

func TestAvailabilityIsUnknownNotGuessed(t *testing.T) {
	// Combined credits carry no mediaInfo, so the hub genuinely does not know
	// whether these are in the library. "not_in_library" would be a guess
	// presented as a fact, and a wrong badge is worse than no badge.
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		credit(1, "Dune", "Paul", "2021-09-15", 45),
	}}
	got := testServer().filmographyFrom(credits, "release", []string{"read", "request", "play"})
	if got[0].Availability != "unknown" {
		t.Fatalf("Availability = %q, want unknown", got[0].Availability)
	}
	// And no Request button, since we cannot say whether it is already there.
	for _, action := range got[0].Actions {
		if action == "request" || action == "play" {
			t.Fatalf("offered %q for a title of unknown availability", action)
		}
	}
}

func TestTheCharacterIsShownInTheSubtitle(t *testing.T) {
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		credit(1, "Dune", "Paul Atreides", "2021-09-15", 45),
	}}
	got := testServer().filmographyFrom(credits, "release", nil)
	if got[0].Subtitle != "2021 · Movie · Paul Atreides" {
		t.Fatalf("Subtitle = %q", got[0].Subtitle)
	}
}

func TestNonMediaCreditsAreSkipped(t *testing.T) {
	credits := &jellyseerr.CombinedCredits{Cast: []jellyseerr.PersonCredit{
		{ID: 1, MediaType: "person", Title: "Somebody"},
		credit(2, "Dune", "Paul", "2021-09-15", 45),
	}}
	if got := testServer().filmographyFrom(credits, "release", nil); len(got) != 1 {
		t.Fatalf("kept %d, want only the media credit", len(got))
	}
}

func TestNoCreditsIsAnEmptyListNotNil(t *testing.T) {
	// A nil slice marshals to JSON null, which the app would have to special
	// case. An empty list just renders as an empty grid.
	if got := testServer().filmographyFrom(nil, "release", nil); got == nil || len(got) != 0 {
		t.Fatalf("got %v, want an empty slice", got)
	}
}

func TestCastIsCappedAndKeepsBillingOrder(t *testing.T) {
	// A film routinely lists forty, most of them one-line parts. The first dozen
	// in billing order is the cast anyone recognises.
	cast := make([]jellyseerr.CastCredit, 0, 40)
	for i := 0; i < 40; i++ {
		cast = append(cast, jellyseerr.CastCredit{ID: i, Name: string(rune('A' + i%26)), Order: i})
	}
	got := castFrom(&jellyseerr.Credits{Cast: cast})
	if len(got) != maxCast {
		t.Fatalf("kept %d, want %d", len(got), maxCast)
	}
	if got[0].ID != 0 || got[11].ID != 11 {
		t.Fatal("billing order was not preserved")
	}
}

func TestCastProfilePointsAtTheHub(t *testing.T) {
	got := castFrom(&jellyseerr.Credits{Cast: []jellyseerr.CastCredit{
		{ID: 1, Name: "Someone", ProfilePath: "/abc.jpg"},
	}})
	if got[0].Profile != "/v1/img/tmdb/w185/abc.jpg" {
		t.Fatalf("Profile = %q", got[0].Profile)
	}
}

func TestNoCreditsBlockMeansNoCast(t *testing.T) {
	if got := castFrom(nil); got != nil {
		t.Fatalf("got %v, want nil", got)
	}
}

// testServer is a Server with just enough wired up for the pure helpers.
//
// The index is present but never swept, so it reports "not ready" and every
// library lookup misses -- which is exactly the state these tests want: they
// are about credit filtering and ordering, not about what the library holds.
func testServer() *Server {
	return &Server{index: index.New()}
}
