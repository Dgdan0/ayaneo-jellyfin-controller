package api

import (
	"ayaneohub/internal/adapters/openlibrary"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"
)

// ReadingAuthorRef names an author page: what a book links to.
type ReadingAuthorRef struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

// readingAuthorRef is the one place an author's page id is made, so the
// Authors view and a book's author link agree. Callers pass names from
// storytellerPeople, which has already turned "Brown, Pierce" into "Pierce Brown".
func readingAuthorRef(name string) ReadingAuthorRef {
	key := normalizeReadingIdentity(name)
	if key == "" {
		name, key = "Unknown author", "unknown"
	}
	digest := sha256.Sum256([]byte("name:" + key))
	return ReadingAuthorRef{ID: "ra_" + hex.EncodeToString(digest[:16]), Name: name}
}

type ReadingAuthor struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Artwork string `json:"artwork,omitempty"`
	// SeriesCount and BookCount describe the author's shelf: "2 series", "2 books".
	SeriesCount int           `json:"seriesCount"`
	BookCount   int           `json:"bookCount"`
	Total       int           `json:"total"`
	Page        int           `json:"page"`
	TotalPages  int           `json:"totalPages"`
	Items       []ReadingWork `json:"items"`
}
type ReadingAuthorsResponse struct {
	Authors    []ReadingAuthor `json:"authors"`
	Page       int             `json:"page"`
	Total      int             `json:"total"`
	TotalPages int             `json:"totalPages"`
	Partial    []Partial       `json:"partial"`
	Cache      CacheInfo       `json:"cache"`
}

// An author's shelf is their series, each as one collection, then their books
// outside any series: Brandon Sanderson holds Mistborn and the Stormlight
// Archive, Blake Crouch holds Dark Matter and Recursion. Asking for one author
// includes each series' books, for an author page with a row per series.
// Authors are keyed by name as storytellerPeople writes it, so "Brown, Pierce"
// and "Pierce Brown" are one person and narrators are not authors.
func (s *Server) handleReadingAuthors(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	if r.PathValue("libraryId") != "storyteller:books" {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "author shelves unavailable for this library"})
		return
	}
	page, _, direction, ok := readingPageOptions(w, r)
	if !ok {
		return
	}
	authorID := r.URL.Query().Get("authorId")
	if authorID != "" && (len(authorID) != 35 || !strings.HasPrefix(authorID, "ra_")) {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "invalid author id"})
		return
	}
	if s.storyteller == nil {
		writeError(w, r, 503, Error{Code: CodeUpstreamDown, Service: "storyteller", Message: "Storyteller is unavailable"})
		return
	}
	ctx, cancel := timeoutFor(r, 25*time.Second)
	defer cancel()
	books, meta, err := s.storytellerBooks(ctx)
	if err != nil {
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	standalone, seriesGroups := s.storytellerShelfGroups(books, true)
	groups := map[string]*ReadingAuthor{}
	// One of each writer's own book titles, to find their portrait by.
	bookTitles := map[string]string{}
	add := func(name string, work ReadingWork, artworkBook *storyteller.Book) {
		ref := readingAuthorRef(name)
		name = ref.Name
		group := groups[ref.ID]
		if group == nil {
			group = &ReadingAuthor{ID: ref.ID, Name: name, Items: []ReadingWork{}}
			groups[ref.ID] = group
		}
		if bookTitles[ref.ID] == "" {
			if artworkBook != nil {
				bookTitles[ref.ID] = artworkBook.Title
			} else if work.EntityType != "collection" {
				bookTitles[ref.ID] = work.Title
			}
		}
		if group.Artwork == "" && artworkBook != nil {
			group.Artwork = s.readingAuthorArtwork(*artworkBook, name)
		}
		group.Items = append(group.Items, work)
		if work.EntityType == "collection" {
			group.SeriesCount++
		}
		group.BookCount += max(work.BookCount, 1)
	}
	for _, series := range seriesGroups {
		work, err := s.storytellerCollection("storyteller:books", series, false)
		if err != nil {
			writeReadingCatalogError(w, r, err)
			return
		}
		names := work.Authors
		if len(names) == 0 {
			names = []string{""}
		}
		for _, name := range names {
			add(name, work, &series.books[0])
		}
	}
	works, err := s.storytellerStandaloneWorks("storyteller:books", standalone)
	if err != nil {
		writeReadingCatalogError(w, r, err)
		return
	}
	for _, work := range works {
		names := work.Authors
		if len(names) == 0 {
			names = []string{""}
		}
		for _, name := range names {
			add(name, work, nil)
		}
	}
	authors := make([]ReadingAuthor, 0, len(groups))
	for _, group := range groups {
		// Unverified/missing portraits deliberately request the initials fallback.
		sort.SliceStable(group.Items, func(i, j int) bool {
			left, right := group.Items[i], group.Items[j]
			if (left.EntityType == "collection") != (right.EntityType == "collection") {
				return left.EntityType == "collection"
			}
			return readingTitleSort(left) < readingTitleSort(right)
		})
		group.Total = len(group.Items)
		group.TotalPages = (group.Total + 11) / 12
		authors = append(authors, *group)
	}
	sort.Slice(authors, func(i, j int) bool {
		a, b := authors[i], authors[j]
		if (a.Name == "Unknown author") != (b.Name == "Unknown author") {
			return b.Name == "Unknown author"
		}
		if strings.EqualFold(a.Name, b.Name) {
			return a.ID < b.ID
		}
		return (strings.ToLower(a.Name) < strings.ToLower(b.Name)) != (direction == "desc")
	})
	out := ReadingAuthorsResponse{Authors: []ReadingAuthor{}, Page: page, Total: len(authors), TotalPages: (len(authors) + 11) / 12, Partial: []Partial{}, Cache: cacheInfoFrom(meta)}
	if authorID != "" {
		for _, group := range authors {
			if group.ID == authorID {
				for i, item := range group.Items {
					if item.EntityType != "collection" {
						continue
					}
					for _, series := range seriesGroups {
						full, err := s.storytellerCollection("storyteller:books", series, true)
						if err == nil && full.ID == item.ID {
							group.Items[i] = full
							break
						}
					}
				}
				group.Page = page
				group.Items = readingAuthorPage(group.Items, page)
				one := []ReadingAuthor{group}
				s.fillAuthorPhotos(ctx, one, bookTitles)
				out.Authors = one
				out.Total = group.Total
				out.TotalPages = group.TotalPages
				writeJSON(w, 200, out)
				return
			}
		}
		writeError(w, r, 404, Error{Code: CodeNotFound, Message: "Author no longer in this library"})
		return
	}
	start := min((page-1)*12, len(authors))
	end := min(start+12, len(authors))
	s.fillAuthorPhotos(ctx, authors[start:end], bookTitles)
	for _, group := range authors[start:end] {
		group.Page = 1
		group.Items = readingAuthorPage(group.Items, 1)
		out.Authors = append(out.Authors, group)
	}
	writeJSON(w, 200, out)
}

// fillAuthorPhotos gives each writer without a portrait from a requested series
// their Open Library photo, found through one of their own books in the
// library. Looked up together and remembered for a day; a writer Open Library
// does not know keeps the initials.
func (s *Server) fillAuthorPhotos(ctx context.Context, authors []ReadingAuthor, bookTitles map[string]string) {
	if s.openlibrary == nil {
		return
	}
	var wait sync.WaitGroup
	for i := range authors {
		author := &authors[i]
		title := bookTitles[author.ID]
		if author.Artwork != "" || title == "" || author.Name == "" || author.Name == "Unknown author" {
			continue
		}
		wait.Add(1)
		go func() {
			defer wait.Done()
			key := "reading:author-photo:" + strings.ToLower(author.Name) + "\x00" + strings.ToLower(title)
			id, _, err := cache.Fetch(ctx, s.cache, key, cache.Metadata, func(ctx context.Context) (string, error) {
				return s.openlibrary.AuthorIDForBook(ctx, author.Name, title)
			})
			if err != nil || id == "" {
				return
			}
			if token := s.images.registerReadingCover(openlibrary.AuthorPhotoURL(id)); token != "" {
				author.Artwork = "/v1/img/reading/" + token
			}
		}()
	}
	wait.Wait()
}

func (s *Server) readingAuthorArtwork(book storyteller.Book, name string) string {
	if s.readingAcquisitions == nil || len(book.Series) == 0 {
		return ""
	}
	manifest, ok := s.readingAcquisitions.seriesRoster(storytellerAcquisitionSeriesID([]storyteller.Book{book}), book.Series[0].Name, []string{name})
	if !ok || manifest.AuthorID == "" || !strings.EqualFold(strings.TrimSpace(manifest.Author), strings.TrimSpace(name)) {
		return ""
	}
	if token := s.images.registerReadingCover(manifest.AuthorImageURL); token != "" {
		return "/v1/img/reading/" + token
	}
	return ""
}
func readingAuthorPage(items []ReadingWork, page int) []ReadingWork {
	start := min((page-1)*12, len(items))
	return items[start:min(start+12, len(items))]
}

// Resolve a provider entry by stable source mapping / identifiers only. Acquisition tracking
// and title resemblance cannot assert playable availability.
func (s *Server) handleReadingResolve(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	source, sourceID := strings.TrimSpace(r.URL.Query().Get("source")), strings.TrimSpace(r.URL.Query().Get("sourceId"))
	isbn := normalizeISBN(r.URL.Query().Get("isbn"))
	if len(source) > 64 || len(sourceID) > 256 || source == "" || sourceID == "" || r.URL.Query().Get("isbn") != "" && isbn == "" {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "invalid book identity"})
		return
	}
	if s.storyteller != nil {
		ctx, cancel := timeoutFor(r, 25*time.Second)
		defer cancel()
		books, _, err := s.storytellerBooks(ctx)
		if err != nil {
			writeUpstreamError(w, r, "storyteller", err)
			return
		}
		for _, book := range books {
			if _, err := s.storytellerWork("storyteller:books", book, false); err != nil {
				writeReadingCatalogError(w, r, err)
				return
			}
		}
	}
	id, found := s.readingCatalog.WorkIDFor(source, sourceID)
	if !found && isbn != "" {
		id, found = s.readingCatalog.FindIdentity("isbn:" + isbn)
	}
	if !found {
		id, found = s.readingCatalog.FindIdentity("external:" + strings.ToLower(source) + ":" + normalizeReadingIdentity(sourceID))
	}
	// An unmatched identifier may refer to another edition of the same title. Stay unknown.
	writeJSON(w, 200, struct {
		WorkID   string `json:"workId"`
		Resolved bool   `json:"resolved"`
	}{id, found})
}
