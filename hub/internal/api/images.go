package api

import (
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/cache"
)

// tmdbImageBase is TMDB's CDN. Public, no credential -- but the handheld still
// goes through the hub for three reasons: it holds one authenticated connection
// rather than opening a second unauthenticated one, it works when the device is
// on a network that cannot reach TMDB, and the hub can cache far harder than a
// phone can.
const tmdbImageBase = "https://image.tmdb.org/t/p"

// allowedTmdbSizes is a whitelist, not a passthrough.
//
// The size segment goes straight into an outbound URL, so accepting arbitrary
// text would turn this endpoint into an open proxy pointed at anything under
// image.tmdb.org. A fixed set also keeps the cache from fragmenting across
// hundreds of near-identical widths.
var allowedTmdbSizes = map[string]bool{
	"w92": true, "w154": true, "w185": true, "w342": true,
	"w500": true, "w780": true, "w1280": true, "original": true,
}

type imageProxy struct {
	client *http.Client
	mu     sync.Mutex
	// Modest in-memory cache. Posters are 10-40KB at w342, so a few hundred is a
	// handful of megabytes and covers every screen the app shows repeatedly.
	entries  map[string]*cachedImage
	order    []string
	maxItems int
}

type cachedImage struct {
	body        []byte
	contentType string
	fetchedAt   time.Time
}

func newImageProxy() *imageProxy {
	return &imageProxy{
		client:   &http.Client{Timeout: 15 * time.Second},
		entries:  map[string]*cachedImage{},
		maxItems: 400,
	}
}

// handleTmdbImage serves /v1/img/tmdb/{size}/{file}.
func (s *Server) handleTmdbImage(w http.ResponseWriter, r *http.Request) {
	size := r.PathValue("size")
	file := r.PathValue("file")

	if !allowedTmdbSizes[size] {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "unsupported image size",
		})
		return
	}
	// TMDB paths are a single segment like "/abc123.jpg". Rejecting anything
	// else stops a crafted path escaping the intended prefix.
	if file == "" || strings.ContainsAny(file, "/\\") || strings.Contains(file, "..") {
		writeError(w, r, http.StatusBadRequest, Error{
			Code:    CodeInvalidRequest,
			Message: "bad image path",
		})
		return
	}

	key := size + "/" + file
	if img := s.images.get(key); img != nil {
		s.writeImage(w, img, true)
		return
	}

	resp, err := s.images.client.Get(tmdbImageBase + "/" + size + "/" + file)
	if err != nil {
		writeError(w, r, http.StatusBadGateway, Error{
			Code:      CodeUpstreamDown,
			Service:   "tmdb",
			Message:   "could not fetch the image",
			Retryable: true,
		})
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		writeError(w, r, http.StatusNotFound, Error{
			Code:    CodeNotFound,
			Message: "no such image",
		})
		return
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		writeError(w, r, http.StatusBadGateway, Error{
			Code:    CodeUpstreamDown,
			Service: "tmdb",
			Message: "image download failed",
		})
		return
	}

	img := &cachedImage{
		body:        body,
		contentType: resp.Header.Get("Content-Type"),
		fetchedAt:   time.Now(),
	}
	if img.contentType == "" {
		img.contentType = "image/jpeg"
	}
	s.images.put(key, img)
	s.writeImage(w, img, false)
}

func (s *Server) writeImage(w http.ResponseWriter, img *cachedImage, fromCache bool) {
	w.Header().Set("Content-Type", img.contentType)
	w.Header().Set("Content-Length", strconv.Itoa(len(img.body)))
	// immutable is correct here rather than optimistic: a TMDB path identifies
	// one specific image, and a changed image gets a new path. So the client can
	// keep it forever and never revalidate, which is the single biggest
	// bandwidth saving in the whole API -- a poster grid is a few hundred KB of
	// images and a few KB of JSON.
	w.Header().Set("Cache-Control", "public, max-age="+
		strconv.Itoa(int(cache.ImageMaxAge.Seconds()))+", immutable")
	if fromCache {
		w.Header().Set("X-Hub-Cache", "hit")
	}
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(img.body)
}

func (p *imageProxy) get(key string) *cachedImage {
	p.mu.Lock()
	defer p.mu.Unlock()
	img, ok := p.entries[key]
	if !ok {
		return nil
	}
	return img
}

func (p *imageProxy) put(key string, img *cachedImage) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, exists := p.entries[key]; !exists {
		p.order = append(p.order, key)
	}
	p.entries[key] = img
	// Plain FIFO. The access pattern is "the screens you visit", which a queue
	// approximates well enough that tracking recency would not repay its cost.
	for len(p.order) > p.maxItems {
		oldest := p.order[0]
		p.order = p.order[1:]
		delete(p.entries, oldest)
	}
}

// jellyfinImagePrefix is the hub-relative prefix the app receives for a
// Jellyfin poster. Never a direct Jellyfin URL: the server is on loopback with
// a self-signed certificate, and its images need the hub's credential.
const jellyfinImagePrefix = "/v1/img/jf"

// allowedJellyfinImageTypes is a whitelist for the same reason the TMDB sizes
// are: the segment goes straight into an upstream path.
var allowedJellyfinImageTypes = map[string]bool{
	"Primary": true, "Backdrop": true, "Thumb": true, "Logo": true,
}

// handleJellyfinImage serves /v1/img/jf/{itemId}/{imageType}?tag=&w=.
func (s *Server) handleJellyfinImage(w http.ResponseWriter, r *http.Request) {
	if s.jellyfin == nil {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "jellyfin",
			Message: "Jellyfin is not configured",
		})
		return
	}
	itemID := r.PathValue("itemId")
	imageType := r.PathValue("imageType")
	if !isHex32(itemID) || !allowedJellyfinImageTypes[imageType] {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "bad image request",
		})
		return
	}
	width, _ := strconv.Atoi(r.URL.Query().Get("w"))
	// Bucketed, not free-form. Every distinct width is a distinct cache entry,
	// and a device that asks for its exact pixel size defeats both this cache
	// and the client's.
	width = bucketWidth(width)
	tag := r.URL.Query().Get("tag")
	if len(tag) > 64 || strings.ContainsAny(tag, `/\?&`) {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "bad image tag",
		})
		return
	}

	key := "jf/" + itemID + "/" + imageType + "/" + strconv.Itoa(width) + "/" + tag
	if img := s.images.get(key); img != nil {
		s.writeImage(w, img, true)
		return
	}

	ctx, cancel := timeoutFor(r, 20*time.Second)
	defer cancel()

	body, contentType, err := s.jellyfin.FetchImage(ctx, itemID, imageType, width, tag)
	if err != nil {
		writeError(w, r, http.StatusNotFound, Error{
			Code: CodeNotFound, Service: "jellyfin", Message: "no such image",
		})
		return
	}
	img := &cachedImage{body: body, contentType: contentType, fetchedAt: time.Now()}
	s.images.put(key, img)
	s.writeImage(w, img, false)
}

// bucketWidth snaps to a small set of widths.
func bucketWidth(requested int) int {
	buckets := []int{180, 270, 360, 540, 780}
	if requested <= 0 {
		return 360
	}
	for _, b := range buckets {
		if requested <= b {
			return b
		}
	}
	return buckets[len(buckets)-1]
}
