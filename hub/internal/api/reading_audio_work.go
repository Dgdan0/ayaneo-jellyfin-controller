package api

// One audiobook to a work. Storyteller can hold one book twice: Dark Matter is a
// book with its ebook, its audiobook and its read-along edition, and a second
// book that is the same audio again with nothing else, and each book has its own
// place. The hub merges them into one work, and a work that offers both audiobooks
// has two places to follow, two sets of tracks, and a read-along edition aligned
// to only one of them. So the work offers one: the copy that also has the
// read-along edition, else the one that also has the ebook.
//
// Only a copy is left out. Another narrator's reading of the book, or a shorter
// one by the same narrator, is another audiobook and stays. The routes of the book
// that is left out are untouched: an app that was shown it before still reaches
// its files, its archive and its place.

import (
	"sort"
	"strings"
)

// oneAudiobookPerWork returns the editions of a merged work with the copies of
// an audiobook folded into one. It does not change the list it is given.
func oneAudiobookPerWork(editions []ReadingEdition) []ReadingEdition {
	var audiobooks []int // indexes into editions
	for i, edition := range editions {
		if isStorytellerAudiobook(edition) {
			audiobooks = append(audiobooks, i)
		}
	}
	if len(audiobooks) < 2 {
		return editions
	}

	// What else each book offers, to choose between copies.
	kindsOf := map[string]map[string]bool{}
	for _, edition := range editions {
		if edition.Source != "storyteller" {
			continue
		}
		if kindsOf[edition.SourceItemID] == nil {
			kindsOf[edition.SourceItemID] = map[string]bool{}
		}
		kindsOf[edition.SourceItemID][edition.Kind] = true
	}

	// Copies of one recording: the same narrators, and lengths that agree.
	var groups [][]int
	for _, index := range audiobooks {
		placed := false
		for g, group := range groups {
			if sameRecording(editions[group[0]], editions[index]) {
				groups[g] = append(group, index)
				placed = true
				break
			}
		}
		if !placed {
			groups = append(groups, []int{index})
		}
	}

	dropped := map[int]bool{}
	for _, group := range groups {
		if len(group) < 2 {
			continue
		}
		keep := group[0]
		best := copyRank(kindsOf[editions[keep].SourceItemID])
		for _, index := range group[1:] {
			if rank := copyRank(kindsOf[editions[index].SourceItemID]); rank > best {
				keep, best = index, rank
			}
		}
		for _, index := range group {
			if index != keep {
				dropped[index] = true
			}
		}
	}
	if len(dropped) == 0 {
		return editions
	}
	out := make([]ReadingEdition, 0, len(editions)-len(dropped))
	for i, edition := range editions {
		if !dropped[i] {
			out = append(out, edition)
		}
	}
	return out
}

func isStorytellerAudiobook(edition ReadingEdition) bool {
	return edition.Source == "storyteller" && edition.Kind == "audiobook"
}

// copyRank says how good a copy of an audiobook is to open: one whose book also has
// the read-along edition, then one whose book has the ebook.
func copyRank(kinds map[string]bool) int {
	switch {
	case kinds["readaloud"]:
		return 2
	case kinds["ebook"]:
		return 1
	}
	return 0
}

// sameRecording says two audiobook editions are one recording: read by the same
// people, and as long as each other (within two percent) where both lengths are
// known. A narrator that is not named is not known to be the same.
func sameRecording(a, b ReadingEdition) bool {
	left, right := narratorSet(a.Narrator), narratorSet(b.Narrator)
	if left == "" || left != right {
		return false
	}
	if a.DurationMS > 0 && b.DurationMS > 0 {
		difference := a.DurationMS - b.DurationMS
		if difference < 0 {
			difference = -difference
		}
		return difference*50 <= max(a.DurationMS, b.DurationMS)
	}
	return true
}

// narratorSet is the narrators of an edition as a set, whatever order and case
// they were listed in.
func narratorSet(narrator string) string {
	var names []string
	for _, name := range strings.Split(narrator, ",") {
		if identity := normalizeReadingIdentity(name); identity != "" {
			names = append(names, identity)
		}
	}
	sort.Strings(names)
	return strings.Join(names, "|")
}
