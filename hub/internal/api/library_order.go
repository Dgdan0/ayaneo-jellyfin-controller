package api

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
)

// The order someone keeps their libraries in, per Jellyfin profile and side
// (media or books), shared by every device: arrange the tiles on the Pocket
// and the iPad shows them the same way. Saved libraries come first in the
// saved order, the rest follow A to Z, and with nothing saved the whole list
// is A to Z. The apps never sort libraries themselves.

const (
	librarySideMedia = "media"
	librarySideBooks = "books"
	// libraryOrderLimit is far above any real library count; it only bounds
	// what one request can make the hub keep.
	libraryOrderLimit = 100
)

// readingLibraryIDPattern is a library id from GET /v1/reading/libraries:
// "kavita:2", "storyteller:books".
var readingLibraryIDPattern = regexp.MustCompile(`^[a-z]{1,20}:[A-Za-z0-9_-]{1,40}$`)

// LibraryOrderRequest is PUT /v1/library/order's body.
type LibraryOrderRequest struct {
	Side string   `json:"side"`
	IDs  []string `json:"ids"`
}

// LibraryOrderResponse is the order now kept for the side.
type LibraryOrderResponse struct {
	Side  string   `json:"side"`
	IDs   []string `json:"ids"`
	Order string   `json:"order"`
}

type libraryOrderFile struct {
	Version  int                            `json:"version"`
	Profiles map[string]map[string][]string `json:"profiles"`
}

type libraryOrderStore struct {
	mu       sync.Mutex
	path     string
	profiles map[string]map[string][]string
}

// libraryOrderPath keeps the file beside the hub's other registries.
func libraryOrderPath(registryPath string) string {
	if strings.TrimSpace(registryPath) == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(registryPath), "library-order.json")
}

func newLibraryOrderStore(path string) *libraryOrderStore {
	store := &libraryOrderStore{path: path, profiles: map[string]map[string][]string{}}
	if path == "" {
		return store
	}
	body, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			slog.Warn("library order unreadable, starting A to Z", "path", path, "err", err)
		}
		return store
	}
	var saved libraryOrderFile
	if err := json.Unmarshal(body, &saved); err != nil {
		slog.Warn("library order unreadable, starting A to Z", "path", path, "err", err)
		return store
	}
	for profile, sides := range saved.Profiles {
		for side, ids := range sides {
			if side == librarySideMedia || side == librarySideBooks {
				store.put(profile, side, ids)
			}
		}
	}
	return store
}

// get is the saved order, or nil for A to Z.
func (s *libraryOrderStore) get(profile, side string) []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.profiles[profile][side]...)
}

// set keeps an order, an empty one meaning A to Z again, and writes the file.
func (s *libraryOrderStore) set(profile, side string, ids []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.put(profile, side, ids)
	if s.path == "" {
		return nil
	}
	body, err := json.Marshal(libraryOrderFile{Version: 1, Profiles: s.profiles})
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
	return os.Rename(temporary, s.path)
}

func (s *libraryOrderStore) put(profile, side string, ids []string) {
	if len(ids) == 0 {
		delete(s.profiles[profile], side)
		if len(s.profiles[profile]) == 0 {
			delete(s.profiles, profile)
		}
		return
	}
	if s.profiles[profile] == nil {
		s.profiles[profile] = map[string][]string{}
	}
	s.profiles[profile][side] = append([]string(nil), ids...)
}

// arrangeLibraries puts the saved ids first, in the saved order, and the rest
// after them A to Z by name, ignoring case. A saved id that is gone is
// skipped. It reports whether a saved order shaped the result.
func arrangeLibraries[T any](items []T, id, name func(T) string, saved []string) ([]T, bool) {
	place := make(map[string]int, len(saved))
	for i, savedID := range saved {
		if _, seen := place[savedID]; !seen {
			place[savedID] = i
		}
	}
	out := append([]T(nil), items...)
	custom := false
	for _, item := range out {
		if _, ok := place[id(item)]; ok {
			custom = true
			break
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		pi, iSaved := place[id(out[i])]
		pj, jSaved := place[id(out[j])]
		switch {
		case iSaved && jSaved:
			return pi < pj
		case iSaved != jSaved:
			return iSaved
		}
		ni, nj := strings.ToLower(name(out[i])), strings.ToLower(name(out[j]))
		if ni != nj {
			return ni < nj
		}
		return id(out[i]) < id(out[j])
	})
	return out, custom
}

func orderLabel(custom bool) string {
	if custom {
		return "custom"
	}
	return "name"
}

// libraryOrderProfile is whose order a request reads and writes: the
// device's chosen Jellyfin profile, else the configured default. ok is false
// when the header holds something that is not a Jellyfin id.
func (s *Server) libraryOrderProfile(r *http.Request) (string, bool) {
	userID := strings.TrimSpace(r.Header.Get(jellyfinUserHeader))
	if userID != "" {
		return strings.ToLower(userID), isHex32(userID)
	}
	if s.jellyfin != nil && s.jellyfin.UserID() != "" {
		return strings.ToLower(s.jellyfin.UserID()), true
	}
	return "default", true
}

// handleLibraryOrder serves PUT /v1/library/order: how this profile wants
// its media or books libraries listed, on every device.
func (s *Server) handleLibraryOrder(w http.ResponseWriter, r *http.Request) {
	if !requireScope(w, r, "read", "arrange libraries") {
		return
	}
	profile, ok := s.libraryOrderProfile(r)
	if !ok {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad Jellyfin user id"})
		return
	}
	var body LibraryOrderRequest
	decoder := json.NewDecoder(io.LimitReader(r.Body, 16<<10))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&body); err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "body must be {\"side\":…,\"ids\":[…]}"})
		return
	}
	if body.Side != librarySideMedia && body.Side != librarySideBooks {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "side must be media or books"})
		return
	}
	if len(body.IDs) > libraryOrderLimit {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "too many libraries"})
		return
	}
	ids := make([]string, 0, len(body.IDs))
	seen := map[string]bool{}
	for _, raw := range body.IDs {
		id := strings.TrimSpace(raw)
		valid := isHex32(id)
		if body.Side == librarySideBooks {
			valid = readingLibraryIDPattern.MatchString(id)
		}
		if !valid {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad library id"})
			return
		}
		if body.Side == librarySideMedia {
			id = strings.ToLower(id)
		}
		if !seen[id] {
			seen[id] = true
			ids = append(ids, id)
		}
	}
	if err := s.libraryOrder.set(profile, body.Side, ids); err != nil {
		slog.Error("library order not saved", "err", err)
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "the order could not be saved"})
		return
	}
	w.Header().Add("Vary", jellyfinUserHeader)
	writeJSON(w, http.StatusOK, LibraryOrderResponse{Side: body.Side, IDs: ids, Order: orderLabel(len(ids) > 0)})
}
