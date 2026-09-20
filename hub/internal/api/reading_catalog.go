package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"ayaneohub/internal/adapters/kavita"
	"ayaneohub/internal/adapters/storyteller"
	"ayaneohub/internal/cache"
	readingdomain "ayaneohub/internal/reading"
)

type ReadingLibrary struct {
	ID           string   `json:"id"`
	Source       string   `json:"source"`
	Kind         string   `json:"kind"`
	Title        string   `json:"title"`
	Artwork      string   `json:"artwork,omitempty"`
	Capabilities []string `json:"capabilities"`
}

type ReadingLibrariesResponse struct {
	Libraries []ReadingLibrary `json:"libraries"`
	Partial   []Partial        `json:"partial"`
	Cache     CacheInfo        `json:"cache"`
}

type ReadingProgress struct {
	Percentage float64 `json:"percentage"`
	Completed  bool    `json:"completed"`
	Current    int     `json:"current,omitempty"`
	Total      int     `json:"total,omitempty"`
	UpdatedAt  string  `json:"updatedAt,omitempty"`
}

type ReadingEdition struct {
	ID           string            `json:"id"`
	WorkID       string            `json:"workId"`
	Source       string            `json:"source"`
	SourceItemID string            `json:"sourceItemId,omitempty"`
	Kind         string            `json:"kind"`
	Format       string            `json:"format,omitempty"`
	Identifiers  map[string]string `json:"identifiers,omitempty"`
	Narrator     string            `json:"narrator,omitempty"`
	PageCount    int               `json:"pageCount,omitempty"`
	DurationMS   int64             `json:"durationMs,omitempty"`
	Availability string            `json:"availability"`
}

type ReadingSectionItem struct {
	SourceItemID string           `json:"sourceItemId"`
	Title        string           `json:"title"`
	Number       string           `json:"number,omitempty"`
	Kind         string           `json:"kind"`
	PageCount    int              `json:"pageCount,omitempty"`
	Progress     *ReadingProgress `json:"progress,omitempty"`
}

type ReadingSection struct {
	ID     string               `json:"id"`
	Title  string               `json:"title"`
	Number float64              `json:"number,omitempty"`
	Items  []ReadingSectionItem `json:"items"`
}

type ReadingContinue struct {
	Source       string  `json:"source"`
	SourceItemID string  `json:"sourceItemId"`
	Title        string  `json:"title"`
	Number       string  `json:"number,omitempty"`
	Percentage   float64 `json:"percentage,omitempty"`
}

type ReadingWork struct {
	ID           string           `json:"id"`
	LibraryID    string           `json:"libraryId,omitempty"`
	Kind         string           `json:"kind"`
	Title        string           `json:"title"`
	SortTitle    string           `json:"sortTitle,omitempty"`
	Authors      []string         `json:"authors"`
	Series       string           `json:"series,omitempty"`
	SeriesIndex  float64          `json:"seriesIndex,omitempty"`
	Overview     string           `json:"overview,omitempty"`
	Artwork      string           `json:"artwork,omitempty"`
	Genres       []string         `json:"genres"`
	Year         int              `json:"year,omitempty"`
	Languages    []string         `json:"languages"`
	Editions     []ReadingEdition `json:"editions"`
	Progress     *ReadingProgress `json:"progress,omitempty"`
	Availability []string         `json:"availability"`
	Sections     []ReadingSection `json:"sections,omitempty"`
	Continue     *ReadingContinue `json:"continue,omitempty"`
	Partial      []Partial        `json:"partial,omitempty"`
	Cache        CacheInfo        `json:"cache"`
}

type ReadingLibraryItemsResponse struct {
	LibraryID  string        `json:"libraryId"`
	Page       int           `json:"page"`
	PageSize   int           `json:"pageSize"`
	Total      int           `json:"total"`
	TotalPages int           `json:"totalPages"`
	HasMore    bool          `json:"hasMore"`
	Items      []ReadingWork `json:"items"`
	Partial    []Partial     `json:"partial"`
	Cache      CacheInfo     `json:"cache"`
}

func (s *Server) handleReadingLibraries(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(20*time.Second))
	defer cancel()
	out := ReadingLibrariesResponse{Libraries: []ReadingLibrary{}, Partial: []Partial{}}
	allHits := true
	var oldest time.Duration

	if s.kavita != nil {
		libraries, meta, err := cache.Fetch(ctx, s.cache, "reading:kavita:libraries", cache.LibraryPage, s.kavita.Libraries)
		if err != nil {
			out.Partial = append(out.Partial, readingPartial("kavita", "libraries"))
		} else {
			for _, library := range libraries {
				out.Libraries = append(out.Libraries, ReadingLibrary{
					ID: "kavita:" + strconv.Itoa(library.ID), Source: "kavita", Kind: kavitaLibraryKind(library.Type),
					Title: library.Name, Capabilities: []string{"browse", "details", "progress"},
				})
			}
			allHits = allHits && meta.Hit
			if meta.Age > oldest {
				oldest = meta.Age
			}
		}
	}
	if s.storyteller != nil {
		books, meta, err := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
		if err != nil {
			out.Partial = append(out.Partial, readingPartial("storyteller", "libraries"))
		} else {
			out.Libraries = append(out.Libraries, ReadingLibrary{
				ID: "storyteller:books", Source: "storyteller", Kind: "book", Title: "Books & Audiobooks",
				Capabilities: []string{"browse", "details", "ebook", "audiobook", "readaloud", "progress"},
			})
			_ = books
			allHits = allHits && meta.Hit
			if meta.Age > oldest {
				oldest = meta.Age
			}
		}
	}
	if len(out.Libraries) == 0 && len(out.Partial) > 0 {
		writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Service: "reading", Message: "Reading libraries are unavailable", Retryable: true})
		return
	}
	out.Cache = CacheInfo{Hit: allHits, AgeSeconds: int(oldest.Seconds())}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) handleReadingLibraryItems(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	libraryID := strings.TrimSpace(r.PathValue("libraryId"))
	page, sortBy, direction, ok := readingPageOptions(w, r)
	if !ok {
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()

	switch {
	case strings.HasPrefix(libraryID, "kavita:"):
		id, err := strconv.Atoi(strings.TrimPrefix(libraryID, "kavita:"))
		if err != nil || id <= 0 || s.kavita == nil {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading library"})
			return
		}
		kind, err := s.kavitaLibraryKind(ctx, id)
		if err != nil {
			if errors.Is(err, errReadingLibraryNotFound) {
				writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Service: "kavita", Message: "Reading library not found"})
				return
			}
			writeUpstreamError(w, r, "kavita", err)
			return
		}
		key := fmt.Sprintf("reading:kavita:library:%d:%d:%s:%s", id, page, sortBy, direction)
		upstream, meta, err := cache.Fetch(ctx, s.cache, key, cache.LibraryPage, func(fetchCtx context.Context) (*kavita.SeriesPage, error) {
			return s.kavita.Series(fetchCtx, id, page, 60, kavita.Sort(sortBy), kavita.Direction(direction))
		})
		if err != nil {
			writeUpstreamError(w, r, "kavita", err)
			return
		}
		items := make([]ReadingWork, 0, len(upstream.Items))
		for _, item := range upstream.Items {
			mapped, bindErr := s.kavitaSummary(libraryID, kind, item)
			if bindErr != nil {
				writeReadingCatalogError(w, r, bindErr)
				return
			}
			items = append(items, mapped)
		}
		writeJSON(w, http.StatusOK, ReadingLibraryItemsResponse{
			LibraryID: libraryID, Page: upstream.Page, PageSize: upstream.PageSize, Total: upstream.Total,
			TotalPages: upstream.TotalPages, HasMore: upstream.Page < upstream.TotalPages, Items: items,
			Partial: []Partial{}, Cache: cacheInfoFrom(meta),
		})

	case libraryID == "storyteller:books":
		if s.storyteller == nil {
			writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Service: "storyteller", Message: "Storyteller is not configured"})
			return
		}
		books, meta, err := cache.Fetch(ctx, s.cache, "reading:storyteller:books", cache.LibraryPage, s.storyteller.Books)
		if err != nil {
			writeUpstreamError(w, r, "storyteller", err)
			return
		}
		// Cache entries are shared between requests. Sort a private copy so two
		// concurrent requests cannot reorder the same backing array.
		books = append([]storyteller.Book(nil), books...)
		sortStorytellerBooks(books, sortBy, direction)
		const pageSize = 60
		start := (page - 1) * pageSize
		if start > len(books) {
			start = len(books)
		}
		end := start + pageSize
		if end > len(books) {
			end = len(books)
		}
		items := make([]ReadingWork, 0, end-start)
		for _, book := range books[start:end] {
			mapped, bindErr := s.storytellerWork("storyteller:books", book, false)
			if bindErr != nil {
				writeReadingCatalogError(w, r, bindErr)
				return
			}
			items = append(items, mapped)
		}
		totalPages := (len(books) + pageSize - 1) / pageSize
		writeJSON(w, http.StatusOK, ReadingLibraryItemsResponse{
			LibraryID: libraryID, Page: page, PageSize: pageSize, Total: len(books), TotalPages: totalPages,
			HasMore: page < totalPages, Items: items, Partial: []Partial{}, Cache: cacheInfoFrom(meta),
		})

	default:
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading library"})
	}
}

func (s *Server) handleReadingWork(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	workID := r.PathValue("workId")
	if !validReadingWorkID(workID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading work id"})
		return
	}
	binding, found := s.readingCatalog.Resolve(workID)
	if !found {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "reading work not found"})
		return
	}
	ctx, cancel := timeoutFor(r, s.cfg.Server.RequestTimeout.OrDefault(25*time.Second))
	defer cancel()
	var combined *ReadingWork
	var partial []Partial
	for _, source := range binding.Sources {
		var mapped ReadingWork
		var err error
		switch source.Source {
		case "kavita":
			id, parseErr := strconv.Atoi(source.SourceID)
			if parseErr != nil || s.kavita == nil {
				continue
			}
			var detail *kavita.Detail
			detail, _, err = cache.Fetch(ctx, s.cache, "reading:kavita:work:"+source.SourceID, cache.Metadata, func(fetchCtx context.Context) (*kavita.Detail, error) { return s.kavita.Detail(fetchCtx, id) })
			if err == nil {
				mapped, err = s.kavitaWork(ctx, workID, detail)
			}
		case "storyteller":
			id, parseErr := strconv.ParseInt(source.SourceID, 10, 64)
			if parseErr != nil || s.storyteller == nil {
				continue
			}
			var book *storyteller.Book
			book, _, err = cache.Fetch(ctx, s.cache, "reading:storyteller:work:"+source.SourceID, cache.Metadata, func(fetchCtx context.Context) (*storyteller.Book, error) { return s.storyteller.Book(fetchCtx, id) })
			if err == nil {
				mapped, err = s.storytellerWork("storyteller:books", *book, true)
			}
		}
		if err != nil {
			partial = append(partial, readingPartial(source.Source, "work"))
			continue
		}
		if combined == nil {
			copy := mapped
			combined = &copy
		} else {
			mergeReadingWork(combined, mapped)
		}
	}
	if combined == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{Code: CodeUpstreamDown, Service: "reading", Message: "Reading work is unavailable", Retryable: true})
		return
	}
	combined.ID = workID
	combined.Partial = partial
	writeJSON(w, http.StatusOK, combined)
}

func (s *Server) handleKavitaReadingImage(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	id, err := strconv.Atoi(r.PathValue("seriesId"))
	if err != nil || id <= 0 || s.kavita == nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid Kavita cover"})
		return
	}
	s.writeReadingServiceImage(w, r, "kavita", strconv.Itoa(id), func(ctx context.Context) ([]byte, string, error) { return s.kavita.Cover(ctx, id) })
}

func (s *Server) handleStorytellerReadingImage(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	id, err := strconv.ParseInt(r.PathValue("bookId"), 10, 64)
	if err != nil || id <= 0 || s.storyteller == nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid Storyteller cover"})
		return
	}
	s.writeReadingServiceImage(w, r, "storyteller", strconv.FormatInt(id, 10), func(ctx context.Context) ([]byte, string, error) { return s.storyteller.Cover(ctx, id) })
}

func (s *Server) writeReadingServiceImage(w http.ResponseWriter, r *http.Request, source, id string, fetch func(context.Context) ([]byte, string, error)) {
	key := "reading/" + source + "/" + id
	if img := s.images.get(key); img != nil {
		s.writeImage(w, img, true)
		return
	}
	ctx, cancel := timeoutFor(r, 20*time.Second)
	defer cancel()
	body, contentType, err := fetch(ctx)
	if err != nil {
		writeUpstreamError(w, r, source, err)
		return
	}
	img := &cachedImage{body: body, contentType: contentType, fetchedAt: time.Now()}
	s.images.put(key, img)
	s.writeImage(w, img, false)
}

func readingPageOptions(w http.ResponseWriter, r *http.Request) (int, string, string, bool) {
	page := 1
	if raw := r.URL.Query().Get("page"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			page = 0
		} else {
			page = parsed
		}
	}
	sortBy := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("sort")))
	if sortBy == "" {
		sortBy = "title"
	}
	direction := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("direction")))
	if direction == "" {
		direction = "asc"
	}
	if page < 1 || page > 10000 || (sortBy != "title" && sortBy != "added" && sortBy != "progress") || (direction != "asc" && direction != "desc") {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading page options"})
		return 0, "", "", false
	}
	return page, sortBy, direction, true
}

func (s *Server) kavitaLibraryKind(ctx context.Context, libraryID int) (string, error) {
	libraries, _, err := cache.Fetch(ctx, s.cache, "reading:kavita:libraries", cache.LibraryPage, s.kavita.Libraries)
	if err != nil {
		return "", err
	}
	for _, library := range libraries {
		if library.ID == libraryID {
			return kavitaLibraryKind(library.Type), nil
		}
	}
	return "", errReadingLibraryNotFound
}

var errReadingLibraryNotFound = errors.New("reading library not found")

func kavitaLibraryKind(libraryType int) string {
	switch libraryType {
	case 0:
		return "manga"
	case 1:
		return "comic"
	case 2:
		return "book"
	default:
		return "book"
	}
}

func (s *Server) kavitaSummary(libraryID, kind string, item kavita.Series) (ReadingWork, error) {
	id, err := s.readingCatalog.Bind(readingdomain.WorkBinding{Source: "kavita", SourceID: strconv.Itoa(item.ID), IdentityKeys: []string{"title:" + normalizeReadingIdentity(item.Name)}})
	if err != nil {
		return ReadingWork{}, err
	}
	return ReadingWork{
		ID: id, LibraryID: libraryID, Kind: kind, Title: item.Name, SortTitle: item.SortName,
		Authors: []string{}, Genres: []string{}, Languages: []string{}, Editions: []ReadingEdition{},
		Artwork: "/v1/img/reading/kavita/" + strconv.Itoa(item.ID), Progress: pageProgress(item.PagesRead, item.Pages),
		Availability: []string{kind}, Cache: CacheInfo{},
	}, nil
}

func (s *Server) kavitaWork(ctx context.Context, workID string, detail *kavita.Detail) (ReadingWork, error) {
	kind, err := s.kavitaLibraryKind(ctx, detail.Series.LibraryID)
	if err != nil {
		return ReadingWork{}, err
	}
	authors := make([]string, 0, len(detail.Metadata.Writers))
	for _, writer := range detail.Metadata.Writers {
		if name := strings.TrimSpace(writer.Name); name != "" {
			authors = append(authors, name)
		}
	}
	identities := []string{"title:" + normalizeReadingIdentity(detail.Series.Name)}
	if len(authors) > 0 {
		identities = append(identities, metadataIdentity(detail.Series.Name, authors, "", 0))
	}
	identifiers := map[string]string{}
	sections := make([]ReadingSection, 0, len(detail.Volumes))
	for _, volume := range detail.Volumes {
		section := ReadingSection{ID: "kavita-volume:" + strconv.Itoa(volume.ID), Title: kavitaVolumeTitle(volume), Number: volume.Number, Items: []ReadingSectionItem{}}
		for _, chapter := range volume.Chapters {
			if value := normalizeISBN(chapter.ISBN); value != "" {
				identities = append(identities, "isbn:"+value)
				identifiers["isbn"] = value
			}
			section.Items = append(section.Items, ReadingSectionItem{SourceItemID: strconv.Itoa(chapter.ID), Title: chapter.Title, Number: kavitaChapterNumber(chapter.Number), Kind: kind, PageCount: chapter.Pages, Progress: pageProgress(chapter.PagesRead, chapter.Pages)})
		}
		sections = append(sections, section)
	}
	boundID, err := s.readingCatalog.Bind(readingdomain.WorkBinding{Source: "kavita", SourceID: strconv.Itoa(detail.Series.ID), IdentityKeys: identities})
	if err != nil {
		return ReadingWork{}, err
	}
	if workID == "" {
		workID = boundID
	}
	genres := make([]string, 0, len(detail.Metadata.Genres))
	for _, genre := range detail.Metadata.Genres {
		if genre.Title != "" {
			genres = append(genres, genre.Title)
		}
	}
	languages := []string{}
	if detail.Metadata.Language != "" {
		languages = append(languages, detail.Metadata.Language)
	}
	edition := ReadingEdition{ID: editionID("kavita", strconv.Itoa(detail.Series.ID), kind), WorkID: workID, Source: "kavita", SourceItemID: strconv.Itoa(detail.Series.ID), Kind: kind, Format: kavitaFormat(detail.Series.Format), Identifiers: identifiers, PageCount: detail.Series.Pages, Availability: "available"}
	work := ReadingWork{ID: workID, LibraryID: "kavita:" + strconv.Itoa(detail.Series.LibraryID), Kind: kind, Title: detail.Series.Name, SortTitle: detail.Series.SortName, Authors: authors, Overview: detail.Metadata.Summary, Artwork: "/v1/img/reading/kavita/" + strconv.Itoa(detail.Series.ID), Genres: genres, Year: detail.Metadata.ReleaseYear, Languages: languages, Editions: []ReadingEdition{edition}, Progress: pageProgress(detail.Series.PagesRead, detail.Series.Pages), Availability: []string{kind}, Sections: sections, Partial: []Partial{}}
	if detail.Continue.ID > 0 {
		work.Continue = &ReadingContinue{Source: "kavita", SourceItemID: strconv.Itoa(detail.Continue.ID), Title: detail.Continue.Title, Number: kavitaChapterNumber(detail.Continue.Number), Percentage: percentage(detail.Continue.PagesRead, detail.Continue.Pages)}
	}
	return work, nil
}

func (s *Server) storytellerWork(libraryID string, book storyteller.Book, includeEditions bool) (ReadingWork, error) {
	authors := creatorNames(book.Authors)
	seriesName := ""
	seriesIndex := 0.0
	if len(book.Series) > 0 {
		seriesName = book.Series[0].Name
		seriesIndex = book.Series[0].Position
	}
	identities := storytellerIdentities(book, authors, seriesName, seriesIndex)
	id, err := s.readingCatalog.Bind(readingdomain.WorkBinding{Source: "storyteller", SourceID: strconv.FormatInt(book.ID, 10), IdentityKeys: identities})
	if err != nil {
		return ReadingWork{}, err
	}
	availability := storytellerAvailability(book)
	kind := "book"
	if book.Ebook == nil && book.Readaloud == nil && book.Audiobook != nil {
		kind = "audiobook"
	}
	work := ReadingWork{ID: id, LibraryID: libraryID, Kind: kind, Title: book.Title, SortTitle: book.Title, Authors: authors, Series: seriesName, SeriesIndex: seriesIndex, Overview: book.Description, Artwork: "/v1/img/reading/storyteller/" + strconv.FormatInt(book.ID, 10), Genres: []string{}, Languages: []string{}, Editions: []ReadingEdition{}, Progress: storytellerProgress(book.Position), Availability: availability, Partial: []Partial{}}
	if book.Language != "" {
		work.Languages = []string{book.Language}
	}
	work.Year = readingYear(book.PublicationDate)
	if includeEditions {
		work.Editions = storytellerEditions(id, book)
	}
	return work, nil
}

func storytellerEditions(workID string, book storyteller.Book) []ReadingEdition {
	out := []ReadingEdition{}
	if book.Ebook != nil && !book.Ebook.Missing {
		out = append(out, ReadingEdition{ID: editionID("storyteller", book.Ebook.UUID, "ebook"), WorkID: workID, Source: "storyteller", SourceItemID: strconv.FormatInt(book.ID, 10), Kind: "ebook", Format: "epub", PageCount: book.Ebook.PageCount, Availability: "available"})
	}
	if book.Audiobook != nil && !book.Audiobook.Missing {
		out = append(out, ReadingEdition{ID: editionID("storyteller", book.Audiobook.UUID, "audiobook"), WorkID: workID, Source: "storyteller", SourceItemID: strconv.FormatInt(book.ID, 10), Kind: "audiobook", Format: "audio", Narrator: strings.Join(creatorNames(book.Narrators), ", "), DurationMS: int64(book.Audiobook.Duration * 1000), Availability: "available"})
	}
	if book.Readaloud != nil && !book.Readaloud.Missing {
		out = append(out, ReadingEdition{ID: editionID("storyteller", book.Readaloud.UUID, "readaloud"), WorkID: workID, Source: "storyteller", SourceItemID: strconv.FormatInt(book.ID, 10), Kind: "readaloud", Format: "epub-media-overlay", Availability: "available"})
	}
	return out
}

func storytellerIdentities(book storyteller.Book, authors []string, seriesName string, seriesIndex float64) []string {
	out := []string{"title:" + normalizeReadingIdentity(book.Title)}
	for _, identifier := range book.Identifiers {
		value := identifier.Value
		if value == "" {
			value = identifier.Identifier
		}
		kind := strings.ToLower(strings.TrimSpace(identifier.Type))
		if kind == "" {
			kind = strings.ToLower(strings.TrimSpace(identifier.Scheme))
		}
		if normalized := normalizeISBN(value); strings.Contains(kind, "isbn") && normalized != "" {
			out = append(out, "isbn:"+normalized)
		} else if value != "" && kind != "" {
			out = append(out, "external:"+kind+":"+normalizeReadingIdentity(value))
		}
	}
	if len(authors) > 0 {
		out = append(out, metadataIdentity(book.Title, authors, seriesName, seriesIndex))
	}
	return out
}

func metadataIdentity(title string, authors []string, series string, index float64) string {
	return fmt.Sprintf("metadata:%s|%s|%s|%g", normalizeReadingIdentity(title), normalizeReadingIdentity(strings.Join(authors, ",")), normalizeReadingIdentity(series), index)
}
func normalizeReadingIdentity(value string) string {
	return strings.Join(strings.Fields(strings.Map(func(r rune) rune {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return unicode.ToLower(r)
		}
		return ' '
	}, value)), " ")
}
func normalizeISBN(value string) string {
	return strings.Map(func(r rune) rune {
		if unicode.IsDigit(r) || r == 'x' || r == 'X' {
			return unicode.ToLower(r)
		}
		return -1
	}, value)
}

func storytellerAvailability(book storyteller.Book) []string {
	out := []string{}
	if book.Ebook != nil && !book.Ebook.Missing {
		out = append(out, "ebook")
	}
	if book.Audiobook != nil && !book.Audiobook.Missing {
		out = append(out, "audiobook")
	}
	if book.Readaloud != nil && !book.Readaloud.Missing {
		out = append(out, "readaloud")
	}
	return out
}
func creatorNames(values []storyteller.Creator) []string {
	out := []string{}
	for _, value := range values {
		if name := strings.TrimSpace(value.Name); name != "" {
			out = append(out, name)
		}
	}
	return out
}
func storytellerProgress(position *storyteller.Position) *ReadingProgress {
	if position == nil {
		return nil
	}
	value := position.Locator.Locations.TotalProgression
	if value == 0 {
		value = position.Locator.Locations.Progression
	}
	value = clampProgress(value)
	return &ReadingProgress{Percentage: value, Completed: value >= 0.999, UpdatedAt: position.UpdatedAt}
}
func pageProgress(current, total int) *ReadingProgress {
	if total <= 0 {
		return nil
	}
	return &ReadingProgress{Percentage: percentage(current, total), Completed: current >= total, Current: current, Total: total}
}
func percentage(current, total int) float64 {
	if total <= 0 {
		return 0
	}
	return clampProgress(float64(current) / float64(total))
}
func clampProgress(value float64) float64 {
	if value < 0 {
		return 0
	}
	if value > 1 {
		return 1
	}
	return value
}
func readingYear(value string) int {
	if len(value) < 4 {
		return 0
	}
	year, _ := strconv.Atoi(value[:4])
	return year
}
func kavitaFormat(value int) string {
	switch value {
	case 4:
		return "epub"
	case 1:
		return "archive"
	case 3:
		return "pdf"
	default:
		return "unknown"
	}
}
func kavitaVolumeTitle(volume kavita.Volume) string {
	name := strings.TrimSpace(volume.Name)
	if name == "" || name == "-100000" || name == "100000" {
		return "Issues"
	}
	return "Volume " + name
}
func kavitaChapterNumber(value string) string {
	value = strings.TrimSpace(value)
	if value == "-100000" || value == "100000" {
		return ""
	}
	return value
}
func editionID(source, sourceID, kind string) string {
	return fmt.Sprintf("re_%x", simpleDigest(source+"\x00"+sourceID+"\x00"+kind))
}
func simpleDigest(value string) []byte { sum := sha256Sum(value); return sum[:16] }
func sha256Sum(value string) [32]byte  { return sha256.Sum256([]byte(value)) }

func validReadingWorkID(value string) bool {
	if len(value) != 35 || !strings.HasPrefix(value, "rw_") {
		return false
	}
	_, err := hex.DecodeString(strings.TrimPrefix(value, "rw_"))
	return err == nil
}
func readingPartial(service, affects string) Partial {
	return Partial{Service: service, Reason: "upstream_unavailable", Affects: []string{"reading." + affects}, Message: service + " could not be loaded"}
}
func writeReadingCatalogError(w http.ResponseWriter, r *http.Request, err error) {
	slog.Error("reading catalog persistence failed", "error", err, "requestId", RequestIDFrom(r.Context()))
	writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "Could not update the reading catalog"})
}

func sortStorytellerBooks(books []storyteller.Book, sortBy, direction string) {
	sort.SliceStable(books, func(i, j int) bool {
		comparison := 0
		switch sortBy {
		case "added":
			comparison = strings.Compare(books[i].CreatedAt, books[j].CreatedAt)
		case "progress":
			left, right := progressValue(books[i].Position), progressValue(books[j].Position)
			switch {
			case left < right:
				comparison = -1
			case left > right:
				comparison = 1
			}
		default:
			comparison = strings.Compare(strings.ToLower(books[i].Title), strings.ToLower(books[j].Title))
		}
		if comparison == 0 {
			return books[i].ID < books[j].ID
		}
		return comparison < 0 != (direction == "desc")
	})
}
func progressValue(position *storyteller.Position) float64 {
	if position == nil {
		return 0
	}
	if position.Locator.Locations.TotalProgression != 0 {
		return position.Locator.Locations.TotalProgression
	}
	return position.Locator.Locations.Progression
}

func mergeReadingWork(target *ReadingWork, source ReadingWork) {
	target.Editions = append(target.Editions, source.Editions...)
	target.Availability = mergeUnique(target.Availability, source.Availability)
	if target.Overview == "" {
		target.Overview = source.Overview
	}
	if len(target.Authors) == 0 {
		target.Authors = source.Authors
	}
	if len(target.Genres) == 0 {
		target.Genres = source.Genres
	}
	if len(target.Languages) == 0 {
		target.Languages = source.Languages
	}
	if target.Progress == nil {
		target.Progress = source.Progress
	}
	if len(target.Sections) == 0 {
		target.Sections = source.Sections
	}
	if target.Continue == nil {
		target.Continue = source.Continue
	}
}
func mergeUnique(a, b []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, values := range [][]string{a, b} {
		for _, value := range values {
			if value != "" && !seen[value] {
				seen[value] = true
				out = append(out, value)
			}
		}
	}
	return out
}
