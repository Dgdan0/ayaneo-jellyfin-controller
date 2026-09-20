package reading

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

// WorkBinding describes one upstream record and the identities that can link
// it to the same conceptual work in another reader service. Identity keys must
// already be normalized and prefixed (for example isbn: or metadata:).
type WorkBinding struct {
	Source       string
	SourceID     string
	IdentityKeys []string
}

type SourceRef struct {
	Source   string `json:"source"`
	SourceID string `json:"sourceId"`
}

type CatalogWork struct {
	ID           string      `json:"id"`
	IdentityKeys []string    `json:"identityKeys,omitempty"`
	Sources      []SourceRef `json:"sources"`
}

type catalogFile struct {
	Version int                    `json:"version"`
	Works   map[string]CatalogWork `json:"works"`
}

// CatalogStore is the durable bridge between source-specific records and Hub
// work IDs. The random Hub ID survives service migrations; a newly imported
// source record reconnects to it through a strong identifier or normalized
// metadata identity.
type CatalogStore struct {
	mu    sync.Mutex
	path  string
	works map[string]CatalogWork
	refs  map[string]string
	// loadErr keeps a malformed or unreadable catalog fail-closed. Without it,
	// the next bind could replace durable work IDs with an apparently valid new
	// file and break saved links on every client.
	loadErr error
}

func NewCatalogStore(path string) *CatalogStore {
	s := &CatalogStore{path: path, works: map[string]CatalogWork{}, refs: map[string]string{}}
	if path == "" {
		return s
	}
	body, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			s.loadErr = fmt.Errorf("reading catalog: loading %s: %w", path, err)
		}
		return s
	}
	var disk catalogFile
	if err := json.Unmarshal(body, &disk); err != nil {
		s.loadErr = fmt.Errorf("reading catalog: loading %s: %w", path, err)
		return s
	}
	if disk.Version != 1 || disk.Works == nil {
		s.loadErr = fmt.Errorf("reading catalog: loading %s: unsupported or incomplete state", path)
		return s
	}
	s.works = disk.Works
	s.rebuildRefsLocked()
	return s
}

func (s *CatalogStore) Bind(binding WorkBinding) (string, error) {
	binding.Source = strings.TrimSpace(strings.ToLower(binding.Source))
	binding.SourceID = strings.TrimSpace(binding.SourceID)
	if binding.Source == "" || binding.SourceID == "" {
		return "", errors.New("reading catalog: source and source id are required")
	}
	binding.IdentityKeys = normalizeIdentityKeys(binding.IdentityKeys)

	s.mu.Lock()
	defer s.mu.Unlock()
	if s.loadErr != nil {
		return "", s.loadErr
	}
	refKey := sourceRefKey(binding.Source, binding.SourceID)
	if id := s.refs[refKey]; id != "" {
		work := s.works[id]
		work.IdentityKeys = mergeStrings(work.IdentityKeys, binding.IdentityKeys)
		s.works[id] = work
		return id, s.saveLocked()
	}

	identityIndex := s.identityIndexLocked()
	matched := ""
	for _, key := range binding.IdentityKeys {
		if !strongIdentity(key) {
			continue
		}
		ids := identityIndex[key]
		if len(ids) != 1 {
			continue
		}
		for id := range ids {
			if matched == "" || matched == id {
				matched = id
			} else {
				// Different strong keys point at different works. That is an
				// ambiguous match, so keep this source record separate.
				matched = ""
				break
			}
		}
		if matched == "" && len(ids) == 1 {
			break
		}
	}
	if matched == "" {
		var err error
		matched, err = newWorkID()
		if err != nil {
			return "", err
		}
		s.works[matched] = CatalogWork{ID: matched, Sources: []SourceRef{}}
	}
	work := s.works[matched]
	work.IdentityKeys = mergeStrings(work.IdentityKeys, binding.IdentityKeys)
	work.Sources = append(work.Sources, SourceRef{Source: binding.Source, SourceID: binding.SourceID})
	s.works[matched] = work
	s.refs[refKey] = matched
	return matched, s.saveLocked()
}

func (s *CatalogStore) Resolve(id string) (CatalogWork, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	work, ok := s.works[id]
	if !ok {
		return CatalogWork{}, false
	}
	work.IdentityKeys = append([]string(nil), work.IdentityKeys...)
	work.Sources = append([]SourceRef(nil), work.Sources...)
	return work, true
}

func (s *CatalogStore) WorkIDFor(source, sourceID string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	id, ok := s.refs[sourceRefKey(strings.ToLower(strings.TrimSpace(source)), strings.TrimSpace(sourceID))]
	return id, ok
}

func (s *CatalogStore) rebuildRefsLocked() {
	s.refs = make(map[string]string)
	for id, work := range s.works {
		for _, source := range work.Sources {
			s.refs[sourceRefKey(source.Source, source.SourceID)] = id
		}
	}
}

func (s *CatalogStore) identityIndexLocked() map[string]map[string]struct{} {
	index := map[string]map[string]struct{}{}
	for id, work := range s.works {
		for _, key := range work.IdentityKeys {
			if !strongIdentity(key) {
				continue
			}
			if index[key] == nil {
				index[key] = map[string]struct{}{}
			}
			index[key][id] = struct{}{}
		}
	}
	return index
}

func (s *CatalogStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	body, err := json.MarshalIndent(catalogFile{Version: 1, Works: s.works}, "", "  ")
	if err != nil {
		return err
	}
	temporary := s.path + ".tmp"
	if err := os.WriteFile(temporary, body, 0o600); err != nil {
		return err
	}
	if err := os.Rename(temporary, s.path); err == nil {
		return nil
	}
	// Windows does not replace an existing destination with os.Rename.
	if err := os.Remove(s.path); err != nil && !errors.Is(err, os.ErrNotExist) {
		_ = os.Remove(temporary)
		return err
	}
	if err := os.Rename(temporary, s.path); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}

func sourceRefKey(source, id string) string { return source + "\x00" + id }

func strongIdentity(key string) bool {
	return strings.HasPrefix(key, "isbn:") || strings.HasPrefix(key, "asin:") ||
		strings.HasPrefix(key, "external:") || strings.HasPrefix(key, "metadata:")
}

func normalizeIdentityKeys(values []string) []string {
	out := make([]string, 0, len(values))
	seen := map[string]bool{}
	for _, value := range values {
		value = strings.ToLower(strings.Join(strings.Fields(strings.TrimSpace(value)), " "))
		if value == "" || seen[value] {
			continue
		}
		seen[value] = true
		out = append(out, value)
	}
	return out
}

func mergeStrings(a, b []string) []string {
	merged := normalizeIdentityKeys(append(append([]string(nil), a...), b...))
	sort.Strings(merged)
	return merged
}

func newWorkID() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return "rw_" + hex.EncodeToString(raw), nil
}
