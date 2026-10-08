package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/adapters/storyteller"
)

// This worker automates Storyteller's supported processing route. It never
// guesses whether two separate catalog records are matching editions or merges
// source books. Only a book already paired by Storyteller can be queued.
type readingAlignmentStore struct {
	mu          sync.Mutex
	path        string
	initialized bool
	seen        map[string]bool
	loadErr     error
}

type readingAlignmentRegistry struct {
	Version int      `json:"version"`
	Seen    []string `json:"seen"`
}

func readingAlignmentPath(transfersPath string) string {
	if strings.TrimSpace(transfersPath) == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(transfersPath), "reading-alignments.json")
}

func newReadingAlignmentStore(path string) *readingAlignmentStore {
	store := &readingAlignmentStore{path: path, seen: map[string]bool{}}
	if path == "" {
		return store
	}
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store
	}
	if err != nil {
		store.loadErr = err
		return store
	}
	var registry readingAlignmentRegistry
	if err := json.Unmarshal(body, &registry); err != nil || registry.Version != 1 {
		store.loadErr = fmt.Errorf("invalid readaloud registry at %s", path)
		return store
	}
	for _, id := range registry.Seen {
		if id != "" {
			store.seen[id] = true
		}
	}
	store.initialized = true
	return store
}

func (s *readingAlignmentStore) ready() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.initialized
}

func (s *readingAlignmentStore) contains(id string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.seen[id]
}

func (s *readingAlignmentStore) initialize(books []storyteller.Book) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return s.loadErr
	}
	if s.initialized {
		return nil
	}
	for _, book := range books {
		if completeStorytellerPair(book) {
			s.seen[storytellerBookKey(book)] = true
		}
	}
	if err := s.saveLocked(); err != nil {
		return err
	}
	s.initialized = true
	return nil
}

func (s *readingAlignmentStore) mark(book storyteller.Book) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return s.loadErr
	}
	key := storytellerBookKey(book)
	if s.seen[key] {
		return nil
	}
	s.seen[key] = true
	if err := s.saveLocked(); err != nil {
		delete(s.seen, key)
		return err
	}
	return nil
}

func (s *readingAlignmentStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	ids := make([]string, 0, len(s.seen))
	for id := range s.seen {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	body, err := json.MarshalIndent(readingAlignmentRegistry{Version: 1, Seen: ids}, "", "  ")
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

func storytellerBookKey(book storyteller.Book) string {
	if book.UUID != "" {
		return book.UUID
	}
	return strconv.FormatInt(book.ID, 10)
}

func completeStorytellerPair(book storyteller.Book) bool {
	return book.ID > 0 && book.Ebook != nil && book.Ebook.UUID != "" && !book.Ebook.Missing &&
		book.Audiobook != nil && book.Audiobook.UUID != "" && !book.Audiobook.Missing
}

func activeReadaloud(book storyteller.Book) bool {
	if book.Readaloud == nil {
		return false
	}
	switch strings.ToUpper(book.Readaloud.Status) {
	case "", "ALIGNED", "ERROR", "STOPPED":
		return false
	default:
		return true
	}
}

func (s *Server) reconcileReadingAlignments(parent context.Context) error {
	if s.storyteller == nil || s.readingAlignments == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	books, err := s.storyteller.Books(ctx)
	if err != nil {
		return err
	}
	// This is the one read of Storyteller's list that never comes from the cache,
	// every minute. Keep it for the screens, and drop the record of any book whose
	// read-along (or files) it shows moved since that record was read.
	s.cache.Set(storytellerBooksKey, append([]storyteller.Book(nil), books...))
	s.followStorytellerList(books)
	if !s.readingAlignments.ready() {
		return s.readingAlignments.initialize(books)
	}
	for _, book := range books {
		if completeStorytellerPair(book) && book.Readaloud != nil {
			if err := s.readingAlignments.mark(book); err != nil {
				return err
			}
			if activeReadaloud(book) {
				return nil // one alignment at a time on the media PC
			}
		}
	}
	for _, book := range books {
		if !completeStorytellerPair(book) || book.Readaloud != nil || s.readingAlignments.contains(storytellerBookKey(book)) {
			continue
		}
		if err := s.storyteller.StartReadaloud(ctx, book.ID); err != nil {
			return fmt.Errorf("queue readaloud for Storyteller book %d: %w", book.ID, err)
		}
		if err := s.readingAlignments.mark(book); err != nil {
			return fmt.Errorf("save readaloud submission for Storyteller book %d: %w", book.ID, err)
		}
		s.invalidateReadingCatalog()
		slog.Info("readaloud queued", "storytellerBookId", book.ID)
		return nil
	}
	return nil
}
