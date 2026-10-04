package api

import (
	"bytes"
	"encoding/json"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"ayaneohub/internal/artcolor"
)

func pngOf(t *testing.T, c color.Color) []byte {
	t.Helper()
	img := image.NewNRGBA(image.Rect(0, 0, 40, 60))
	draw.Draw(img, img.Bounds(), &image.Uniform{C: c}, image.Point{}, draw.Src)
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// coverServer serves one picture as a registered reading cover, counting how
// often the hub fetches it.
func coverServer(t *testing.T, contentType string, body []byte, delay time.Duration) (*Server, string, *atomic.Int32) {
	t.Helper()
	var calls atomic.Int32
	cover := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		time.Sleep(delay)
		w.Header().Set("Content-Type", contentType)
		_, _ = w.Write(body)
	}))
	t.Cleanup(cover.Close)
	server := NewServer(libraryAPIConfig("", ""))
	server.images.client = cover.Client()
	token := server.images.registerReadingCover(cover.URL + "/cover")
	if token == "" {
		t.Fatal("cover was not registered")
	}
	return server, "/v1/img/reading/" + token, &calls
}

func colorsOf(t *testing.T, handler http.Handler, sources ...string) (int, artworkColorsResponse) {
	t.Helper()
	query := url.Values{}
	for _, src := range sources {
		query.Add("src", src)
	}
	got := libraryRequest(handler, "/v1/img/colors?"+query.Encode())
	var body artworkColorsResponse
	if got.Code == http.StatusOK {
		if err := json.Unmarshal(got.Body.Bytes(), &body); err != nil {
			t.Fatalf("response is not JSON: %v %s", err, got.Body.String())
		}
	}
	return got.Code, body
}

func TestArtworkColorsNeedATokenLikeTheImages(t *testing.T) {
	handler := NewServer(libraryAPIConfig("", "")).Handler()
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/v1/img/colors?src=/v1/img/tmdb/w342/a.jpg", nil))
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("no token = %d, want 401", recorder.Code)
	}
}

func TestArtworkColorsTakeOnlyHubImagePaths(t *testing.T) {
	handler := NewServer(libraryAPIConfig("", "")).Handler()
	many := make([]string, maxColorSources+1)
	for i := range many {
		many[i] = "/v1/img/tmdb/w342/a.jpg"
	}
	for name, sources := range map[string][]string{
		"none":             {},
		"another site":     {"https://image.tmdb.org/t/p/w342/a.jpg"},
		"no scheme host":   {"//evil.example/v1/img/jf/a/Primary"},
		"another endpoint": {"/v1/library/items/0123456789abcdef0123456789abcdef"},
		"climbing out":     {"/v1/img/../health"},
		"itself":           {"/v1/img/colors"},
		"one bad of two":   {"/v1/img/tmdb/w342/a.jpg", "/v1/health"},
		"too many":         many,
	} {
		if code, _ := colorsOf(t, handler, sources...); code != http.StatusBadRequest {
			t.Errorf("%s = %d, want 400", name, code)
		}
	}
}

func TestOnePictureAtTwoWidthsSharesItsColours(t *testing.T) {
	key := func(src string) string {
		k, ok := artworkColorKey(src)
		if !ok {
			t.Fatalf("%s refused", src)
		}
		return k
	}
	jf := "/v1/img/jf/d151b13906a376f2a95e6bc56a43b4a9/Backdrop?tag=c6775dd0"
	if key(jf+"&w=1280") != key(jf+"&w=360") || key(jf+"&w=1280") != key(jf) {
		t.Error("a Jellyfin picture's width changed its key")
	}
	if key(jf) == key("/v1/img/jf/d151b13906a376f2a95e6bc56a43b4a9/Backdrop?tag=00000000") {
		t.Error("a new tag is a new picture and must not reuse the old colours")
	}
	if key(jf) == key("/v1/img/jf/d151b13906a376f2a95e6bc56a43b4a9/Primary?tag=c6775dd0") {
		t.Error("a poster and a backdrop are different pictures")
	}
	if key("/v1/img/tmdb/w342/a.jpg") != key("/v1/img/tmdb/w1280/a.jpg") {
		t.Error("a TMDB picture's size changed its key")
	}
	if key("/v1/img/reading/kavita/4928?w=180") != key("/v1/img/reading/kavita/4928") {
		t.Error("a sized reading cover must share the unsized cover's colours")
	}
}

func TestArtworkColoursAreWorkedOutOnceAndRemembered(t *testing.T) {
	server, src, calls := coverServer(t, "image/png", pngOf(t, color.NRGBA{R: 0xd6, G: 0x28, B: 0x28, A: 0xff}), 0)
	handler := server.Handler()
	for round := range 3 {
		code, body := colorsOf(t, handler, src, src)
		if code != http.StatusOK {
			t.Fatalf("round %d = %d", round, code)
		}
		set, ok := body.Colors[src]
		if !ok || len(body.Colors) != 1 || len(body.Pending) != 0 || len(body.Missing) != 0 {
			t.Fatalf("round %d = %+v", round, body)
		}
		if !strings.HasPrefix(set.Dominant, "#d") || set.Dark == "" || set.Vivid == "" || set.Light == "" {
			t.Fatalf("round %d colours = %+v, want the red", round, set)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("the cover was fetched %d times, want once", calls.Load())
	}
}

func TestArtworkThatIsNotAPictureIsMissingAndNotAskedForAgain(t *testing.T) {
	server, src, calls := coverServer(t, "image/jpeg", []byte("not a picture"), 0)
	handler := server.Handler()
	for round := range 2 {
		code, body := colorsOf(t, handler, src)
		if code != http.StatusOK || len(body.Missing) != 1 || body.Missing[0] != src || len(body.Colors) != 0 {
			t.Fatalf("round %d = %d %+v", round, code, body)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("an unreadable cover was fetched %d times, want once", calls.Load())
	}
	// An unregistered cover is a 404 from the image route: missing, not an error.
	if code, body := colorsOf(t, handler, "/v1/img/reading/00000000000000000000000000000000"); code != http.StatusOK || len(body.Missing) != 1 {
		t.Fatalf("unknown cover = %d %+v", code, body)
	}
}

func TestSlowArtworkIsPendingAndFinishesInTheBackground(t *testing.T) {
	server, src, _ := coverServer(t, "image/png", pngOf(t, color.NRGBA{R: 0x1d, G: 0x4f, B: 0xa8, A: 0xff}), 300*time.Millisecond)
	server.colorRequestBudget = 30 * time.Millisecond
	handler := server.Handler()
	code, body := colorsOf(t, handler, src)
	if code != http.StatusOK || len(body.Pending) != 1 || body.Pending[0] != src || len(body.Colors) != 0 {
		t.Fatalf("first ask = %d %+v, want pending", code, body)
	}
	key, _ := artworkColorKey(src)
	deadline := time.Now().Add(5 * time.Second)
	for {
		if _, ok := server.colors.get(key); ok {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the background work never finished")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if _, body := colorsOf(t, handler, src); len(body.Colors) != 1 {
		t.Fatalf("second ask = %+v, want the colours", body)
	}
}

func TestArtworkColoursSurviveARestart(t *testing.T) {
	path := artworkColorsPath(filepath.Join(t.TempDir(), "offline-grants.json"))
	store := newArtworkColorStore(path)
	red := artcolor.Palette{Dominant: "#d62828", Dark: "#2c0807", Vivid: "#dd2722", Light: "#ffdbd6"}
	store.put("jf/abc/Backdrop/t1", red)
	store.put("tmdb/a.jpg", artcolor.Palette{Dominant: "#1d4fa8", Dark: "#0c1331", Vivid: "#556fde", Light: "#dae4ff"})
	store.flush()

	again := newArtworkColorStore(path)
	if got, ok := again.get("jf/abc/Backdrop/t1"); !ok || got != red {
		t.Fatalf("after a restart = %+v %v", got, ok)
	}
	if len(again.order) != 2 || again.order[0] != "jf/abc/Backdrop/t1" {
		t.Fatalf("order after a restart = %v", again.order)
	}
	// A damaged file starts empty instead of stopping the hub.
	if err := writeArtworkColors(path, artworkColorRegistry{Version: 99}); err != nil {
		t.Fatal(err)
	}
	if damaged := newArtworkColorStore(path); len(damaged.entries) != 0 {
		t.Fatalf("a wrong-version file loaded %d entries", len(damaged.entries))
	}
}

func TestAFailureIsForgottenAfterAnHour(t *testing.T) {
	store := newArtworkColorStore("")
	now := time.Date(2026, 10, 4, 12, 0, 0, 0, time.UTC)
	store.now = func() time.Time { return now }
	store.fail("tmdb/gone.jpg")
	if !store.failedRecently("tmdb/gone.jpg") {
		t.Fatal("a fresh failure was forgotten")
	}
	now = now.Add(colorFailureMemory + time.Minute)
	if store.failedRecently("tmdb/gone.jpg") {
		t.Fatal("a failure from over an hour ago still blocks asking")
	}
}
