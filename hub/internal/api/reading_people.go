package api

import (
	"regexp"
	"strconv"
	"strings"

	"ayaneohub/internal/adapters/storyteller"
	readingdomain "ayaneohub/internal/reading"
)

// storytellerPeople is who wrote a book, each as "First Last".
//
// It is the one source of a Storyteller book's authors: cards, series,
// matching and the author shelves all read it. Storyteller can list an
// audiobook's narrator among the authors (Jon Lindstrom on Dark Matter put the
// book under two authors), so anyone also listed as a narrator, or carrying a
// narrator role, is dropped. A book whose only people are narrators keeps them
// rather than becoming "Unknown author".
func storytellerPeople(book storyteller.Book) []string {
	narrators := map[string]bool{}
	for _, name := range creatorNames(book.Narrators) {
		narrators[normalizeReadingIdentity(name)] = true
	}
	writers := make([]storyteller.Creator, 0, len(book.Authors))
	for _, author := range book.Authors {
		if narratorRole(author.Role) {
			continue
		}
		writers = append(writers, author)
	}
	names := creatorNames(writers)
	out := make([]string, 0, len(names))
	for _, name := range names {
		if !narrators[normalizeReadingIdentity(name)] {
			out = append(out, name)
		}
	}
	if len(out) == 0 {
		return creatorNames(book.Authors)
	}
	return out
}

func narratorRole(role string) bool {
	role = strings.ToLower(strings.TrimSpace(role))
	return role == "nrt" || strings.Contains(role, "narrat") || role == "reader"
}

var fileTitleSeries = regexp.MustCompile(`(?i)^(.*?)[\s,]*(?:book|vol\.?|volume|no\.?|#)?\s*#?(\d+(?:\.\d+)?)$`)

// storytellerFileTitle reads a title that is really a file name, such as
// "Brandon Sanderson - Mistborn 02 - Well of Ascension". An audiobook added
// without metadata kept that as its title and stood outside its series, while
// the series listed the same book as missing. The first part must be one of
// the book's authors, so an ordinary title that contains " - " is left alone.
func storytellerFileTitle(title string, authors []string) (clean, series string, position float64, ok bool) {
	parts := strings.Split(title, " - ")
	if len(parts) < 2 || len(parts) > 3 {
		return "", "", 0, false
	}
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
		if parts[i] == "" {
			return "", "", 0, false
		}
	}
	lead := normalizeReadingIdentity(parts[0])
	byAuthor := false
	for _, author := range authors {
		if lead != "" && normalizeReadingIdentity(author) == lead {
			byAuthor = true
			break
		}
	}
	if !byAuthor {
		return "", "", 0, false
	}
	if len(parts) == 2 {
		return parts[1], "", 0, true
	}
	series = parts[1]
	if match := fileTitleSeries.FindStringSubmatch(series); match != nil && strings.TrimSpace(match[1]) != "" {
		if value, err := strconv.ParseFloat(match[2], 64); err == nil {
			series, position = strings.TrimSpace(match[1]), value
		}
	}
	return parts[2], series, position, true
}

// withoutLeadingArticle lets "Well of Ascension" find "The Well of Ascension";
// takes and returns normalizeReadingIdentity output.
func withoutLeadingArticle(normalized string) string {
	for _, article := range []string{"the ", "a ", "an "} {
		if rest, found := strings.CutPrefix(normalized, article); found && rest != "" {
			return rest
		}
	}
	return normalized
}

// isReadingFixture reports a generated reading-lab book (see
// readingdomain.FixtureAuthor). They stay openable by id for device tests but
// are kept out of library shelves and author lists.
func isReadingFixture(book storyteller.Book) bool {
	for _, identifier := range book.Identifiers {
		value := identifier.Value
		if value == "" {
			value = identifier.Identifier
		}
		if strings.HasPrefix(strings.ToLower(strings.TrimSpace(value)), readingdomain.FixtureIdentifierPrefix) {
			return true
		}
	}
	authors := creatorNames(book.Authors)
	return len(authors) == 1 && authors[0] == readingdomain.FixtureAuthor
}

// seriesBookKey is what makes two editions one book inside a series: the
// position when there is one, otherwise the title without a leading article.
func seriesBookKey(number, title string) string {
	if number != "" {
		return "n:" + number
	}
	return "t:" + withoutLeadingArticle(normalizeReadingIdentity(title))
}

// sameSeriesBook finds the item already holding another edition of book.
func sameSeriesBook(items []ReadingSectionItem, book storyteller.Book) int {
	key := seriesBookKey(storytellerSeriesNumber(book), book.Title)
	for index, item := range items {
		if seriesBookKey(item.Number, item.Title) == key {
			return index
		}
	}
	return -1
}

// mergeSeriesEdition folds an edition into the item for its book. The edition
// someone has been reading or listening to leads, so opening the item goes on
// from there; an ebook beats an audiobook otherwise.
func mergeSeriesEdition(target *ReadingSectionItem, edition ReadingSectionItem) {
	target.Formats = mergeUnique(target.Formats, edition.Formats)
	leads := (target.Progress == nil && edition.Progress != nil) ||
		(target.Progress == nil && edition.Progress == nil && target.Kind == "audiobook" && edition.Kind != "audiobook")
	if leads {
		target.SourceItemID, target.WorkID, target.Progress = edition.SourceItemID, edition.WorkID, edition.Progress
		target.Artwork = edition.Artwork
	}
	if edition.PageCount > target.PageCount {
		target.PageCount = edition.PageCount
	}
	for _, format := range target.Formats {
		if format == "ebook" || format == "readaloud" {
			target.Kind = "book"
		}
	}
}

// distinctStorytellerBooks counts books, not editions: Mistborn with an ebook
// and an audiobook of book 2 is still three books.
func distinctStorytellerBooks(books []storyteller.Book) int {
	seen := map[string]bool{}
	for _, book := range books {
		seen[seriesBookKey(storytellerSeriesNumber(book), book.Title)] = true
	}
	return len(seen)
}
