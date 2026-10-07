package reading

import (
	"errors"
	"reflect"
	"strings"
	"testing"
	"testing/iotest"
)

func TestISBN13TurnsAnyISBNIntoAnISBN13OrNothing(t *testing.T) {
	for raw, want := range map[string]string{
		// Dark Matter, from its own edition: ISBN:9781101904220 and the ASIN 1101904224.
		"9781101904220": "9781101904220", "1101904224": "9781101904220",
		// Wikipedia's examples, one with an X check digit.
		"0306406152": "9780306406157", "080442957X": "9780804429573", "080442957x": "9780804429573",
		// How an export and a catalogue write them.
		`="9781101904220"`: "9781101904220", `=""`: "", "978-1-101-90422-0": "9781101904220",
		"ISBN 0-306-40615-2": "9780306406157", "isbn:9780306406157": "9780306406157", "  1101904224 ": "9781101904220",
		// Not ISBNs: a wrong check digit, a wrong length, words.
		"9781101904221": "", "1101904225": "", "12345": "", "": "", "not an isbn": "", "97811019042201": "",
		// An X is only a check digit of an ISBN-10.
		"978110190422X": "",
	} {
		if got := ISBN13(raw); got != want {
			t.Errorf("ISBN13(%q) = %q, want %q", raw, got, want)
		}
	}
	if got := ISBN10("9781101904220"); got != "1101904224" {
		t.Errorf("ISBN10 = %q", got)
	}
	if got := ISBN10("9780804429573"); got != "080442957x" {
		t.Errorf("ISBN10 with an X = %q", got)
	}
	if got := ISBN10("9791032305751"); got != "" {
		t.Errorf("a 979 number has no ISBN-10: %q", got)
	}
	if got := ISBNForms("1101904224"); !reflect.DeepEqual(got, []string{"9781101904220", "1101904224"}) {
		t.Errorf("forms = %v", got)
	}
	if got := ISBNForms("nope"); got != nil {
		t.Errorf("forms of nothing = %v", got)
	}
}

func TestGoodreadsMonthTakesTheMonthOfADate(t *testing.T) {
	for raw, want := range map[string]string{
		"2025/09/14": "2025-09", "2025-09-14": "2025-09", "2025/9/4": "2025-09", "2025/09": "2025-09", "2025-12": "2025-12",
		"": "", "2025": "", "2025/13/01": "", "2025/00/01": "", "1800/05/01": "", "14/09/2025": "", "soon": "",
	} {
		if got := GoodreadsMonth(raw); got != want {
			t.Errorf("GoodreadsMonth(%q) = %q, want %q", raw, got, want)
		}
	}
}

// The columns of a real export, in its order, with its quirks: a byte order mark, ISBNs
// wrapped as ="…", a rating of 0, series notes in the titles, shelves that are statuses.
const goodreadsSample = "\xef\xbb\xbf" + `Book Id,Title,Author,Author l-f,Additional Authors,ISBN,ISBN13,My Rating,Average Rating,Publisher,Binding,Number of Pages,Year Published,Original Publication Year,Date Read,Date Added,Bookshelves,Bookshelves with positions,Exclusive Shelf,My Review,Spoiler,Private Notes,Read Count,Owned Copies
1,"The Final Empire (Mistborn, #1)",Brandon Sanderson,"Sanderson, Brandon",,"=""0765311781""","=""9780765311788""",5,4.45,Tor,Paperback,541,2006,2006,2025/09/14,2025/01/02,"cosmere, favorites, read","cosmere (#1), favorites (#2), read (#3)",read,"A long review, with ""quotes"", that is private.",,A private note,2,0
2,Dark Matter,Blake Crouch,"Crouch, Blake","Jon Lindstrom (Narrator), Someone Else","=""""","=""""",0,4.09,Ballantine,Kindle,342,2016,2016,,2024/03/01,"to-read","to-read (#1)",to-read,,,,0,0
3,Recursion,Blake Crouch,"Crouch, Blake",,"=""1101904224""","=""""",4,4.2,,,,,,2023/05/06,2023/05/01,"currently-reading, sci-fi",,currently-reading,,,,0,0
,,,,,,,,,,,,,,,,,,,,,,,
4,,Nobody,"Nobody",,,,3,,,,,,,,,,,read,,,,1,0
5,Odd Book,Someone,,,,,6,9.9,,,,,,2025-13-45,,,,,,,,abc,
`

func TestParseGoodreadsReadsTheColumnsThePageUses(t *testing.T) {
	export, err := ParseGoodreads(strings.NewReader(goodreadsSample))
	if err != nil {
		t.Fatal(err)
	}
	if export.Total != 6 || export.Skipped != 2 || len(export.Rows) != 4 {
		t.Fatalf("total %d, skipped %d, rows %d", export.Total, export.Skipped, len(export.Rows))
	}
	want := []GoodreadsRow{
		{
			Title: "The Final Empire (Mistborn, #1)", Authors: []string{"Brandon Sanderson"},
			ISBNs: []string{"9780765311788"}, Rating: 5, Average: 4.45, Finished: "2025-09",
			Shelves: []string{"cosmere", "favorites"}, Status: "read", ReadCount: 2,
		},
		{
			Title: "Dark Matter", Authors: []string{"Blake Crouch", "Jon Lindstrom (Narrator)", "Someone Else"},
			Average: 4.09, Status: "to-read",
		},
		{
			Title: "Recursion", Authors: []string{"Blake Crouch"}, ISBNs: []string{"9781101904220"},
			Rating: 4, Average: 4.2, Finished: "2023-05", Shelves: []string{"sci-fi"}, Status: "currently-reading",
		},
		// A rating of 6, an average above 5, a date that is not one, a count that is not a
		// number: each is left out rather than believed.
		{Title: "Odd Book", Authors: []string{"Someone"}},
	}
	if !reflect.DeepEqual(export.Rows, want) {
		t.Fatalf("rows:\n got %+v\nwant %+v", export.Rows, want)
	}
}

func TestParseGoodreadsFindsColumnsByNameWhateverTheirOrder(t *testing.T) {
	export, err := ParseGoodreads(strings.NewReader("Exclusive Shelf,ISBN13,Title,My Rating,Extra\nread,=\"9780306406157\",A Book,3,whatever\n"))
	if err != nil || len(export.Rows) != 1 {
		t.Fatalf("%+v, %v", export, err)
	}
	row := export.Rows[0]
	if row.Title != "A Book" || row.Rating != 3 || row.Status != "read" || row.ReadCount != 1 || !reflect.DeepEqual(row.ISBNs, []string{"9780306406157"}) || len(row.Authors) != 0 {
		t.Fatalf("row = %+v", row)
	}
}

func TestParseGoodreadsStatusesAreNeverShelves(t *testing.T) {
	for _, test := range []struct {
		shelves, exclusive string
		wantShelves        []string
		wantStatus         string
	}{
		{"read, favorites", "read", []string{"favorites"}, "read"},
		{"to-read, read", "", nil, "read"},
		{"currently-reading, to-read, read, x", "", []string{"x"}, "currently-reading"},
		{"favorites", "to-read", []string{"favorites"}, "to-read"},
		{"a, b, a, , c", "", []string{"a", "b", "c"}, ""},
		{"", "my-custom-shelf", nil, ""},
		{"READ", "Read", nil, "read"},
	} {
		shelves, status := goodreadsShelves(test.shelves, test.exclusive)
		if !reflect.DeepEqual(shelves, test.wantShelves) || status != test.wantStatus {
			t.Errorf("shelves %q exclusive %q = %v, %q; want %v, %q", test.shelves, test.exclusive, shelves, status, test.wantShelves, test.wantStatus)
		}
	}
}

func TestParseGoodreadsRefusesWhatIsNotAnExportAndNeverRepeatsACell(t *testing.T) {
	for name, text := range map[string]string{
		"nothing":                               "",
		"no title column":                       "Name,Author\nx,y\n",
		"a title and nothing to say which book": "Title,Rating\nx,3\n",
	} {
		if _, err := ParseGoodreads(strings.NewReader(text)); !errors.Is(err, ErrNotGoodreads) {
			t.Errorf("%s: %v", name, err)
		}
	}
	// A file that cannot be read says so and nothing else; one with stray quotes is read
	// as far as it goes, the way a spreadsheet does.
	if _, err := ParseGoodreads(iotest.ErrReader(errors.New("secret title"))); !errors.Is(err, ErrNotGoodreads) {
		t.Errorf("an unreadable file: %v", err)
	}
	stray := strings.Join([]string{"Title,Author", "fine,fine", `"odd "quote" title,someone`, `"another`}, "\n")
	if _, err := ParseGoodreads(strings.NewReader(stray)); err != nil {
		t.Errorf("stray quotes: %v", err)
	}

	var big strings.Builder
	big.WriteString("Title,Author\n")
	for i := 0; i <= MaxGoodreadsRows; i++ {
		big.WriteString("b,a\n")
	}
	if _, err := ParseGoodreads(strings.NewReader(big.String())); !errors.Is(err, ErrGoodreadsTooBig) {
		t.Fatalf("more rows than the limit: %v", err)
	}
}

func TestCombineGoodreadsMakesOneRecordOfSeveralRows(t *testing.T) {
	rows := []GoodreadsRow{
		{Title: "x", Rating: 3, Finished: "2020-01", ReadCount: 1, Shelves: []string{"a", "b"}, Status: "read", Average: 4.1},
		{Title: "x", Rating: 5, Finished: "2024-06", ReadCount: 1, Shelves: []string{"b", "c"}, Status: "currently-reading"},
		{Title: "x", Rating: 0, Finished: "2022-02", ReadCount: 2, Status: "to-read", Average: 3.9},
	}
	got := CombineGoodreads(rows)
	want := GoodreadsRecord{Rating: 5, Average: 4.1, Finished: "2024-06", ReadCount: 4, Shelves: []string{"a", "b", "c"}, Status: "currently-reading"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("record = %+v, want %+v", got, want)
	}
	// The row read last has no rating: the first rated row's stands.
	got = CombineGoodreads([]GoodreadsRow{{Rating: 4, Finished: "2020-01"}, {Rating: 0, Finished: "2024-01"}})
	if got.Rating != 4 || got.Finished != "2024-01" {
		t.Fatalf("record = %+v", got)
	}
	// Nothing is dated: the first rated row leads.
	got = CombineGoodreads([]GoodreadsRow{{Title: "a"}, {Rating: 2}, {Rating: 5}})
	if got.Rating != 2 || got.Finished != "" {
		t.Fatalf("record = %+v", got)
	}
	if got := CombineGoodreads(nil); !reflect.DeepEqual(got, GoodreadsRecord{}) {
		t.Fatalf("no rows = %+v", got)
	}
}

func TestGoodreadsTitleDropsTheSeriesNoteAndMainTitleTheSubtitle(t *testing.T) {
	for title, want := range map[string]string{
		"The Final Empire (Mistborn, #1)":                          "The Final Empire",
		"Words of Radiance (The Stormlight Archive, #2)":           "Words of Radiance",
		"Mort (Discworld, Book 4)":                                 "Mort",
		"Harry Potter and the Sorcerer's Stone (Harry Potter, #1)": "Harry Potter and the Sorcerer's Stone",
		"Dark Matter":                      "Dark Matter",
		"Fahrenheit 451 (Special edition)": "Fahrenheit 451 (Special edition)",
		"(#1)":                             "(#1)",
	} {
		if got := GoodreadsTitle(title); got != want {
			t.Errorf("GoodreadsTitle(%q) = %q, want %q", title, got, want)
		}
	}
	for title, want := range map[string]string{
		"Dark Matter: A Novel":    "Dark Matter",
		"Morning Star - Book III": "Morning Star",
		"Dark Matter":             "Dark Matter",
		"Spider-Man: Blue":        "Spider-Man",
		"Catch-22":                "Catch-22",
		": odd":                   ": odd",
	} {
		if got := MainTitle(title); got != want {
			t.Errorf("MainTitle(%q) = %q, want %q", title, got, want)
		}
	}
}
