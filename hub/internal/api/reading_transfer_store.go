package api

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"ayaneohub/internal/adapters/bookkeeprr"
)

var (
	errReadingTransferNotFound = errors.New("reading transfer not found")
	errReadingTransferState    = errors.New("reading transfer cannot perform that action")
	errReadingTransferBusy     = errors.New("reading transfer action is already running")
)

// readingTransferRecord is the private half of a Books transfer. The app sees
// only Public.ID; the qBittorrent hash and BookKeeprr release id never leave the
// Hub. RetryPending survives a Hub restart after the old failed row has been
// removed but before the replacement grab succeeds.
type readingTransferRecord struct {
	Public               ReadingDownloadItem `json:"public"`
	UpstreamID           int                 `json:"upstreamId,omitempty"`
	SupersededUpstreamID int                 `json:"supersededUpstreamId,omitempty"`
	QBTHash              string              `json:"qbtHash,omitempty"`
	ReleaseID            int                 `json:"releaseId,omitempty"`
	RetryPending         bool                `json:"retryPending,omitempty"`
	AwaitingUpstream     bool                `json:"awaitingUpstream,omitempty"`
	Canceled             bool                `json:"canceled,omitempty"`
	UpdatedAt            int64               `json:"updatedAt"`
}

type readingTransferFile struct {
	Version int                              `json:"version"`
	Items   map[string]readingTransferRecord `json:"items"`
}

type readingTransferStore struct {
	mu    sync.Mutex
	path  string
	items map[string]readingTransferRecord
}

func newReadingTransferStore(path string) *readingTransferStore {
	s := &readingTransferStore{path: path, items: map[string]readingTransferRecord{}}
	if path == "" {
		return s
	}
	body, err := os.ReadFile(path)
	if err != nil {
		return s
	}
	var disk readingTransferFile
	if json.Unmarshal(body, &disk) == nil && disk.Items != nil {
		s.items = disk.Items
		// A process can stop during the network call. On restart this is a
		// recoverable pending retry, rather than a permanently busy row.
		for id, item := range s.items {
			if item.Public.Status == "retrying" {
				item.Public.Status = "retry_pending"
				item.Public.Failed = true
				item.RetryPending = true
				s.items[id] = item
			}
		}
	}
	return s
}

func (s *readingTransferStore) reconcile(downloads []bookkeeprr.Download) ([]ReadingDownloadItem, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	byUpstream := map[int]string{}
	superseded := map[int]bool{}
	for id, item := range s.items {
		if item.UpstreamID > 0 && !item.Canceled {
			byUpstream[item.UpstreamID] = id
		}
		if item.SupersededUpstreamID > 0 {
			superseded[item.SupersededUpstreamID] = true
		}
	}
	seen := map[string]bool{}
	out := make([]ReadingDownloadItem, 0, len(downloads)+2)
	for _, download := range downloads {
		// BookKeeprr's delete is synchronous, but a proxy or replica can briefly
		// return the old row. Never resurrect the attempt we just replaced.
		if superseded[download.ID] {
			continue
		}
		id := byUpstream[download.ID]
		if id == "" && download.Release != nil {
			// A grab may have succeeded after the HTTP response was lost. Bind
			// that live row to its durable retry ticket instead of grabbing twice.
			for candidateID, candidate := range s.items {
				if candidate.RetryPending && !candidate.Canceled && candidate.ReleaseID == download.Release.ID && candidate.SupersededUpstreamID != download.ID {
					id = candidateID
					break
				}
			}
		}
		if id == "" {
			var err error
			id, err = randomReadingTransferID()
			if err != nil {
				return nil, err
			}
		}
		item := readingDownloadItem(id, download)
		record := s.items[id]
		record.Public = item
		record.UpstreamID = download.ID
		record.QBTHash = download.QBTHash
		if download.Release != nil {
			record.ReleaseID = download.Release.ID
		}
		record.RetryPending = false
		record.AwaitingUpstream = false
		record.Canceled = false
		record.UpdatedAt = time.Now().UnixMilli()
		s.items[id] = record
		seen[id] = true
		out = append(out, item)
	}
	for id, record := range s.items {
		if record.Canceled || seen[id] || (!record.RetryPending && !record.AwaitingUpstream) {
			continue
		}
		if record.AwaitingUpstream && record.UpdatedAt < time.Now().Add(-10*time.Minute).UnixMilli() {
			record.AwaitingUpstream = false
			s.items[id] = record
			continue
		}
		item := record.Public
		if record.RetryPending {
			item.Status = "retry_pending"
			item.Failed = true
			item.ProgressPercent = 0
			item.DownloadSpeedBPS = 0
			item.ETASeconds = 0
		}
		out = append(out, item)
	}
	s.pruneLocked(time.Now().Add(-90 * 24 * time.Hour).UnixMilli())
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].AddedAt > out[j].AddedAt })
	return out, nil
}

func (s *readingTransferStore) get(id string) (readingTransferRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.items[id]
	if !ok || record.Canceled {
		return readingTransferRecord{}, errReadingTransferNotFound
	}
	return record, nil
}

func (s *readingTransferStore) beginRetry(id string) (readingTransferRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.items[id]
	if !ok || record.Canceled {
		return readingTransferRecord{}, errReadingTransferNotFound
	}
	if record.Public.Status == "retrying" {
		return readingTransferRecord{}, errReadingTransferBusy
	}
	if !record.Public.Failed && !record.RetryPending {
		return readingTransferRecord{}, errReadingTransferState
	}
	record.Public.Status = "retrying"
	record.Public.Failed = true
	record.RetryPending = true
	record.AwaitingUpstream = false
	record.UpdatedAt = time.Now().UnixMilli()
	s.items[id] = record
	if err := s.saveLocked(); err != nil {
		return readingTransferRecord{}, err
	}
	return record, nil
}

func (s *readingTransferStore) markRetryPending(id string, previousUpstreamID int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.items[id]
	if !ok {
		return errReadingTransferNotFound
	}
	if previousUpstreamID > 0 {
		record.SupersededUpstreamID = previousUpstreamID
	}
	record.UpstreamID = 0
	record.QBTHash = ""
	record.Public.Status = "retry_pending"
	record.Public.Failed = true
	record.RetryPending = true
	record.AwaitingUpstream = false
	record.UpdatedAt = time.Now().UnixMilli()
	s.items[id] = record
	return s.saveLocked()
}

func (s *readingTransferStore) abortRetry(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.items[id]
	if !ok {
		return errReadingTransferNotFound
	}
	record.Public.Status = "failed"
	record.Public.Failed = true
	record.RetryPending = false
	record.UpdatedAt = time.Now().UnixMilli()
	s.items[id] = record
	return s.saveLocked()
}

func (s *readingTransferStore) completeRetry(id string, grabbed *bookkeeprr.GrabbedRelease) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.items[id]
	if !ok {
		return errReadingTransferNotFound
	}
	record.UpstreamID = grabbed.DownloadID
	record.QBTHash = grabbed.QBTHash
	record.Public.Status = grabbed.Status
	if record.Public.Status == "" {
		record.Public.Status = "queued"
	}
	record.Public.Failed = false
	record.RetryPending = false
	record.AwaitingUpstream = true
	record.UpdatedAt = time.Now().UnixMilli()
	s.items[id] = record
	return s.saveLocked()
}

func (s *readingTransferStore) markCanceled(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	record, ok := s.items[id]
	if !ok {
		return errReadingTransferNotFound
	}
	record.Canceled = true
	record.RetryPending = false
	record.AwaitingUpstream = false
	record.Public.Status = "canceled"
	record.UpdatedAt = time.Now().UnixMilli()
	s.items[id] = record
	return s.saveLocked()
}

func (s *readingTransferStore) pruneLocked(cutoff int64) {
	for id, record := range s.items {
		if record.UpdatedAt < cutoff && !record.RetryPending {
			delete(s.items, id)
		}
	}
}

func (s *readingTransferStore) saveLocked() error {
	if s.path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	body, err := json.MarshalIndent(readingTransferFile{Version: 1, Items: s.items}, "", "  ")
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

func randomReadingTransferID() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", fmt.Errorf("creating reading transfer id: %w", err)
	}
	return "rt_" + hex.EncodeToString(raw), nil
}
