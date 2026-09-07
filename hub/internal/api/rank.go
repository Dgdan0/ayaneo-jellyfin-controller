package api

import (
	"sort"
	"strings"
	"unicode"
)

// Search results, reordered so the obvious answer is first.
//
// TMDB's own ordering is popularity-weighted relevance, which is usually fine
// and occasionally puts a 2019 documentary called "The Mentalists" above the
// series you actually typed. Nothing is removed -- a near-miss is sometimes
// exactly what someone wanted, and silently hiding it is worse than showing it
// second.

// normalizeTitle reduces a title to what a person means by it.
//
// Lower-cased, punctuation dropped, whitespace collapsed, and a leading article
// removed -- so "The Mentalist" and "Mentalist" compare equal, which is the
// whole point: both are exact answers to "the mentalist" and both should sort
// above "The Mentalists".
func normalizeTitle(title string) string {
	var b strings.Builder
	lastWasSpace := true
	for _, r := range strings.ToLower(title) {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			b.WriteRune(r)
			lastWasSpace = false
		case unicode.IsSpace(r) || r == '-' || r == ':' || r == '.':
			if !lastWasSpace {
				b.WriteRune(' ')
				lastWasSpace = true
			}
		}
		// Everything else -- apostrophes, brackets, ampersands -- is dropped
		// without inserting a gap, so "don't" stays one word.
	}
	out := strings.TrimSpace(b.String())
	for _, article := range []string{"the ", "a ", "an "} {
		if rest := strings.TrimPrefix(out, article); rest != out {
			return rest
		}
	}
	return out
}

// titleTier is how well a title answers the query. Lower is better.
const (
	tierExact       = 0
	tierPrefix      = 1
	tierWordPrefix  = 2
	tierContains    = 3
	tierNoTextMatch = 4
)

func titleTier(query, title string) int {
	q := normalizeTitle(query)
	t := normalizeTitle(title)
	if q == "" {
		return tierNoTextMatch
	}
	switch {
	case t == q:
		return tierExact
	case strings.HasPrefix(t, q):
		return tierPrefix
	// A whole-word match somewhere inside, e.g. "dune" in "dune part two".
	case strings.Contains(" "+t+" ", " "+q+" "):
		return tierWordPrefix
	case strings.Contains(t, q):
		return tierContains
	}
	return tierNoTextMatch
}

// rankSearchHits sorts in place, stably.
//
// Stability is what keeps this honest: within a tier the upstream order is
// preserved, so TMDB's popularity ranking still decides between two equally
// good title matches. This only overrides the cases where a worse title match
// was winning on popularity alone.
func rankSearchHits(query string, hits []SearchHit) {
	if strings.TrimSpace(query) == "" || len(hits) < 2 {
		return
	}
	// Scored copies rather than a map keyed on element addresses: sorting moves
	// the contents, so any index recorded against &hits[i] is wrong the moment
	// the first swap happens.
	type scored struct {
		tier int
		hit  SearchHit
	}
	list := make([]scored, len(hits))
	for i := range hits {
		list[i] = scored{tier: titleTier(query, hits[i].Media.Title), hit: hits[i]}
	}
	sort.SliceStable(list, func(i, j int) bool { return list[i].tier < list[j].tier })
	for i := range list {
		hits[i] = list[i].hit
	}
}
