package api

// What the hub keeps of a person's own reading life, per Jellyfin profile (#39): the
// import of their Goodreads export, matched to the hub's works, and the values they set
// from an app. Goodreads cannot be written back, so the hub is where a rating or a
// finish date made in an app lives; and it is kept apart from the import, so that a
// second import replaces the first without bringing back a rating that was removed here.
//
// The file is private (mode 0600, beside hub.yaml). It holds the fields the page uses
// for the works that matched, never the export itself: no review, no note, no row that
// matched nothing but a title and author in the report of the last import.

import (
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"

	readingdomain "ayaneohub/internal/reading"
)

// youRecord is what the import says of one work.
type youRecord = readingdomain.GoodreadsRecord

// youImport is a profile's last import: what it said of each work it matched and the
// report it was made with.
type youImport struct {
	ImportedAt string               `json:"importedAt"`
	Report     goodreadsCounts      `json:"report"`
	Matches    []goodreadsMatchItem `json:"matches,omitempty"`
	Unmatched  []goodreadsMissItem  `json:"unmatchedRows,omitempty"`
	Truncated  bool                 `json:"truncated,omitempty"`
	Works      map[string]youRecord `json:"works"`
}

// editInt and editString are a value set from an app, or the fact that it was cleared:
// a cleared value stays cleared when an import says otherwise.
type editInt struct {
	Value   int  `json:"value,omitempty"`
	Cleared bool `json:"cleared,omitempty"`
}

type editString struct {
	Value   string `json:"value,omitempty"`
	Cleared bool   `json:"cleared,omitempty"`
}

// youEdit is what an app set of one work.
type youEdit struct {
	Rating    *editInt    `json:"rating,omitempty"`
	Finished  *editString `json:"finished,omitempty"`
	ReadCount *editInt    `json:"readCount,omitempty"`
}

func (e *youEdit) empty() bool {
	return e == nil || (e.Rating == nil && e.Finished == nil && e.ReadCount == nil)
}

type youProfile struct {
	Import *youImport         `json:"import,omitempty"`
	Edits  map[string]youEdit `json:"edits,omitempty"`
}

type youFile struct {
	Version  int                    `json:"version"`
	Profiles map[string]*youProfile `json:"profiles"`
}

type readingYouStore struct {
	mu       sync.Mutex
	path     string
	profiles map[string]*youProfile
	// loadErr keeps a file that cannot be read from being written over: it holds what
	// cannot be fetched again, a rating set from an app.
	loadErr error
}

// readingYouPath keeps the file beside the hub's other registries.
func readingYouPath(registryPath string) string {
	if strings.TrimSpace(registryPath) == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(registryPath), "reading-you.json")
}

func newReadingYouStore(path string) *readingYouStore {
	store := &readingYouStore{path: path, profiles: map[string]*youProfile{}}
	if path == "" {
		return store
	}
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return store
	}
	if err != nil {
		store.loadErr = fmt.Errorf("reading data: loading %s: %w", path, err)
		slog.Error("reading data unreadable; it will be kept as it is", "path", path, "err", err)
		return store
	}
	var saved youFile
	if err := json.Unmarshal(body, &saved); err != nil || saved.Version != 1 || saved.Profiles == nil {
		store.loadErr = fmt.Errorf("reading data: loading %s: unsupported or malformed file", path)
		slog.Error("reading data unreadable; it will be kept as it is", "path", path)
		return store
	}
	store.profiles = saved.Profiles
	return store
}

// errYouUnavailable is the store refusing to write over a file it could not read.
var errYouUnavailable = errors.New("the reading data file cannot be read")

// view is a profile's import record and edit of one work, as copies.
func (s *readingYouStore) view(profile, workID string) (*youRecord, *youEdit) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.viewLocked(profile, workID)
}

func (s *readingYouStore) viewLocked(profile, workID string) (*youRecord, *youEdit) {
	entry := s.profiles[profile]
	if entry == nil {
		return nil, nil
	}
	var record *youRecord
	if entry.Import != nil {
		if found, ok := entry.Import.Works[workID]; ok {
			copied := found
			copied.Shelves = append([]string(nil), found.Shelves...)
			record = &copied
		}
	}
	var edit *youEdit
	if found, ok := entry.Edits[workID]; ok {
		copied := found
		edit = &copied
	}
	return record, edit
}

// importOf is a profile's last import, or nil.
func (s *readingYouStore) importOf(profile string) *youImport {
	s.mu.Lock()
	defer s.mu.Unlock()
	if entry := s.profiles[profile]; entry != nil && entry.Import != nil {
		copied := *entry.Import
		return &copied
	}
	return nil
}

// replaceImport keeps an import in place of the profile's last; nil forgets it.
func (s *readingYouStore) replaceImport(profile string, imported *youImport) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return errYouUnavailable
	}
	entry := s.profiles[profile]
	if entry == nil {
		entry = &youProfile{}
		s.profiles[profile] = entry
	}
	previous := entry.Import
	entry.Import = imported
	if err := s.saveLocked(); err != nil {
		entry.Import = previous
		s.dropEmptyLocked(profile)
		return err
	}
	s.dropEmptyLocked(profile)
	return nil
}

// update applies a change to a profile's edit of a work, handing it the import's record
// for the work, and keeps the result. The change may refuse, and then nothing is kept.
func (s *readingYouStore) update(profile, workID string, change func(record *youRecord, edit *youEdit)) (*youRecord, *youEdit, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return nil, nil, errYouUnavailable
	}
	record, edit := s.viewLocked(profile, workID)
	if edit == nil {
		edit = &youEdit{}
	}
	change(record, edit)
	entry := s.profiles[profile]
	if entry == nil {
		entry = &youProfile{}
		s.profiles[profile] = entry
	}
	previous, had := entry.Edits[workID]
	if edit.empty() {
		delete(entry.Edits, workID)
	} else {
		if entry.Edits == nil {
			entry.Edits = map[string]youEdit{}
		}
		entry.Edits[workID] = *edit
	}
	if err := s.saveLocked(); err != nil {
		if had {
			entry.Edits[workID] = previous
		} else {
			delete(entry.Edits, workID)
		}
		s.dropEmptyLocked(profile)
		return nil, nil, err
	}
	s.dropEmptyLocked(profile)
	if edit.empty() {
		edit = nil
	}
	return record, edit, nil
}

func (s *readingYouStore) dropEmptyLocked(profile string) {
	if entry := s.profiles[profile]; entry != nil && entry.Import == nil && len(entry.Edits) == 0 {
		delete(s.profiles, profile)
	}
}

func (s *readingYouStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	// A profile with nothing left is not written (it is dropped from memory after the save).
	kept := make(map[string]*youProfile, len(s.profiles))
	for name, entry := range s.profiles {
		if entry != nil && (entry.Import != nil || len(entry.Edits) > 0) {
			kept[name] = entry
		}
	}
	body, err := json.Marshal(youFile{Version: 1, Profiles: kept})
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
