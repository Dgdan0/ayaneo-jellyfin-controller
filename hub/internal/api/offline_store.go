package api

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// offlineGrant is the durable capability behind one original-file download.
// It contains only Jellyfin IDs and validated relative resource paths; neither
// the Jellyfin API key nor a filesystem path is ever written to this registry.
type offlineGrant struct {
	ID            string            `json:"id"`
	Owner         string            `json:"owner"`
	UserID        string            `json:"userId"`
	BatchKey      string            `json:"batchKey"`
	ClientItemKey string            `json:"clientItemKey"`
	ItemID        string            `json:"itemId"`
	SeriesID      string            `json:"seriesId,omitempty"`
	MediaSourceID string            `json:"mediaSourceId"`
	Resource      string            `json:"resource"`
	Subtitles     map[string]string `json:"subtitles,omitempty"`
	Manifest      OfflineManifest   `json:"manifest"`
	CreatedAt     int64             `json:"createdAt"`
	ExpiresAt     int64             `json:"expiresAt"`
}

type offlineRegistryFile struct {
	Version int                     `json:"version"`
	Grants  map[string]offlineGrant `json:"grants"`
	Synced  map[string]int64        `json:"synced"`
}

type offlineStore struct {
	mu     sync.Mutex
	path   string
	grants map[string]offlineGrant
	synced map[string]int64
}

func newOfflineStore(path string) *offlineStore {
	s := &offlineStore{
		path:   path,
		grants: make(map[string]offlineGrant),
		synced: make(map[string]int64),
	}
	if path == "" {
		return s
	}
	body, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return s
	}
	if err != nil {
		return s
	}
	var disk offlineRegistryFile
	if json.Unmarshal(body, &disk) == nil {
		if disk.Grants != nil {
			s.grants = disk.Grants
		}
		if disk.Synced != nil {
			s.synced = disk.Synced
		}
	}
	return s
}

func (s *offlineStore) get(id string) (offlineGrant, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	value, ok := s.grants[id]
	return value, ok
}

func (s *offlineStore) find(owner, userID, clientItemKey string) (offlineGrant, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, value := range s.grants {
		if value.Owner == owner && value.UserID == userID && value.ClientItemKey == clientItemKey {
			return value, true
		}
	}
	return offlineGrant{}, false
}

func (s *offlineStore) putMany(values []offlineGrant) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, value := range values {
		s.grants[value.ID] = value
	}
	return s.saveLocked()
}

func (s *offlineStore) put(value offlineGrant) error {
	return s.putMany([]offlineGrant{value})
}

func (s *offlineStore) wasSynced(key string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.synced[key]
	return ok
}

func (s *offlineStore) markSynced(key string, at int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.synced[key] = at
	cutoff := time.Now().Add(-90 * 24 * time.Hour).UnixMilli()
	for id, timestamp := range s.synced {
		if timestamp < cutoff {
			delete(s.synced, id)
		}
	}
	return s.saveLocked()
}

func (s *offlineStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	body, err := json.MarshalIndent(offlineRegistryFile{
		Version: 1,
		Grants:  s.grants,
		Synced:  s.synced,
	}, "", "  ")
	if err != nil {
		return err
	}
	temporary := s.path + ".tmp"
	if err := os.WriteFile(temporary, body, 0o600); err != nil {
		return err
	}
	if err := os.Rename(temporary, s.path); err != nil {
		_ = os.Remove(temporary)
		return err
	}
	return nil
}
