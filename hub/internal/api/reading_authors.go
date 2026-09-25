package api

import (
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"sort"
	"strings"
	"time"
)

type ReadingAuthor struct {
	ID         string        `json:"id"`
	Name       string        `json:"name"`
	Artwork    string        `json:"artwork,omitempty"`
	Total      int           `json:"total"`
	Page       int           `json:"page"`
	TotalPages int           `json:"totalPages"`
	Items      []ReadingWork `json:"items"`
}
type ReadingAuthorsResponse struct {
	Authors    []ReadingAuthor `json:"authors"`
	Page       int             `json:"page"`
	Total      int             `json:"total"`
	TotalPages int             `json:"totalPages"`
	Partial    []Partial       `json:"partial"`
	Cache      CacheInfo       `json:"cache"`
}

// Author grouping is done before pagination, over individual works rather than series containers.
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
	books, meta, err := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
	if err != nil {
		writeUpstreamError(w, r, "storyteller", err)
		return
	}
	groups := map[string]*ReadingAuthor{}
	bases := []storyteller.Book{}
	works := []ReadingWork{}
	for _, raw := range books {
		book := s.reconcileStorytellerBook(raw)
		work, err := s.storytellerWork("storyteller:books", book, false)
		if err != nil {
			writeReadingCatalogError(w, r, err)
			return
		}
		for i, base := range bases {
			if works[i].ID == work.ID || sameStorytellerEditionWork(base, book) {
				work.ID = works[i].ID
				break
			}
		}
		bases = append(bases, book)
		works = append(works, work)
		authors := book.Authors
		if len(authors) == 0 {
			authors = []storyteller.Creator{{Name: ""}}
		}
		for _, author := range authors {
			name := strings.Join(strings.Fields(author.Name), " ")
			key := "name:" + strings.ToLower(name)
			if author.UUID != "" {
				key = "storyteller:" + author.UUID
			}
			if name == "" {
				name = "Unknown author"
				key = "unknown"
			}
			digest := sha256.Sum256([]byte(key))
			id := "ra_" + hex.EncodeToString(digest[:16])
			group := groups[id]
			if group == nil {
				group = &ReadingAuthor{ID: id, Name: name, Items: []ReadingWork{}}
				groups[id] = group
			}
			if group.Artwork == "" {
				group.Artwork = s.readingAuthorArtwork(book, name)
			}
			found := false
			for i, item := range group.Items {
				if item.ID == work.ID {
					mergeReadingWork(&group.Items[i], work)
					found = true
					break
				}
			}
			if !found {
				group.Items = append(group.Items, work)
			}
		}
	}
	authors := make([]ReadingAuthor, 0, len(groups))
	for _, group := range groups {
		// Unverified/missing portraits deliberately request the initials fallback.
		sortReadingWorks(group.Items, "series", "asc")
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
				group.Page = page
				group.Items = readingAuthorPage(group.Items, page)
				out.Authors = []ReadingAuthor{group}
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
	for _, group := range authors[start:end] {
		group.Page = 1
		group.Items = readingAuthorPage(group.Items, 1)
		out.Authors = append(out.Authors, group)
	}
	writeJSON(w, 200, out)
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
		books, _, err := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
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
