package api

import (
	"reflect"
	"testing"

	"ayaneohub/internal/adapters/storyteller"
)

func TestStorytellerPeopleAreWritersAsFirstLast(t *testing.T) {
	// Dark Matter's audiobook listed its narrator as an author.
	darkMatter := storyteller.Book{
		Authors:   []storyteller.Creator{{Name: "Blake Crouch"}, {Name: "Jon Lindstrom"}},
		Narrators: []storyteller.Creator{{Name: "Jon Lindstrom"}},
	}
	if got := storytellerPeople(darkMatter); !reflect.DeepEqual(got, []string{"Blake Crouch"}) {
		t.Fatalf("narrator kept as author: %v", got)
	}
	byRole := storyteller.Book{Authors: []storyteller.Creator{{Name: "Blake Crouch"}, {Name: "Jon Lindstrom", Role: "nrt"}}}
	if got := storytellerPeople(byRole); !reflect.DeepEqual(got, []string{"Blake Crouch"}) {
		t.Fatalf("narrator role kept: %v", got)
	}
	// Light Bringer's EPUB filed its author as "Brown, Pierce".
	lightBringer := storyteller.Book{Authors: []storyteller.Creator{{Name: "Brown, Pierce"}}}
	if got := storytellerPeople(lightBringer); !reflect.DeepEqual(got, []string{"Pierce Brown"}) {
		t.Fatalf("name not turned round: %v", got)
	}
	onlyNarrator := storyteller.Book{Authors: []storyteller.Creator{{Name: "Jon Lindstrom", Role: "nrt"}}}
	if got := storytellerPeople(onlyNarrator); len(got) != 1 {
		t.Fatalf("a narrator-only book lost its only name: %v", got)
	}
}

func TestStorytellerFileTitleReadsAuthorSeriesNumberTitle(t *testing.T) {
	authors := []string{"Brandon Sanderson"}
	clean, series, position, ok := storytellerFileTitle("Brandon Sanderson - Mistborn 02 - Well of Ascension", authors)
	if !ok || clean != "Well of Ascension" || series != "Mistborn" || position != 2 {
		t.Fatalf("got %q %q %v %v", clean, series, position, ok)
	}
	for _, title := range []string{"Brandon Sanderson - Mistborn Book 2 - Well of Ascension", "Brandon Sanderson - Mistborn #2 - Well of Ascension"} {
		if clean, series, position, ok := storytellerFileTitle(title, authors); !ok || clean != "Well of Ascension" || series != "Mistborn" || position != 2 {
			t.Fatalf("%s: got %q %q %v", title, clean, series, position)
		}
	}
	if clean, series, _, ok := storytellerFileTitle("Brandon Sanderson - Elantris", authors); !ok || clean != "Elantris" || series != "" {
		t.Fatalf("two parts: %q %q %v", clean, series, ok)
	}
	// A real title with a dash is not a file name: its first part is no author.
	if _, _, _, ok := storytellerFileTitle("Star Wars - Thrawn - Alliances", authors); ok {
		t.Fatal("a title with dashes was rewritten")
	}
}

func TestLeadingArticleDoesNotSeparateTitles(t *testing.T) {
	if withoutLeadingArticle("the well of ascension") != withoutLeadingArticle("well of ascension") {
		t.Fatal("article mismatch")
	}
	if withoutLeadingArticle("the") != "the" {
		t.Fatal("a title that is only an article was emptied")
	}
}

func TestReadingFixturesAreRecognised(t *testing.T) {
	if !isReadingFixture(storyteller.Book{Authors: []storyteller.Creator{{Name: "Lab Author"}}}) {
		t.Fatal("fixture author")
	}
	if !isReadingFixture(storyteller.Book{Identifiers: []storyteller.Identifier{{Value: "urn:pocketds:fixture:clockwork-island"}}}) {
		t.Fatal("fixture identifier")
	}
	if isReadingFixture(storyteller.Book{Authors: []storyteller.Creator{{Name: "Lab Author"}, {Name: "Pierce Brown"}}}) {
		t.Fatal("a real co-written book was hidden")
	}
}

// The library on 2026-10-01: Mistborn 1 and 3 as ebooks with series metadata,
// book 2 as an audiobook named after its file, and a lab fixture.
func TestMistbornGroupsAsOneSeriesOfThree(t *testing.T) {
	sanderson := []storyteller.Creator{{Name: "Brandon Sanderson"}}
	books := []storyteller.Book{
		{ID: 1, Title: "Mistborn: The Final Empire", Authors: sanderson, Ebook: &storyteller.Ebook{}, Series: []storyteller.Series{{Name: "Mistborn", Position: 1}}},
		{ID: 2, Title: "Brandon Sanderson - Mistborn 02 - Well of Ascension", Authors: sanderson, Audiobook: &storyteller.Audiobook{}},
		{ID: 3, Title: "The Hero of Ages", Authors: sanderson, Ebook: &storyteller.Ebook{}, Series: []storyteller.Series{{Name: "Mistborn", Position: 3}}},
		{ID: 4, Title: "The Clockwork Island", Authors: []storyteller.Creator{{Name: "Lab Author"}}},
	}
	standalone, groups := (&Server{}).storytellerShelfGroups(books, true)
	if len(standalone) != 0 || len(groups) != 1 || len(groups[0].books) != 3 {
		t.Fatalf("standalone %d, groups %d", len(standalone), len(groups))
	}
	if got := groups[0].books[1].Title; got != "Well of Ascension" {
		t.Fatalf("file-name title kept: %q", got)
	}
	if distinctStorytellerBooks(groups[0].books) != 3 {
		t.Fatal("book count")
	}
}

func TestSeriesEditionsMergeIntoOneBook(t *testing.T) {
	items := []ReadingSectionItem{{SourceItemID: "10", Title: "Golden Son", Number: "2", Kind: "audiobook", Formats: []string{"audiobook"}}}
	ebook := storyteller.Book{ID: 11, Title: "Golden Son", Series: []storyteller.Series{{Name: "Red Rising", Position: 2}}}
	same := sameSeriesBook(items, ebook)
	if same != 0 {
		t.Fatalf("same book not found: %d", same)
	}
	mergeSeriesEdition(&items[0], ReadingSectionItem{SourceItemID: "11", Title: "Golden Son", Number: "2", Kind: "book", Formats: []string{"ebook"}})
	if items[0].SourceItemID != "11" || items[0].Kind != "book" || !reflect.DeepEqual(items[0].Formats, []string{"audiobook", "ebook"}) {
		t.Fatalf("merged: %+v", items[0])
	}
}
