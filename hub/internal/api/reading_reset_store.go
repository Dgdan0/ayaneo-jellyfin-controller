package api

// What the hub keeps of books started over (#60).
//
// Storyteller has no way to delete a place. Its position routes are a GET and a POST that
// replaces the one row of an account and a book with a newer one (checked in its source, route.js
// and database/positions.ts: no DELETE, nothing in its status route touches positions, only
// deleting the user or the book removes one). So starting a book over cannot remove the row.
// The hub records a stamp instead, and a place older than the stamp is as good as gone: every
// reader of a place goes through [readingResetStore.gone], so no screen, shelf, series or
// device is told of it. A place written after the stamp (the hub stamps every write itself, and
// never before the reset) is a new place.
//
// The stamp is also what a device is told, in resetAt, so that it can drop what it keeps of the
// place (its checkpoint, its outbox, its downloaded copy) and so that a write made from a place
// that has been started over can be refused with its own code. It is kept per work, for the
// apps, and per Storyteller book and Kavita series that made the work up, for the readers.
//
// The file is private (mode 0600, beside the hub's other registries).

import (
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type resetFile struct {
	Version int              `json:"version"`
	Works   map[string]int64 `json:"works"`
	Sources map[string]int64 `json:"sources"`
}

type readingResetStore struct {
	mu      sync.Mutex
	path    string
	works   map[string]int64
	sources map[string]int64
	// loadErr keeps a file that cannot be read from being written over.
	loadErr error
}

// readingResetPath keeps the file beside the hub's other registries.
func readingResetPath(registryPath string) string {
	if strings.TrimSpace(registryPath) == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(registryPath), "reading-resets.json")
}

func resetSourceKey(source, id string) string { return source + ":" + id }

func newReadingResetStore(path string) *readingResetStore {
	store := &readingResetStore{path: path, works: map[string]int64{}, sources: map[string]int64{}}
	if path == "" {
		return store
	}
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store
	}
	if err != nil {
		store.loadErr = fmt.Errorf("reading resets: loading %s: %w", path, err)
		slog.Error("reading resets unreadable; they will be kept as they are", "path", path, "err", err)
		return store
	}
	var saved resetFile
	if err := json.Unmarshal(body, &saved); err != nil || saved.Version != 1 {
		store.loadErr = fmt.Errorf("reading resets: loading %s: unsupported or malformed file", path)
		slog.Error("reading resets unreadable; they will be kept as they are", "path", path)
		return store
	}
	for key, at := range saved.Works {
		store.works[key] = at
	}
	for key, at := range saved.Sources {
		store.sources[key] = at
	}
	return store
}

// errResetsUnavailable is the store refusing to write over a file it could not read.
var errResetsUnavailable = errors.New("the reading resets file cannot be read")

// work is when a work was last started over, in the hub's milliseconds; 0 when never.
func (s *readingResetStore) work(workID string) int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.works[workID]
}

// source is the same for one Storyteller book or Kavita series.
func (s *readingResetStore) source(source, id string) int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sources[resetSourceKey(source, id)]
}

// any is whether anything was ever started over, which is the cheap check that lets every
// reader of a place skip the rest.
func (s *readingResetStore) any() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.sources) > 0
}

// gone is whether a place stamped [timestamp] in a source was written before it was started over.
func (s *readingResetStore) gone(source, id string, timestamp int64) bool {
	at := s.source(source, id)
	return at > 0 && timestamp < at
}

// record keeps that a work, and the sources it is made of ("storyteller:12", "kavita:9"), were
// started over at [at]. A stamp never moves back: a device that has seen a later reset must not
// be taken to an earlier one.
func (s *readingResetStore) record(workID string, at int64, sourceKeys []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return errResetsUnavailable
	}
	previousWork, hadWork := s.works[workID]
	previousSources := map[string]int64{}
	hadSources := map[string]bool{}
	set := func(table map[string]int64, key string) {
		if at > table[key] {
			table[key] = at
		}
	}
	set(s.works, workID)
	for _, key := range sourceKeys {
		if value, ok := s.sources[key]; ok {
			previousSources[key], hadSources[key] = value, true
		}
		set(s.sources, key)
	}
	if err := s.saveLocked(); err != nil {
		if hadWork {
			s.works[workID] = previousWork
		} else {
			delete(s.works, workID)
		}
		for _, key := range sourceKeys {
			if hadSources[key] {
				s.sources[key] = previousSources[key]
			} else {
				delete(s.sources, key)
			}
		}
		return err
	}
	return nil
}

func (s *readingResetStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	body, err := json.Marshal(resetFile{Version: 1, Works: s.works, Sources: s.sources})
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
