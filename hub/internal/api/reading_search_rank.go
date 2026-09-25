package api

import (
	"sort"
	"strings"
	"unicode"
)

type rankedReadingItem struct {
	item  ReadingItem
	score int
}

// BookKeeprr searches several metadata providers, some of which return titles
// matching only an author or series subtitle. Keep those reachable, but don't
// mix them into the first screen of close title matches.
func rankReadingSearch(query string, items []ReadingItem) ([]ReadingItem, []ReadingItem) {
	queryWords := readingSearchWords(query)
	queryPhrase := strings.Join(queryWords, " ")
	queryCompact := strings.Join(queryWords, "")
	ranked := make([]rankedReadingItem, 0, len(items))
	maxScore := 0
	for _, item := range items {
		score := readingSearchScore(queryWords, queryPhrase, queryCompact, item)
		ranked = append(ranked, rankedReadingItem{item: item, score: score})
		if score > maxScore {
			maxScore = score
		}
	}
	sort.SliceStable(ranked, func(i, j int) bool { return ranked[i].score > ranked[j].score })
	threshold := 50
	if maxScore >= 120 {
		// An exact title is available. Other books merely mentioning the words
		// belong behind the broader-results action.
		threshold = 110
	} else if maxScore < threshold {
		threshold = 30
	}
	close := make([]ReadingItem, 0, len(ranked))
	broader := make([]ReadingItem, 0, len(ranked))
	for _, entry := range ranked {
		if entry.score >= threshold {
			close = append(close, entry.item)
		} else {
			broader = append(broader, entry.item)
		}
	}
	return close, broader
}

func readingSearchScore(queryWords []string, phrase, compact string, item ReadingItem) int {
	if phrase == "" {
		return 0
	}
	fullWords := readingSearchWords(item.Title)
	full := strings.Join(fullWords, " ")
	primaryTitle, _, _ := strings.Cut(item.Title, ":")
	primaryWords := readingSearchWords(primaryTitle)
	primary := strings.Join(primaryWords, " ")
	primaryCompact := strings.Join(primaryWords, "")
	authorWords := readingSearchWords(item.Author)
	switch {
	case full == phrase:
		return 130
	case primary == phrase:
		return 120
	case readingContainsAll(append(append([]string{}, fullWords...), authorWords...), queryWords) &&
		readingContainsAny(fullWords, queryWords) && readingContainsAny(authorWords, queryWords):
		return 110
	case strings.HasPrefix(primary, phrase+" "):
		return 105
	case strings.Contains(" "+primary+" ", " "+phrase+" "):
		return 90
	case len([]rune(compact)) >= 4 && strings.Contains(primaryCompact, compact):
		return 80
	case strings.Contains(" "+full+" ", " "+phrase+" "):
		return 65
	case readingContainsAll(primaryWords, queryWords):
		return 55
	case len([]rune(compact)) >= 4 && strings.Contains(strings.Join(fullWords, ""), compact):
		return 35
	case strings.Contains(" "+strings.Join(authorWords, " ")+" ", " "+phrase+" "):
		return 30
	default:
		return 0
	}
}

func readingContainsAny(haystack, needles []string) bool {
	for _, needle := range needles {
		for _, word := range haystack {
			if word == needle {
				return true
			}
		}
	}
	return false
}

func readingSearchWords(value string) []string {
	return strings.FieldsFunc(strings.ToLower(value), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsNumber(r)
	})
}

func readingContainsAll(haystack, needles []string) bool {
	if len(needles) == 0 {
		return false
	}
	seen := make(map[string]bool, len(haystack))
	for _, word := range haystack {
		seen[word] = true
	}
	for _, word := range needles {
		if !seen[word] {
			return false
		}
	}
	return true
}
