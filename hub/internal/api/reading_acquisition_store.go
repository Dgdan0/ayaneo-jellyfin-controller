package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type readingAcquisitionBook struct {
	ID                 string `json:"id"`
	Title              string `json:"title"`
	Author             string `json:"author,omitempty"`
	ISBN               string `json:"isbn,omitempty"`
	CoverURL           string `json:"coverUrl,omitempty"`
	Year               int    `json:"year,omitempty"`
	Position           int    `json:"position"`
	BookKeeprrSeriesID int    `json:"bookkeeprrSeriesId,omitempty"`
	State              string `json:"state"`
	Error              string `json:"error,omitempty"`
}

type readingAcquisitionManifest struct {
	ID             string                   `json:"id"`
	CandidateKey   string                   `json:"candidateKey"`
	SeriesID       string                   `json:"seriesId"`
	SeriesName     string                   `json:"seriesName"`
	AuthorID       string                   `json:"authorId,omitempty"`
	Author         string                   `json:"author,omitempty"`
	AuthorImageURL string                   `json:"authorImageUrl,omitempty"`
	Description    string                   `json:"description,omitempty"`
	ParentSeriesID int                      `json:"parentSeriesId,omitempty"`
	QualityProfile int                      `json:"qualityProfileId"`
	Monitoring     string                   `json:"monitoring"`
	Roster         []readingAcquisitionBook `json:"roster,omitempty"`
	Books          []readingAcquisitionBook `json:"books"`
	CreatedAt      string                   `json:"createdAt"`
	UpdatedAt      string                   `json:"updatedAt"`
}

type readingAcquisitionRegistry struct {
	Version  int                          `json:"version"`
	Requests []readingAcquisitionManifest `json:"requests"`
}

type readingAcquisitionStore struct {
	mu       sync.Mutex
	path     string
	requests map[string]readingAcquisitionManifest
}

type readingManifestMatch struct {
	SeriesName string
	SeriesID   string
	AuthorID   string
	Author     string
	Position   int
}

func newReadingAcquisitionStore(path string) *readingAcquisitionStore {
	store := &readingAcquisitionStore{path: path, requests: map[string]readingAcquisitionManifest{}}
	store.load()
	return store
}

func readingAcquisitionPath(transfersPath string) string {
	if strings.TrimSpace(transfersPath) == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(transfersPath), "reading-acquisitions.json")
}

func (s *readingAcquisitionStore) begin(
	candidateKey string,
	preview ReadingSeriesPreview,
	selected []ReadingSeriesPreviewBook,
	qualityProfile int,
	monitoring string,
) (readingAcquisitionManifest, error) {
	if s == nil || strings.TrimSpace(preview.SeriesID) == "" || len(selected) == 0 {
		return readingAcquisitionManifest{}, fmt.Errorf("invalid reading acquisition")
	}
	fingerprint := strings.Builder{}
	fingerprint.WriteString(preview.SeriesID)
	fingerprint.WriteByte('|')
	for _, book := range selected {
		fingerprint.WriteString(book.ID)
		fingerprint.WriteByte('|')
	}
	fingerprint.WriteString(fmt.Sprintf("%d|%s", qualityProfile, monitoring))
	digest := sha256.Sum256([]byte(fingerprint.String()))
	id := "ra_" + hex.EncodeToString(digest[:12])

	s.mu.Lock()
	defer s.mu.Unlock()
	if existing, ok := s.requests[id]; ok {
		// Upgrade manifests written before the full verified roster was stored.
		// Replaying an identical request should enrich metadata without creating
		// another provider request or changing its selected Books subset.
		if len(existing.Roster) == 0 && len(preview.FullBooks) > 0 {
			existing.Roster = make([]readingAcquisitionBook, 0, len(preview.FullBooks))
			for _, book := range preview.FullBooks {
				existing.Roster = append(existing.Roster, readingAcquisitionBook{
					ID: book.ID, Title: book.Title, Author: book.Author, ISBN: book.ISBN,
					CoverURL: book.CoverURL, Year: book.Year, Position: book.Position, State: "pending",
				})
			}
			for _, selectedBook := range existing.Books {
				for index := range existing.Roster {
					if existing.Roster[index].ID == selectedBook.ID {
						existing.Roster[index] = selectedBook
						break
					}
				}
			}
			existing.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
			s.requests[id] = existing
			if err := s.saveLocked(); err != nil {
				return readingAcquisitionManifest{}, err
			}
		}
		return cloneReadingManifest(existing), nil
	}
	now := time.Now().UTC().Format(time.RFC3339Nano)
	manifest := readingAcquisitionManifest{
		ID: id, CandidateKey: candidateKey, SeriesID: preview.SeriesID, SeriesName: preview.Name,
		AuthorID: preview.AuthorID, Author: preview.Author, AuthorImageURL: preview.AuthorImageURL,
		Description: preview.Description, QualityProfile: qualityProfile, Monitoring: monitoring,
		Books: make([]readingAcquisitionBook, 0, len(selected)), CreatedAt: now, UpdatedAt: now,
	}
	toStoredBook := func(book ReadingSeriesPreviewBook) readingAcquisitionBook {
		return readingAcquisitionBook{
			ID: book.ID, Title: book.Title, Author: book.Author, ISBN: book.ISBN,
			CoverURL: book.CoverURL, Year: book.Year, Position: book.Position, State: "pending",
		}
	}
	for _, book := range selected {
		manifest.Books = append(manifest.Books, toStoredBook(book))
	}
	roster := preview.FullBooks
	if len(roster) == 0 {
		roster = selected
	}
	manifest.Roster = make([]readingAcquisitionBook, 0, len(roster))
	for _, book := range roster {
		manifest.Roster = append(manifest.Roster, toStoredBook(book))
	}
	s.requests[id] = manifest
	if err := s.saveLocked(); err != nil {
		delete(s.requests, id)
		return readingAcquisitionManifest{}, err
	}
	return cloneReadingManifest(manifest), nil
}

func (s *readingAcquisitionStore) setParent(id string, parentID int) error {
	if s == nil || parentID <= 0 {
		return fmt.Errorf("invalid reading acquisition parent")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	manifest, ok := s.requests[id]
	if !ok {
		return fmt.Errorf("reading acquisition not found")
	}
	manifest.ParentSeriesID = parentID
	manifest.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
	s.requests[id] = manifest
	return s.saveLocked()
}

func (s *readingAcquisitionStore) setBook(id, bookID string, seriesID int, state, message string) error {
	if s == nil {
		return fmt.Errorf("reading acquisition store is unavailable")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	manifest, ok := s.requests[id]
	if !ok {
		return fmt.Errorf("reading acquisition not found")
	}
	found := false
	for index := range manifest.Books {
		if manifest.Books[index].ID != bookID {
			continue
		}
		manifest.Books[index].BookKeeprrSeriesID = seriesID
		manifest.Books[index].State = state
		manifest.Books[index].Error = strings.TrimSpace(message)
		found = true
		break
	}
	for index := range manifest.Roster {
		if manifest.Roster[index].ID != bookID {
			continue
		}
		manifest.Roster[index].BookKeeprrSeriesID = seriesID
		manifest.Roster[index].State = state
		manifest.Roster[index].Error = strings.TrimSpace(message)
		break
	}
	if !found {
		return fmt.Errorf("reading acquisition book not found")
	}
	manifest.UpdatedAt = time.Now().UTC().Format(time.RFC3339Nano)
	s.requests[id] = manifest
	return s.saveLocked()
}

func (s *readingAcquisitionStore) get(id string) (readingAcquisitionManifest, bool) {
	if s == nil {
		return readingAcquisitionManifest{}, false
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	manifest, ok := s.requests[id]
	return cloneReadingManifest(manifest), ok
}

func (s *readingAcquisitionStore) matchBook(
	title string, authors []string, identifiers map[string]string,
) (readingManifestMatch, bool) {
	if s == nil {
		return readingManifestMatch{}, false
	}
	isbn := normalizeISBN(identifiers["isbn"])
	normalizedTitle := normalizeReadingIdentity(title)
	normalizedAuthors := normalizeReadingIdentity(strings.Join(authors, ","))
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, manifest := range s.requests {
		for _, book := range manifest.Books {
			if book.State != "accepted" && book.State != "present" {
				continue
			}
			bookISBN := normalizeISBN(book.ISBN)
			strong := isbn != "" && bookISBN != "" && isbn == bookISBN
			expectedTitle := normalizeReadingIdentity(book.Title)
			expectedAuthor := normalizeReadingIdentity(book.Author)
			strongAuthorMatch := normalizedAuthors != "" && expectedAuthor != "" &&
				strings.Contains(normalizedAuthors, expectedAuthor)
			titleMatch := normalizedTitle != "" && normalizedTitle == expectedTitle
			// EPUB metadata often prefixes a volume title with the series name,
			// for example "Mistborn: The Final Empire". Accept that form only
			// when the manifest author also matches so generic suffixes cannot
			// merge unrelated books into a requested collection.
			if !titleMatch && strongAuthorMatch && len(strings.Fields(expectedTitle)) >= 2 {
				titleMatch = strings.HasSuffix(normalizedTitle, " "+expectedTitle)
			}
			authorMatch := normalizedAuthors == "" || expectedAuthor == "" || strongAuthorMatch
			if !strong && !(titleMatch && authorMatch) {
				continue
			}
			return readingManifestMatch{
				SeriesName: manifest.SeriesName, SeriesID: manifest.SeriesID,
				AuthorID: manifest.AuthorID, Author: manifest.Author, Position: book.Position,
			}, true
		}
	}
	return readingManifestMatch{}, false
}

// seriesRoster combines every acquisition of the same verified series. This
// makes the catalog useful while downloads are still arriving and after a user
// requests a second subset later. Newer manifests replace older metadata for
// the same Open Library work, while the series order remains deterministic.
func (s *readingAcquisitionStore) seriesRoster(
	seriesID, name string, authors []string,
) (readingAcquisitionManifest, bool) {
	if s == nil {
		return readingAcquisitionManifest{}, false
	}
	normalizedName := normalizeReadingIdentity(name)
	normalizedAuthors := normalizeReadingIdentity(strings.Join(authors, ","))
	s.mu.Lock()
	defer s.mu.Unlock()
	matches := make([]readingAcquisitionManifest, 0)
	for _, manifest := range s.requests {
		direct := seriesID != "" && manifest.SeriesID == seriesID
		nameMatch := normalizedName != "" && normalizeReadingIdentity(manifest.SeriesName) == normalizedName
		authorMatch := normalizedAuthors == "" || normalizeReadingIdentity(manifest.Author) == "" ||
			strings.Contains(normalizedAuthors, normalizeReadingIdentity(manifest.Author))
		if direct || (nameMatch && authorMatch) {
			matches = append(matches, manifest)
		}
	}
	if len(matches) == 0 {
		return readingAcquisitionManifest{}, false
	}
	sort.Slice(matches, func(i, j int) bool { return matches[i].UpdatedAt < matches[j].UpdatedAt })
	result := matches[len(matches)-1]
	byID := map[string]readingAcquisitionBook{}
	for _, manifest := range matches {
		roster := manifest.Roster
		if len(roster) == 0 {
			roster = manifest.Books
		}
		for _, book := range roster {
			byID[book.ID] = book
		}
	}
	result.Roster = make([]readingAcquisitionBook, 0, len(byID))
	for _, book := range byID {
		result.Roster = append(result.Roster, book)
	}
	sort.SliceStable(result.Roster, func(i, j int) bool {
		if result.Roster[i].Position != result.Roster[j].Position {
			return result.Roster[i].Position < result.Roster[j].Position
		}
		return result.Roster[i].Title < result.Roster[j].Title
	})
	return cloneReadingManifest(result), true
}

func (s *readingAcquisitionStore) load() {
	if s == nil || s.path == "" {
		return
	}
	body, err := os.ReadFile(s.path)
	if err != nil {
		return
	}
	var registry readingAcquisitionRegistry
	if json.Unmarshal(body, &registry) != nil || registry.Version != 1 {
		return
	}
	for _, manifest := range registry.Requests {
		if manifest.ID != "" {
			s.requests[manifest.ID] = manifest
		}
	}
}

func (s *readingAcquisitionStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	registry := readingAcquisitionRegistry{Version: 1, Requests: make([]readingAcquisitionManifest, 0, len(s.requests))}
	for _, manifest := range s.requests {
		registry.Requests = append(registry.Requests, manifest)
	}
	sort.Slice(registry.Requests, func(i, j int) bool { return registry.Requests[i].ID < registry.Requests[j].ID })
	body, err := json.MarshalIndent(registry, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	temporary := s.path + ".tmp"
	if err := os.WriteFile(temporary, append(body, '\n'), 0o600); err != nil {
		return err
	}
	if err := os.Rename(temporary, s.path); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}

func cloneReadingManifest(value readingAcquisitionManifest) readingAcquisitionManifest {
	value.Books = append([]readingAcquisitionBook(nil), value.Books...)
	value.Roster = append([]readingAcquisitionBook(nil), value.Roster...)
	return value
}
