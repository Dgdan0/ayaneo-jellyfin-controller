package api

// Importing the owner's Goodreads export (#39). The file is read from the request body
// and never kept: its rows are matched to the hub's works and only what the book page
// uses of the matched ones is stored (reading_you_store.go). Nothing of a row is ever
// logged, and an error never repeats a cell.

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"sort"
	"strings"
	"time"

	"ayaneohub/internal/cache"
	readingdomain "ayaneohub/internal/reading"
)

const (
	// goodreadsBodyLimit is far above a real export (a 5,000 book library is a few
	// megabytes); it only bounds what one request can make the hub read.
	goodreadsBodyLimit = 8 << 20
	// goodreadsListLimit bounds the lists a report carries; its counts are always whole.
	goodreadsListLimit = 200

	matchByISBN        = "isbn"
	matchByTitleAuthor = "title_author"
	missNotInLibrary   = "not_in_library"
	missAmbiguous      = "ambiguous"
)

type goodreadsCounts struct {
	// Rows is the data rows in the file. Matched, Unmatched and Skipped add up to it.
	Rows      int `json:"rows"`
	Matched   int `json:"matched"`
	Works     int `json:"works"`
	Unmatched int `json:"unmatched"`
	Ambiguous int `json:"ambiguous"`
	Skipped   int `json:"skipped"`
}

type goodreadsMatchItem struct {
	Title  string `json:"title"`
	Author string `json:"author"`
	WorkID string `json:"workId"`
	By     string `json:"by"`
}

type goodreadsMissItem struct {
	Title  string `json:"title"`
	Author string `json:"author"`
	Reason string `json:"reason"`
}

// GoodreadsImportReport is what an import (or a look at the last one) says.
type GoodreadsImportReport struct {
	DryRun     bool   `json:"dryRun"`
	Imported   bool   `json:"imported"`
	ImportedAt string `json:"importedAt,omitempty"`
	goodreadsCounts
	Matches       []goodreadsMatchItem `json:"matches"`
	UnmatchedRows []goodreadsMissItem  `json:"unmatchedRows"`
	Truncated     bool                 `json:"truncated"`
}

// goodreadsNone answers a profile that has no import.
type goodreadsNone struct {
	Imported bool `json:"imported"`
}

// matchCandidate is a work a row may be: what the hub knows of it.
type matchCandidate struct {
	ID      string
	Title   string
	Authors []string
}

// goodreadsMatcher finds the work a row is. Rows are matched by ISBN, then by title and
// author; a row that finds two works is ambiguous and finds none.
type goodreadsMatcher struct {
	byTitle map[string][]string // title key -> work ids
	byMain  map[string][]string // main title key -> work ids
	authors map[string][]string // work id -> author keys
	// isbn is the work the hub knows by an ISBN, in either of its spellings.
	isbn func(isbn string) (string, bool)
}

func newGoodreadsMatcher(candidates []matchCandidate, isbn func(string) (string, bool)) *goodreadsMatcher {
	m := &goodreadsMatcher{byTitle: map[string][]string{}, byMain: map[string][]string{}, authors: map[string][]string{}, isbn: isbn}
	add := func(index map[string][]string, key, id string) {
		if key == "" {
			return
		}
		for _, existing := range index[key] {
			if existing == id {
				return
			}
		}
		index[key] = append(index[key], id)
	}
	for _, candidate := range candidates {
		full, main := titleKeys(candidate.Title)
		add(m.byTitle, full, candidate.ID)
		add(m.byMain, main, candidate.ID)
		for _, author := range candidate.Authors {
			if key := authorKey(author); key != "" {
				m.authors[candidate.ID] = appendUnique(m.authors[candidate.ID], key)
			}
		}
	}
	return m
}

func appendUnique(list []string, value string) []string {
	for _, existing := range list {
		if existing == value {
			return list
		}
	}
	return append(list, value)
}

// titleKeys are a title as compared: without a series note (the hub's own and
// Goodreads'), lower case, letters and digits; and the same for what precedes a
// subtitle.
func titleKeys(title string) (full, main string) {
	clean := readingdomain.GoodreadsTitle(withoutSeriesNote(title))
	return normalizeReadingIdentity(clean), normalizeReadingIdentity(readingdomain.MainTitle(clean))
}

// authorKey is a name as compared: without a parenthesis (a role), lower case, its
// words in order of the alphabet so that "Sanderson Brandon" is "Brandon Sanderson".
func authorKey(name string) string {
	var plain strings.Builder
	depth := 0
	for _, r := range name {
		switch {
		case r == '(':
			depth++
		case r == ')' && depth > 0:
			depth--
		case depth == 0:
			plain.WriteRune(r)
		}
	}
	words := strings.Fields(normalizeReadingIdentity(plain.String()))
	sort.Strings(words)
	return strings.Join(words, " ")
}

// authorsOverlap says whether any of a row's authors is one of a work's.
func authorsOverlap(rowAuthors []string, workAuthors []string) bool {
	for _, mine := range rowAuthors {
		key := authorKey(mine)
		if key == "" {
			continue
		}
		for _, theirs := range workAuthors {
			if key == theirs {
				return true
			}
		}
	}
	return false
}

// match returns the work a row is, how it was found, or why it was not.
func (m *goodreadsMatcher) match(row readingdomain.GoodreadsRow) (id, by, miss string) {
	found := map[string]bool{}
	for _, number := range row.ISBNs {
		for _, form := range readingdomain.ISBNForms(number) {
			if work, ok := m.isbn("isbn:" + form); ok {
				found[work] = true
			}
		}
	}
	switch len(found) {
	case 1:
		return only(found), matchByISBN, ""
	case 0:
	default:
		return "", "", missAmbiguous
	}

	full, main := titleKeys(row.Title)
	for _, tier := range []struct {
		key   string
		index map[string][]string
	}{{full, m.byTitle}, {main, m.byMain}} {
		if tier.key == "" {
			continue
		}
		matched := map[string]bool{}
		for _, work := range tier.index[tier.key] {
			if authorsOverlap(row.Authors, m.authors[work]) {
				matched[work] = true
			}
		}
		switch len(matched) {
		case 1:
			return only(matched), matchByTitleAuthor, ""
		case 0:
		default:
			return "", "", missAmbiguous
		}
	}
	return "", "", missNotInLibrary
}

func only(set map[string]bool) string {
	for key := range set {
		return key
	}
	return ""
}

// goodreadsCandidates are the works a row can be: every Storyteller book, bound to its
// work (a book's ebook, audiobook and read-along edition are one), as the shelves bind
// them.
func (s *Server) goodreadsCandidates(ctx context.Context) ([]matchCandidate, error) {
	if s.storyteller == nil {
		return nil, nil
	}
	books, _, err := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
	if err != nil {
		return nil, err
	}
	var order []string
	byID := map[string]*matchCandidate{}
	for _, book := range books {
		if isReadingFixture(book) {
			continue
		}
		work, err := s.storytellerWork("storyteller:books", book, false)
		if err != nil {
			return nil, err
		}
		candidate := byID[work.ID]
		if candidate == nil {
			candidate = &matchCandidate{ID: work.ID, Title: work.Title}
			byID[work.ID] = candidate
			order = append(order, work.ID)
		}
		for _, author := range work.Authors {
			candidate.Authors = appendUnique(candidate.Authors, author)
		}
	}
	out := make([]matchCandidate, 0, len(order))
	for _, id := range order {
		out = append(out, *byID[id])
	}
	return out, nil
}

// matchGoodreads matches an export against the candidates and says what came of it.
func (s *Server) matchGoodreads(export readingdomain.GoodreadsExport, candidates []matchCandidate) (*youImport, GoodreadsImportReport) {
	matcher := newGoodreadsMatcher(candidates, s.readingCatalog.FindIdentity)
	counts := goodreadsCounts{Rows: export.Total, Skipped: export.Skipped}
	report := GoodreadsImportReport{Matches: []goodreadsMatchItem{}, UnmatchedRows: []goodreadsMissItem{}}
	rowsByWork := map[string][]readingdomain.GoodreadsRow{}
	var workOrder []string
	for _, row := range export.Rows {
		author := ""
		if len(row.Authors) > 0 {
			author = row.Authors[0]
		}
		id, by, miss := matcher.match(row)
		if id == "" {
			counts.Unmatched++
			if miss == missAmbiguous {
				counts.Ambiguous++
			}
			if len(report.UnmatchedRows) < goodreadsListLimit {
				report.UnmatchedRows = append(report.UnmatchedRows, goodreadsMissItem{Title: row.Title, Author: author, Reason: miss})
			} else {
				report.Truncated = true
			}
			continue
		}
		counts.Matched++
		if _, seen := rowsByWork[id]; !seen {
			workOrder = append(workOrder, id)
		}
		rowsByWork[id] = append(rowsByWork[id], row)
		if len(report.Matches) < goodreadsListLimit {
			report.Matches = append(report.Matches, goodreadsMatchItem{Title: row.Title, Author: author, WorkID: id, By: by})
		} else {
			report.Truncated = true
		}
	}
	counts.Works = len(workOrder)
	imported := &youImport{Report: counts, Matches: report.Matches, Unmatched: report.UnmatchedRows, Truncated: report.Truncated, Works: map[string]youRecord{}}
	for _, id := range workOrder {
		imported.Works[id] = readingdomain.CombineGoodreads(rowsByWork[id])
	}
	report.goodreadsCounts = counts
	return imported, report
}

// handleGoodreadsImport serves POST /v1/reading/import/goodreads: the body is the export,
// as it came from Goodreads. ?dryRun=true matches and reports and keeps nothing.
func (s *Server) handleGoodreadsImport(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	profile, ok := s.readingProfile(r)
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad Jellyfin user id"})
		return
	}
	if strings.HasPrefix(strings.ToLower(r.Header.Get("Content-Type")), "multipart/") {
		writeError(w, r, http.StatusUnsupportedMediaType, Error{Code: CodeInvalidRequest, Message: "send the export file itself as the request body, not as a form"})
		return
	}
	dry := false
	switch strings.ToLower(r.URL.Query().Get("dryRun")) {
	case "", "0", "false":
	case "1", "true":
		dry = true
	default:
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "dryRun must be true or false"})
		return
	}
	export, err := readingdomain.ParseGoodreads(http.MaxBytesReader(w, r.Body, goodreadsBodyLimit))
	if err != nil {
		var tooLarge *http.MaxBytesError
		switch {
		case errors.As(err, &tooLarge), errors.Is(err, readingdomain.ErrGoodreadsTooBig):
			writeError(w, r, http.StatusRequestEntityTooLarge, Error{Code: CodeInvalidRequest, Message: "that export is larger than this hub reads (8 MiB, 20,000 rows)"})
		case errors.Is(err, readingdomain.ErrNotGoodreads):
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "that is not a Goodreads export: it needs a Title column and an Author or ISBN column"})
		default:
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "that file could not be read as a Goodreads export"})
		}
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	candidates, err := s.goodreadsCandidates(ctx)
	if err != nil {
		// Matching against a library that could not be read would call every row
		// missing, and keep it.
		writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Service: "storyteller", Message: "the library could not be read to match the export; try again", Retryable: true})
		return
	}
	imported, report := s.matchGoodreads(export, candidates)
	report.DryRun = dry
	if !dry {
		imported.ImportedAt = s.now().UTC().Format(time.RFC3339)
		if err := s.readingYou.replaceImport(profile, imported); err != nil {
			slog.Error("goodreads import not saved", "err", err)
			writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the import could not be saved"})
			return
		}
		report.Imported, report.ImportedAt = true, imported.ImportedAt
	}
	// Counts only: a row is the owner's, and so is the list of what they read.
	slog.Info("goodreads import", "rows", report.Rows, "matched", report.Matched, "works", report.Works, "unmatched", report.Unmatched, "dryRun", dry)
	w.Header().Add("Vary", jellyfinUserHeader)
	writeJSON(w, http.StatusOK, report)
}

// handleGoodreadsStatus serves GET /v1/reading/import/goodreads: the profile's last import.
func (s *Server) handleGoodreadsStatus(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	profile, ok := s.readingProfile(r)
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad Jellyfin user id"})
		return
	}
	w.Header().Add("Vary", jellyfinUserHeader)
	imported := s.readingYou.importOf(profile)
	if imported == nil {
		writeJSON(w, http.StatusOK, goodreadsNone{})
		return
	}
	report := GoodreadsImportReport{
		Imported: true, ImportedAt: imported.ImportedAt, goodreadsCounts: imported.Report,
		Matches: imported.Matches, UnmatchedRows: imported.Unmatched, Truncated: imported.Truncated,
	}
	if report.Matches == nil {
		report.Matches = []goodreadsMatchItem{}
	}
	if report.UnmatchedRows == nil {
		report.UnmatchedRows = []goodreadsMissItem{}
	}
	writeJSON(w, http.StatusOK, report)
}

// handleGoodreadsForget serves DELETE /v1/reading/import/goodreads: the profile's import
// goes; what was set from an app stays.
func (s *Server) handleGoodreadsForget(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	profile, ok := s.readingProfile(r)
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad Jellyfin user id"})
		return
	}
	if err := s.readingYou.replaceImport(profile, nil); err != nil {
		slog.Error("goodreads import not removed", "err", err)
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the import could not be removed"})
		return
	}
	w.Header().Add("Vary", jellyfinUserHeader)
	writeJSON(w, http.StatusOK, goodreadsNone{})
}
