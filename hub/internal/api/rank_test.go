package api

import "testing"

func hit(title string) SearchHit {
	return SearchHit{Media: MediaRef{Title: title}}
}

func titles(hits []SearchHit) []string {
	out := make([]string, len(hits))
	for i := range hits {
		out[i] = hits[i].Media.Title
	}
	return out
}

func TestNormalizeTitleDropsLeadingArticle(t *testing.T) {
	// So "The Mentalist" and "Mentalist" compare equal -- both are exact
	// answers to "the mentalist" and both belong above "The Mentalists".
	if normalizeTitle("The Mentalist") != "mentalist" {
		t.Fatalf("got %q", normalizeTitle("The Mentalist"))
	}
	if normalizeTitle("A Quiet Place") != "quiet place" {
		t.Fatalf("got %q", normalizeTitle("A Quiet Place"))
	}
	if normalizeTitle("An Education") != "education" {
		t.Fatalf("got %q", normalizeTitle("An Education"))
	}
}

func TestNormalizeTitleKeepsAnArticleThatIsTheWholeTitle(t *testing.T) {
	// "The Thing" must not become "thing" *and* "A" must not become empty.
	if got := normalizeTitle("The"); got != "the" {
		t.Fatalf("got %q", got)
	}
}

func TestNormalizeTitlePunctuation(t *testing.T) {
	// An apostrophe closes up; a colon or dash becomes a gap.
	if got := normalizeTitle("Don't Look Up"); got != "dont look up" {
		t.Fatalf("got %q", got)
	}
	if got := normalizeTitle("Dune: Part Two"); got != "dune part two" {
		t.Fatalf("got %q", got)
	}
	if got := normalizeTitle("Spider-Man"); got != "spider man" {
		t.Fatalf("got %q", got)
	}
	if got := normalizeTitle("  Extra   Spaces  "); got != "extra spaces" {
		t.Fatalf("got %q", got)
	}
}

func TestTitleTiers(t *testing.T) {
	cases := []struct {
		query, title string
		want         int
	}{
		{"the mentalist", "The Mentalist", tierExact},
		{"the mentalist", "Mentalist", tierExact},
		{"the mentalist", "The Mentalists", tierPrefix},
		{"dune", "Dune: Part Two", tierPrefix},
		{"part two", "Dune: Part Two", tierWordPrefix},
		// A prefix, because "men" really is the start of "mentalist" -- typing
		// the first few letters should rank as a prefix, not a stray substring.
		{"men", "The Mentalist", tierPrefix},
		// Genuinely only a substring.
		{"tali", "The Mentalist", tierContains},
		{"the mentalist", "Breaking Bad", tierNoTextMatch},
	}
	for _, c := range cases {
		if got := titleTier(c.query, c.title); got != c.want {
			t.Errorf("%q vs %q: got %d want %d", c.query, c.title, got, c.want)
		}
	}
}

func TestRankPutsTheObviousAnswerFirst(t *testing.T) {
	// The real complaint: searching "the mentalist" surfaced "The Mentalists"
	// among the top results because TMDB ranks on popularity-weighted
	// relevance. Nothing is dropped -- a near-miss is sometimes what someone
	// wanted -- it just stops outranking the exact title.
	hits := []SearchHit{
		hit("The Mentalists"),
		hit("The Mentalist"),
		hit("Mentalist"),
		hit("The Mentalists"),
	}
	rankSearchHits("the mentalist", hits)
	got := titles(hits)
	if got[0] != "The Mentalist" || got[1] != "Mentalist" {
		t.Fatalf("exact matches should lead, got %v", got)
	}
	if len(got) != 4 {
		t.Fatalf("nothing may be dropped, got %v", got)
	}
}

func TestRankIsStableWithinATier(t *testing.T) {
	// Upstream order is TMDB's popularity ranking, and it still decides between
	// two equally good title matches.
	hits := []SearchHit{hit("Dune: Part Two"), hit("Dune: Part One")}
	rankSearchHits("dune", hits)
	if got := titles(hits); got[0] != "Dune: Part Two" {
		t.Fatalf("stability broken, got %v", got)
	}
}

func TestRankLeavesShortInputAlone(t *testing.T) {
	hits := []SearchHit{hit("B"), hit("A")}
	rankSearchHits("", hits)
	if got := titles(hits); got[0] != "B" {
		t.Fatalf("an empty query must not reorder, got %v", got)
	}
	single := []SearchHit{hit("only")}
	rankSearchHits("x", single)
	if single[0].Media.Title != "only" {
		t.Fatal("a single hit must survive")
	}
}
