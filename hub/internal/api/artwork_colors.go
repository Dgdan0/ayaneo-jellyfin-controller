package api

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/artcolor"
)

// GET /v1/img/colors?src=…&src=… gives the colours the Glass look tints a page
// with, one set per artwork the app is showing (GLASS_PLAN.md, issue #10).
//
// The hub reads the artwork through the very handlers that serve it -- same
// whitelists, same caches -- so this endpoint can never reach anything the
// image routes cannot, and a poster the app has just drawn is usually still
// in the image cache.

const (
	maxColorSources = 60
	// colorRequestBudget is how long a request waits for artwork the hub has
	// not analysed yet. Anything slower is reported as pending and finishes in
	// the background.
	colorRequestBudget = 2500 * time.Millisecond
	// colorWorkTimeout bounds one artwork's fetch and analysis, in the
	// background as well.
	colorWorkTimeout = 20 * time.Second
	// colorFailureMemory stops an app asking again and again for artwork that
	// is not there or cannot be read.
	colorFailureMemory = time.Hour
	colorMaxEntries    = 50_000
	colorSaveDelay     = 10 * time.Second
)

type artworkColorSet struct {
	Dominant string `json:"dominant"`
	Dark     string `json:"dark"`
	Vivid    string `json:"vivid"`
	Light    string `json:"light"`
}

type artworkColorsResponse struct {
	// Keyed by each src exactly as the app sent it.
	Colors  map[string]artworkColorSet `json:"colors"`
	Pending []string                   `json:"pending"`
	Missing []string                   `json:"missing"`
}

func colorSetOf(p artcolor.Palette) artworkColorSet {
	return artworkColorSet{Dominant: p.Dominant, Dark: p.Dark, Vivid: p.Vivid, Light: p.Light}
}

// artworkColorKey is the identity of the picture behind an image path, so the
// 360px poster and the 1280px backdrop of one image share an entry. It also
// decides what counts as a hub image path; anything else is refused.
func artworkColorKey(src string) (string, bool) {
	if len(src) > 512 {
		return "", false
	}
	u, err := url.Parse(src)
	if err != nil || u.Scheme != "" || u.Host != "" || u.User != nil || u.Fragment != "" {
		return "", false
	}
	if !strings.HasPrefix(u.Path, "/v1/img/") || strings.Contains(u.Path, "..") || strings.Contains(u.Path, "//") {
		return "", false
	}
	rest := strings.TrimPrefix(u.Path, "/v1/img/")
	parts := strings.Split(rest, "/")
	switch parts[0] {
	case "jf":
		// jf/{itemId}/{imageType}: the tag names the picture, the width does not.
		if len(parts) != 3 {
			return "", false
		}
		return rest + "/" + u.Query().Get("tag"), true
	case "tmdb":
		// tmdb/{size}/{file}: the file names the picture, the size does not.
		if len(parts) != 3 {
			return "", false
		}
		return "tmdb/" + parts[2], true
	case "arr", "reading":
		// These routes ignore a query; a screen may still have sized the path.
		if len(parts) < 2 {
			return "", false
		}
		return rest, true
	}
	return "", false
}

// artworkColorStore keeps every palette worked out, in memory and in
// artwork-colors.json beside the other registries, so a restart does not send
// every app's first screen back to neutral.
type artworkColorStore struct {
	mu       sync.Mutex
	path     string
	entries  map[string]artcolor.Palette
	order    []string
	failed   map[string]time.Time
	inflight map[string]chan struct{}
	timer    *time.Timer
	now      func() time.Time
}

type artworkColorRegistry struct {
	Version int `json:"version"`
	// [key, dominant, dark, vivid, light], oldest first.
	Entries [][5]string `json:"entries"`
}

func artworkColorsPath(registryPath string) string {
	if strings.TrimSpace(registryPath) == "" {
		return ""
	}
	return filepath.Join(filepath.Dir(registryPath), "artwork-colors.json")
}

func newArtworkColorStore(path string) *artworkColorStore {
	store := &artworkColorStore{
		path: path, entries: map[string]artcolor.Palette{},
		failed: map[string]time.Time{}, inflight: map[string]chan struct{}{}, now: time.Now,
	}
	if path == "" {
		return store
	}
	body, err := os.ReadFile(path)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			slog.Warn("artwork colours not loaded", "path", path, "error", err)
		}
		return store
	}
	var registry artworkColorRegistry
	if err := json.Unmarshal(body, &registry); err != nil || registry.Version != 1 {
		// A damaged cache costs one re-analysis per picture, never a refusal to start.
		slog.Warn("artwork colours unreadable; starting empty", "path", path)
		return store
	}
	for _, e := range registry.Entries {
		if e[0] == "" {
			continue
		}
		if _, seen := store.entries[e[0]]; !seen {
			store.order = append(store.order, e[0])
		}
		store.entries[e[0]] = artcolor.Palette{Dominant: e[1], Dark: e[2], Vivid: e[3], Light: e[4]}
	}
	return store
}

func (s *artworkColorStore) get(key string) (artcolor.Palette, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p, ok := s.entries[key]
	return p, ok
}

func (s *artworkColorStore) failedRecently(key string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	at, ok := s.failed[key]
	if ok && s.now().Sub(at) > colorFailureMemory {
		delete(s.failed, key)
		return false
	}
	return ok
}

func (s *artworkColorStore) fail(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.failed[key] = s.now()
}

func (s *artworkColorStore) put(key string, p artcolor.Palette) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, seen := s.entries[key]; !seen {
		s.order = append(s.order, key)
	}
	s.entries[key] = p
	delete(s.failed, key)
	for len(s.order) > colorMaxEntries {
		delete(s.entries, s.order[0])
		s.order = s.order[1:]
	}
	// Coalesced: a Library page analyses dozens of posters within a second, and
	// one write afterwards is enough.
	if s.path != "" && s.timer == nil {
		s.timer = time.AfterFunc(colorSaveDelay, s.flush)
	}
}

// begin claims the work for a key. The second asker gets the first one's
// channel and waits for it instead of fetching the same picture again.
func (s *artworkColorStore) begin(key string) (chan struct{}, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ch, busy := s.inflight[key]; busy {
		return ch, false
	}
	ch := make(chan struct{})
	s.inflight[key] = ch
	return ch, true
}

func (s *artworkColorStore) end(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if ch, busy := s.inflight[key]; busy {
		close(ch)
		delete(s.inflight, key)
	}
}

func (s *artworkColorStore) flush() {
	s.mu.Lock()
	registry := artworkColorRegistry{Version: 1, Entries: make([][5]string, 0, len(s.order))}
	for _, key := range s.order {
		p := s.entries[key]
		registry.Entries = append(registry.Entries, [5]string{key, p.Dominant, p.Dark, p.Vivid, p.Light})
	}
	s.timer = nil
	path := s.path
	s.mu.Unlock()
	if err := writeArtworkColors(path, registry); err != nil {
		slog.Warn("artwork colours not saved", "path", path, "error", err)
	}
}

func writeArtworkColors(path string, registry artworkColorRegistry) error {
	body, err := json.Marshal(registry)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	temporary := path + ".tmp"
	if err := os.WriteFile(temporary, append(body, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(temporary, path)
}

// errArtworkGone is a picture that is not there or is not a picture: an app
// should stop asking. Anything else (a slow or unreachable service) is worth
// asking about again.
var errArtworkGone = errors.New("artwork not readable")

// artworkMux serves the image routes without authentication, for the hub's
// own use: the caller of /v1/img/colors has already been authenticated.
func (s *Server) artworkMux() http.Handler {
	s.artworkMuxOnce.Do(func() {
		mux := http.NewServeMux()
		for pattern, handler := range s.imageRoutes() {
			mux.HandleFunc(pattern, handler)
		}
		s.artworkMuxHandler = mux
	})
	return s.artworkMuxHandler
}

// imageRoutes is the one list of artwork routes, registered both on the API
// and on artworkMux.
func (s *Server) imageRoutes() map[string]http.HandlerFunc {
	return map[string]http.HandlerFunc{
		"GET /v1/img/jf/{itemId}/{imageType}":            s.handleJellyfinImage,
		"GET /v1/img/arr/{service}/{id}":                 s.handleArrPoster,
		"GET /v1/img/reading/kavita/{seriesId}":          s.handleKavitaReadingImage,
		"GET /v1/img/reading/kavita-library/{libraryId}": s.handleKavitaLibraryImage,
		"GET /v1/img/reading/kavita-chapter/{chapterId}": s.handleKavitaChapterImage,
		"GET /v1/img/reading/storyteller/{bookId}":       s.handleStorytellerReadingImage,
		"GET /v1/img/reading/{token}":                    s.handleReadingImage,
		"GET /v1/img/tmdb/{size}/{file}":                 s.handleTmdbImage,
	}
}

// captureWriter keeps what an image handler writes.
type captureWriter struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (c *captureWriter) Header() http.Header { return c.header }

func (c *captureWriter) WriteHeader(status int) {
	if c.status == 0 {
		c.status = status
	}
}

func (c *captureWriter) Write(p []byte) (int, error) {
	if c.status == 0 {
		c.status = http.StatusOK
	}
	if c.body.Len()+len(p) > 16<<20 {
		return 0, errors.New("artwork larger than 16 MB")
	}
	return c.body.Write(p)
}

func (s *Server) artworkBytes(ctx context.Context, src string) ([]byte, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, src, nil)
	if err != nil {
		return nil, errArtworkGone
	}
	capture := &captureWriter{header: http.Header{}}
	s.artworkMux().ServeHTTP(capture, request)
	switch {
	case capture.status == http.StatusOK && strings.HasPrefix(strings.ToLower(capture.header.Get("Content-Type")), "image/"):
		return capture.body.Bytes(), nil
	case capture.status == http.StatusOK, capture.status == http.StatusNotFound,
		capture.status == http.StatusBadRequest, capture.status == http.StatusMethodNotAllowed:
		return nil, errArtworkGone
	}
	return nil, fmt.Errorf("artwork answered %d", capture.status)
}

// artworkPalette reads and analyses one picture, once, however many requests
// ask for it at the same moment.
func (s *Server) artworkPalette(ctx context.Context, src, key string) (artcolor.Palette, error) {
	done, leader := s.colors.begin(key)
	if !leader {
		select {
		case <-done:
			if p, ok := s.colors.get(key); ok {
				return p, nil
			}
			if s.colors.failedRecently(key) {
				return artcolor.Palette{}, errArtworkGone
			}
			return artcolor.Palette{}, errors.New("artwork not analysed")
		case <-ctx.Done():
			return artcolor.Palette{}, ctx.Err()
		}
	}
	defer s.colors.end(key)
	body, err := s.artworkBytes(ctx, src)
	if err != nil {
		if errors.Is(err, errArtworkGone) {
			s.colors.fail(key)
		}
		return artcolor.Palette{}, err
	}
	palette, err := artcolor.Analyze(body)
	if err != nil {
		s.colors.fail(key)
		return artcolor.Palette{}, errArtworkGone
	}
	s.colors.put(key, palette)
	return palette, nil
}

func (s *Server) handleArtworkColors(w http.ResponseWriter, r *http.Request) {
	sources := r.URL.Query()["src"]
	if len(sources) == 0 || len(sources) > maxColorSources {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest,
			Message: fmt.Sprintf("ask for 1 to %d src image paths", maxColorSources)})
		return
	}
	keys := make([]string, len(sources))
	for i, src := range sources {
		key, ok := artworkColorKey(src)
		if !ok {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest,
				Message: "src must be a hub image path such as /v1/img/jf/…"})
			return
		}
		keys[i] = key
	}

	const (
		known = iota + 1
		gone
		later
	)
	state := make([]int, len(sources))
	palettes := make([]artcolor.Palette, len(sources))
	type result struct {
		index   int
		palette artcolor.Palette
		err     error
	}
	var work []int
	first := map[string]int{}
	for i, src := range sources {
		if j, repeated := first[src]; repeated {
			state[i] = -j - 1 // answered with the first copy
			continue
		}
		first[src] = i
		switch p, ok := s.colors.get(keys[i]); {
		case ok:
			state[i], palettes[i] = known, p
		case s.colors.failedRecently(keys[i]):
			state[i] = gone
		default:
			state[i] = later
			work = append(work, i)
		}
	}

	if len(work) > 0 {
		// The work outlives this request on purpose: what misses the budget is
		// stored when it finishes, and the app's next ask finds it. Detached from
		// the request's cancellation, but still bounded per picture.
		background := context.WithoutCancel(r.Context())
		results := make(chan result, len(work))
		slots := make(chan struct{}, 4)
		for _, i := range work {
			go func(i int) {
				slots <- struct{}{}
				defer func() { <-slots }()
				ctx, cancel := context.WithTimeout(background, colorWorkTimeout)
				defer cancel()
				p, err := s.artworkPalette(ctx, sources[i], keys[i])
				results <- result{i, p, err}
			}(i)
		}
		budget := time.NewTimer(s.colorBudget())
		defer budget.Stop()
	collect:
		for range work {
			select {
			case res := <-results:
				switch {
				case res.err == nil:
					state[res.index], palettes[res.index] = known, res.palette
				case errors.Is(res.err, errArtworkGone):
					state[res.index] = gone
				}
			case <-budget.C:
				break collect
			}
		}
	}

	// Lists in request order, so an app can match them to what it asked for.
	out := artworkColorsResponse{Colors: map[string]artworkColorSet{}, Pending: []string{}, Missing: []string{}}
	for i, src := range sources {
		if state[i] < 0 {
			continue
		}
		switch state[i] {
		case known:
			out.Colors[src] = colorSetOf(palettes[i])
		case gone:
			out.Missing = append(out.Missing, src)
		default:
			out.Pending = append(out.Pending, src)
		}
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) colorBudget() time.Duration {
	if s.colorRequestBudget > 0 {
		return s.colorRequestBudget
	}
	return colorRequestBudget
}
