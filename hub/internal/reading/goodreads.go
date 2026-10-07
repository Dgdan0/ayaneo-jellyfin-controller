package reading

// The owner's Goodreads export (My Books > Import and export): their rating, shelves,
// read dates and read count for each book. Goodreads closed its public API in 2020, so
// the file is the only honest way to have this data. Everything here is pure: it reads
// bytes and says what a row is. Which work a row belongs to, and where it is kept, is
// the hub's (internal/api/reading_goodreads.go).
//
// The file is private. Only the columns the book page uses are read; reviews, private
// notes and the rest are never looked at, and no error here repeats a cell.

import (
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"
)

var (
	// ErrNotGoodreads is a file that is not a Goodreads export: no Title column, or
	// nothing that could say which book a row is.
	ErrNotGoodreads = errors.New("the file is not a Goodreads export")
	// ErrGoodreadsTooBig is a file with more rows than any library has.
	ErrGoodreadsTooBig = errors.New("the Goodreads export holds too many rows")
)

// readFailure is the file's reader failing partway (a body over its limit, a dropped
// connection). It says so and no more, and keeps the cause for whoever must tell the
// two apart.
type readFailure struct{ cause error }

func (readFailure) Error() string   { return "the file could not be read" }
func (r readFailure) Unwrap() error { return r.cause }

// MaxGoodreadsRows bounds what one import can make the hub hold.
const MaxGoodreadsRows = 20_000

// The three shelves that are statuses, not tags, strongest first.
const (
	StatusCurrentlyReading = "currently-reading"
	StatusRead             = "read"
	StatusToRead           = "to-read"
)

// goodreadsStatuses is in the order a book with several of them takes: a book being
// read again is being read, whatever else it has been.
var goodreadsStatuses = []string{StatusCurrentlyReading, StatusRead, StatusToRead}

// GoodreadsRow is what one row of the export says that the book page uses.
type GoodreadsRow struct {
	Title string
	// Authors are the Author column and then Additional Authors, as written.
	Authors []string
	// ISBNs are the row's ISBN and ISBN13, each as an ISBN-13, de-duplicated. A
	// number whose check digit is wrong is no ISBN and is left out.
	ISBNs []string
	// Rating is My Rating, 1 to 5; 0 when the book is not rated.
	Rating int
	// Average is Goodreads' average rating of the book, 0 when absent.
	Average float64
	// Finished is Date Read as "YYYY-MM", or "".
	Finished  string
	Shelves   []string
	Status    string
	ReadCount int
}

// GoodreadsExport is every usable row of a file.
type GoodreadsExport struct {
	Rows []GoodreadsRow
	// Total counts the data rows in the file; the rows with no title are counted
	// there and in Skipped and are not in Rows.
	Total   int
	Skipped int
}

// ParseGoodreads reads an export. The columns are found by their header, so their
// order does not matter and an export with more columns is fine.
func ParseGoodreads(source io.Reader) (GoodreadsExport, error) {
	reader := csv.NewReader(source)
	reader.LazyQuotes = true
	reader.FieldsPerRecord = -1
	header, err := reader.Read()
	if err != nil {
		return GoodreadsExport{}, ErrNotGoodreads
	}
	column := map[string]int{}
	for i, name := range header {
		name = strings.ToLower(strings.TrimSpace(strings.TrimPrefix(name, "\xef\xbb\xbf")))
		if _, seen := column[name]; !seen {
			column[name] = i
		}
	}
	titleAt, hasTitle := column["title"]
	_, hasAuthor := column["author"]
	_, hasISBN := column["isbn"]
	_, hasISBN13 := column["isbn13"]
	if !hasTitle || !(hasAuthor || hasISBN || hasISBN13) {
		return GoodreadsExport{}, ErrNotGoodreads
	}
	field := func(record []string, name string) string {
		at, ok := column[name]
		if !ok || at >= len(record) {
			return ""
		}
		return strings.TrimSpace(record[at])
	}

	var export GoodreadsExport
	for {
		record, err := reader.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			var parse *csv.ParseError
			if errors.As(err, &parse) {
				// The line, never the text of it.
				return GoodreadsExport{}, fmt.Errorf("the file is not readable CSV (line %d)", parse.Line)
			}
			return GoodreadsExport{}, readFailure{err}
		}
		if len(record) == 1 && strings.TrimSpace(record[0]) == "" {
			continue // a blank line
		}
		export.Total++
		if export.Total > MaxGoodreadsRows {
			return GoodreadsExport{}, ErrGoodreadsTooBig
		}
		if titleAt >= len(record) || strings.TrimSpace(record[titleAt]) == "" {
			export.Skipped++
			continue
		}
		row := GoodreadsRow{Title: clip(field(record, "title"), 300)}
		for _, name := range append([]string{field(record, "author")}, strings.Split(field(record, "additional authors"), ",")...) {
			if name = clip(strings.TrimSpace(name), 200); name != "" {
				row.Authors = append(row.Authors, name)
			}
		}
		for _, raw := range []string{field(record, "isbn13"), field(record, "isbn")} {
			if isbn := ISBN13(raw); isbn != "" && !contains(row.ISBNs, isbn) {
				row.ISBNs = append(row.ISBNs, isbn)
			}
		}
		if rating, err := strconv.Atoi(field(record, "my rating")); err == nil && rating >= 1 && rating <= 5 {
			row.Rating = rating
		}
		if average, err := strconv.ParseFloat(field(record, "average rating"), 64); err == nil && average > 0 && average <= 5 {
			row.Average = average
		}
		row.Finished = GoodreadsMonth(field(record, "date read"))
		if count, err := strconv.Atoi(field(record, "read count")); err == nil && count > 0 && count < 1000 {
			row.ReadCount = count
		}
		row.Shelves, row.Status = goodreadsShelves(field(record, "bookshelves"), field(record, "exclusive shelf"))
		if row.Status == StatusRead && row.ReadCount == 0 {
			row.ReadCount = 1
		}
		export.Rows = append(export.Rows, row)
	}
	return export, nil
}

// goodreadsShelves splits Bookshelves into tags and the status. Exclusive Shelf is the
// status when it is one of the three; otherwise the strongest of them among the
// shelves. The statuses are never tags.
func goodreadsShelves(bookshelves, exclusive string) (shelves []string, status string) {
	named := map[string]bool{}
	for _, shelf := range strings.Split(bookshelves, ",") {
		shelf = clip(strings.TrimSpace(shelf), 40)
		if shelf == "" {
			continue
		}
		lower := strings.ToLower(shelf)
		isStatus := false
		for _, candidate := range goodreadsStatuses {
			isStatus = isStatus || lower == candidate
		}
		if isStatus {
			named[lower] = true
		} else if !contains(shelves, shelf) && len(shelves) < 20 {
			shelves = append(shelves, shelf)
		}
	}
	exclusive = strings.ToLower(strings.TrimSpace(exclusive))
	for _, candidate := range goodreadsStatuses {
		if exclusive == candidate {
			return shelves, candidate
		}
	}
	for _, candidate := range goodreadsStatuses {
		if named[candidate] {
			return shelves, candidate
		}
	}
	return shelves, ""
}

var goodreadsDate = regexp.MustCompile(`^(\d{4})[/-](\d{1,2})(?:[/-]\d{1,2})?$`)

// GoodreadsMonth is a Date Read ("2025/09/14", "2025-09-14", "2025/09") as "YYYY-MM",
// or "" for anything else, a year alone included.
func GoodreadsMonth(raw string) string {
	match := goodreadsDate.FindStringSubmatch(strings.TrimSpace(raw))
	if match == nil {
		return ""
	}
	year, _ := strconv.Atoi(match[1])
	month, _ := strconv.Atoi(match[2])
	if year < 1900 || month < 1 || month > 12 {
		return ""
	}
	return fmt.Sprintf("%04d-%02d", year, month)
}

// GoodreadsRecord is what several rows of one book come to.
type GoodreadsRecord struct {
	Rating    int      `json:"rating,omitempty"`
	Average   float64  `json:"average,omitempty"`
	Finished  string   `json:"finished,omitempty"`
	ReadCount int      `json:"readCount,omitempty"`
	Shelves   []string `json:"shelves,omitempty"`
	Status    string   `json:"status,omitempty"`
}

// CombineGoodreads is one book's record from its rows (two editions of it, say). The
// rating and the finish date come from the row read last (the first rated row when no
// row has a date), the rating from the first rated row if that one has none; the read
// counts add up; the shelves are the union in the order met; the status is the
// strongest of the rows'.
func CombineGoodreads(rows []GoodreadsRow) GoodreadsRecord {
	var record GoodreadsRecord
	if len(rows) == 0 {
		return record
	}
	lead := -1
	for i, row := range rows {
		if row.Finished != "" && (lead < 0 || row.Finished > rows[lead].Finished) {
			lead = i
		}
	}
	firstRated := -1
	for i, row := range rows {
		if row.Rating > 0 && firstRated < 0 {
			firstRated = i
		}
	}
	if lead < 0 {
		lead = max(firstRated, 0)
	}
	record.Finished = rows[lead].Finished
	record.Rating = rows[lead].Rating
	if record.Rating == 0 && firstRated >= 0 {
		record.Rating = rows[firstRated].Rating
	}
	for _, row := range rows {
		record.ReadCount += row.ReadCount
		if record.Average == 0 {
			record.Average = row.Average
		}
		for _, shelf := range row.Shelves {
			if !contains(record.Shelves, shelf) && len(record.Shelves) < 20 {
				record.Shelves = append(record.Shelves, shelf)
			}
		}
	}
	for _, status := range goodreadsStatuses {
		for _, row := range rows {
			if record.Status == "" && row.Status == status {
				record.Status = status
			}
		}
	}
	return record
}

var (
	// "(Mistborn, #1)", "(The Stormlight Archive, #2)", "(Discworld, Book 3)".
	goodreadsSeriesNote = regexp.MustCompile(`(?i)\s*\([^()]*(?:#\s*\d|\b(?:book|vol\.?|volume)\s*\d)[^()]*\)\s*$`)
	subtitleBreak       = regexp.MustCompile(`\s*(?::|\s[-–—]\s)\s*`)
)

// GoodreadsTitle is a title without Goodreads' series note at its end, the way the
// hub's own titles are written.
func GoodreadsTitle(title string) string {
	clean := strings.TrimSpace(goodreadsSeriesNote.ReplaceAllString(title, ""))
	if clean == "" {
		return strings.TrimSpace(title)
	}
	return clean
}

// MainTitle is what comes before a subtitle ("Dark Matter: A Novel" is "Dark
// Matter"); a title with none is itself.
func MainTitle(title string) string {
	if at := subtitleBreak.FindStringIndex(title); at != nil && at[0] > 0 {
		return strings.TrimSpace(title[:at[0]])
	}
	return strings.TrimSpace(title)
}

// ISBN13 is an ISBN-10 or ISBN-13, in any dress (hyphens, spaces, the ="…" wrapper an
// export puts round it), as the ISBN-13, or "" when it is not an ISBN: wrong length or
// a check digit that does not fit.
func ISBN13(raw string) string {
	digits := isbnDigits(raw)
	switch len(digits) {
	case 13:
		if digits[12] == 'x' || isbnCheck13(digits[:12]) != digits[12] {
			return ""
		}
		return digits
	case 10:
		if isbnCheck10(digits[:9]) != digits[9] {
			return ""
		}
		body := "978" + digits[:9]
		return body + string(isbnCheck13(body))
	}
	return ""
}

// ISBN10 is the ISBN-10 of an ISBN-13 that begins 978, else "".
func ISBN10(isbn13 string) string {
	if len(isbn13) != 13 || !strings.HasPrefix(isbn13, "978") || ISBN13(isbn13) == "" {
		return ""
	}
	body := isbn13[3:12]
	return body + string(isbnCheck10(body))
}

// ISBNForms are the spellings the hub may hold a number under: the ISBN-13 and, when
// there is one, the ISBN-10.
func ISBNForms(raw string) []string {
	isbn := ISBN13(raw)
	if isbn == "" {
		return nil
	}
	if ten := ISBN10(isbn); ten != "" {
		return []string{isbn, ten}
	}
	return []string{isbn}
}

// isbnDigits keeps the digits and an X, lower case, after the "ISBN" a number may carry.
func isbnDigits(raw string) string {
	raw = strings.TrimSpace(raw)
	raw = strings.TrimPrefix(raw, "=")
	raw = strings.Trim(raw, `"'`)
	if len(raw) >= 4 && strings.EqualFold(raw[:4], "isbn") {
		raw = raw[4:]
	}
	return strings.Map(func(r rune) rune {
		switch {
		case r >= '0' && r <= '9':
			return r
		case r == 'x' || r == 'X':
			return 'x'
		case r == '-' || r == ' ' || r == ':' || r == ' ':
			return -1
		}
		return -2
	}, raw)
}

func isbnCheck13(first12 string) byte {
	sum := 0
	for i := 0; i < 12; i++ {
		digit := int(first12[i] - '0')
		if i%2 == 1 {
			digit *= 3
		}
		sum += digit
	}
	return byte('0' + (10-sum%10)%10)
}

func isbnCheck10(first9 string) byte {
	sum := 0
	for i := 0; i < 9; i++ {
		sum += int(first9[i]-'0') * (10 - i)
	}
	check := (11 - sum%11) % 11
	if check == 10 {
		return 'x'
	}
	return byte('0' + check)
}

func clip(value string, runes int) string {
	if utf8.RuneCountInString(value) <= runes {
		return value
	}
	return string([]rune(value)[:runes])
}

func contains(list []string, value string) bool {
	for _, item := range list {
		if item == value {
			return true
		}
	}
	return false
}
