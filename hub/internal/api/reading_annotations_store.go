package api

// What the hub keeps of a person's highlights and notes (#62), per Jellyfin profile and per work, so the ebook and
// its read-along share them and every device has them.
//
// An annotation's anchor is edition-independent: the document's path in one spelling (decoded, NFC, no fragment,
// no leading slash: the app's DocumentPath, #61) and a text quote with a few words either side, which an edition
// that does not have the same markup can still be searched for. Readium's locator is kept beside as a hint only. The
// colour, the note and the stamps travel with it, and a delete leaves a tombstone, so a device that was offline
// learns of it. Last write wins on updatedAt, the stamp the writer says its edit was made at (an outbox sends an edit
// made an hour ago an hour late); a writer's clock cannot win for ever, since a stamp ahead of the hub's own by more than
// a few minutes is taken as the hub's now.
//
// A device asks "what changed since" by [ReadingAnnotation.SyncedAt], the hub's own clock when it stored that version, never by
// updatedAt: an edit made offline an hour ago and sent now carries an old updatedAt and is still news to a device that last
// asked ten minutes ago.
//
// The file is private (mode 0600, beside the hub's other registries) and, like reading-you.json, is never written
// over when it cannot be read: it holds what cannot be fetched again.

import (
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

const (
	// readingAnnotationLimit is the most highlights and notes a profile keeps for one work.
	readingAnnotationLimit = 500
	// readingTombstoneKeep is how long a deleted one is remembered: a year, by when no offline outbox is still holding an edit of it.
	readingTombstoneKeep = int64(365 * 24 * 3600 * 1000)
)

// ReadingAnnotationQuote is the passage and its neighbours, as the page's text says it.
type ReadingAnnotationQuote struct {
	Before    string `json:"before,omitempty"`
	Highlight string `json:"highlight"`
	After     string `json:"after,omitempty"`
}

// ReadingAnnotation is one highlight, with its note when it has one. A tombstone (Deleted) keeps the anchor and drops the note.
type ReadingAnnotation struct {
	ID        string                 `json:"id"`
	Color     string                 `json:"color,omitempty"`
	Note      string                 `json:"note,omitempty"`
	Document  string                 `json:"document,omitempty"`
	Quote     ReadingAnnotationQuote `json:"quote"`
	Locator   json.RawMessage        `json:"locator,omitempty"`
	CreatedAt int64                  `json:"createdAt"`
	UpdatedAt int64                  `json:"updatedAt"`
	Deleted   bool                   `json:"deleted,omitempty"`
	// SyncedAt is when the hub stored this version, on its own clock and never twice the same: what "since" counts.
	SyncedAt int64 `json:"syncedAt"`
}

type annotationsFile struct {
	Version  int                                                `json:"version"`
	Profiles map[string]map[string]map[string]ReadingAnnotation `json:"profiles"`
}

type readingAnnotationStore struct {
	mu       sync.Mutex
	path     string
	profiles map[string]map[string]map[string]ReadingAnnotation
	// clock is the latest SyncedAt handed out.
	clock int64
	// loadErr keeps a file that cannot be read from being written over.
	loadErr error
}

// readingAnnotationsPath keeps the file beside the hub's other registries.
func readingAnnotationsPath(registryPath string) string {
	if strings.TrimSpace(registryPath) == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(registryPath), "reading-annotations.json")
}

func newReadingAnnotationStore(path string) *readingAnnotationStore {
	store := &readingAnnotationStore{path: path, profiles: map[string]map[string]map[string]ReadingAnnotation{}}
	if path == "" {
		return store
	}
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store
	}
	if err != nil {
		store.loadErr = fmt.Errorf("reading annotations: loading %s: %w", path, err)
		slog.Error("reading annotations unreadable; they will be kept as they are", "path", path, "err", err)
		return store
	}
	var saved annotationsFile
	if err := json.Unmarshal(body, &saved); err != nil || saved.Version != 1 || saved.Profiles == nil {
		store.loadErr = fmt.Errorf("reading annotations: loading %s: unsupported or malformed file", path)
		slog.Error("reading annotations unreadable; they will be kept as they are", "path", path)
		return store
	}
	store.profiles = saved.Profiles
	for _, works := range saved.Profiles {
		for _, book := range works {
			for _, annotation := range book {
				store.clock = max(store.clock, annotation.SyncedAt)
			}
		}
	}
	return store
}

var errAnnotationsUnavailable = errors.New("the reading annotations file cannot be read")

// errAnnotationLimit is a profile's highlights of one work being at the limit.
var errAnnotationLimit = errors.New("this book already has the most highlights it can keep")

// list is a profile's annotations of a work. With [since] (>= 0) it is what the hub stored after that moment on its own
// clock, tombstones too, for a device that keeps its own copy; without, the ones that are there, oldest first.
func (s *readingAnnotationStore) list(profile, workID string, since *int64) []ReadingAnnotation {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []ReadingAnnotation{}
	for _, annotation := range s.profiles[profile][workID] {
		switch {
		case since != nil && annotation.SyncedAt > *since:
			out = append(out, annotation)
		case since == nil && !annotation.Deleted:
			out = append(out, annotation)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt != out[j].CreatedAt {
			return out[i].CreatedAt < out[j].CreatedAt
		}
		return out[i].ID < out[j].ID
	})
	return out
}

// put keeps [incoming] when it is newer than what is held under its id (or nothing is), and says whether it did; the
// answer is what the hub holds now, which an app that lost adopts. A tombstone and an edit are the same write.
func (s *readingAnnotationStore) put(profile, workID string, incoming ReadingAnnotation, now int64) (ReadingAnnotation, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return ReadingAnnotation{}, false, errAnnotationsUnavailable
	}
	held, had := s.profiles[profile][workID][incoming.ID]
	if had && incoming.UpdatedAt <= held.UpdatedAt {
		return held, false, nil
	}
	if had {
		incoming.CreatedAt = held.CreatedAt
		// A tombstone that was never given an anchor of its own keeps the one it had.
		if incoming.Deleted && incoming.Document == "" {
			incoming.Document, incoming.Quote, incoming.Locator, incoming.Color = held.Document, held.Quote, held.Locator, held.Color
		}
	} else if !incoming.Deleted && s.liveLocked(profile, workID) >= readingAnnotationLimit {
		return ReadingAnnotation{}, false, errAnnotationLimit
	}
	if incoming.CreatedAt == 0 {
		incoming.CreatedAt = incoming.UpdatedAt
	}
	previousClock := s.clock
	incoming.SyncedAt = max(now, s.clock+1)
	s.clock = incoming.SyncedAt
	works := s.profiles[profile]
	if works == nil {
		works = map[string]map[string]ReadingAnnotation{}
		s.profiles[profile] = works
	}
	book := works[workID]
	if book == nil {
		book = map[string]ReadingAnnotation{}
		works[workID] = book
	}
	previous, hadPrevious := book[incoming.ID]
	book[incoming.ID] = incoming
	pruned := s.pruneLocked(book, now)
	if err := s.saveLocked(); err != nil {
		if hadPrevious {
			book[incoming.ID] = previous
		} else {
			delete(book, incoming.ID)
		}
		for id, gone := range pruned {
			book[id] = gone
		}
		s.clock = previousClock
		return ReadingAnnotation{}, false, err
	}
	return incoming, true, nil
}

func (s *readingAnnotationStore) liveLocked(profile, workID string) int {
	live := 0
	for _, annotation := range s.profiles[profile][workID] {
		if !annotation.Deleted {
			live++
		}
	}
	return live
}

// pruneLocked forgets the tombstones older than [readingTombstoneKeep]; what it dropped, to put back if the save fails.
func (s *readingAnnotationStore) pruneLocked(book map[string]ReadingAnnotation, now int64) map[string]ReadingAnnotation {
	gone := map[string]ReadingAnnotation{}
	for id, annotation := range book {
		if annotation.Deleted && now > 0 && annotation.SyncedAt < now-readingTombstoneKeep {
			gone[id] = annotation
			delete(book, id)
		}
	}
	return gone
}

func (s *readingAnnotationStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	body, err := json.Marshal(annotationsFile{Version: 1, Profiles: s.profiles})
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
