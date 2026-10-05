package api

import (
	"bytes"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

// thumbUpstream is the publication test's Kavita, except that a page is a real
// picture (or whatever body is given) and any page number may be asked for.
func thumbUpstream(t *testing.T, body []byte, contentType string, pageCalls *int) *httptest.Server {
	t.Helper()
	inner := newReadingPublicationUpstream(t, &publicationUpstreamState{})
	t.Cleanup(inner.Close)
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/Reader/image" {
			*pageCalls++
			w.Header().Set("Content-Type", contentType)
			_, _ = w.Write(body)
			return
		}
		forward, err := http.NewRequest(r.Method, inner.URL+r.URL.RequestURI(), r.Body)
		if err != nil {
			t.Fatal(err)
		}
		forward.Header = r.Header.Clone()
		response, err := http.DefaultClient.Do(forward)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		for name, values := range response.Header {
			w.Header()[name] = values
		}
		w.WriteHeader(response.StatusCode)
		_, _ = io.Copy(w, response.Body)
	}))
}

// twoTonePage is an 800x1200 page, red above and blue below.
func twoTonePage(t *testing.T) []byte {
	t.Helper()
	page := image.NewRGBA(image.Rect(0, 0, 800, 1200))
	for y := 0; y < 1200; y++ {
		for x := 0; x < 800; x++ {
			c := color.RGBA{R: 220, G: 30, B: 30, A: 255}
			if y >= 600 {
				c = color.RGBA{R: 30, G: 40, B: 210, A: 255}
			}
			page.SetRGBA(x, y, c)
		}
	}
	var out bytes.Buffer
	if err := png.Encode(&out, page); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}

func TestReadingPageThumbIsASmallJPEGOfThePageKeptAfterTheFirstRead(t *testing.T) {
	calls := 0
	upstream := thumbUpstream(t, twoTonePage(t), "image/png", &calls)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	workID := bindPublicationWork(t, handler)
	base := "/v1/reading/works/" + workID + "/publications/6/pages/1/thumb"

	got := libraryRequest(handler, base+"?w=100")
	if got.Code != http.StatusOK || got.Header().Get("Content-Type") != "image/jpeg" {
		t.Fatalf("thumb = %d %q: %s", got.Code, got.Header().Get("Content-Type"), got.Body.String())
	}
	thumb, err := jpeg.Decode(bytes.NewReader(got.Body.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	if size := thumb.Bounds().Size(); size.X != 100 || size.Y != 150 {
		t.Fatalf("thumb size = %v, want 100x150 (the page's shape)", size)
	}
	if r, _, b, _ := thumb.At(50, 20).RGBA(); r>>8 < 180 || b>>8 > 80 {
		t.Fatalf("top of the thumb is not the page's red: r=%d b=%d", r>>8, b>>8)
	}
	if r, _, b, _ := thumb.At(50, 130).RGBA(); b>>8 < 170 || r>>8 > 80 {
		t.Fatalf("bottom of the thumb is not the page's blue: r=%d b=%d", r>>8, b>>8)
	}
	if again := libraryRequest(handler, base+"?w=100"); again.Code != http.StatusOK || calls != 1 {
		t.Fatalf("a second look read Kavita again: %d calls", calls)
	}

	// Widths are clamped, and a page is never scaled up.
	for _, c := range []struct {
		query string
		width int
	}{{"?w=10", 64}, {"?w=5000", 512}, {"", 160}} {
		got := libraryRequest(handler, base+c.query)
		thumb, err := jpeg.Decode(bytes.NewReader(got.Body.Bytes()))
		if err != nil {
			t.Fatalf("%q: %d, not a JPEG: %v", c.query, got.Code, err)
		}
		if thumb.Bounds().Dx() != c.width {
			t.Fatalf("%q gave %d px wide, want %d", c.query, thumb.Bounds().Dx(), c.width)
		}
	}
	for _, bad := range []string{base + "?w=wide", "/v1/reading/works/" + workID + "/publications/6/pages/3/thumb"} {
		if got := libraryRequest(handler, bad); got.Code != http.StatusBadRequest {
			t.Fatalf("%s returned %d, want 400", bad, got.Code)
		}
	}
}

func TestReadingPageThumbPassesThroughAPageItCannotScale(t *testing.T) {
	calls := 0
	upstream := thumbUpstream(t, []byte("RIFF....WEBPVP8 not decodable here"), "image/webp", &calls)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"reading"})).Handler()
	workID := bindPublicationWork(t, handler)

	got := libraryRequest(handler, "/v1/reading/works/"+workID+"/publications/6/pages/0/thumb")
	if got.Code != http.StatusOK || got.Header().Get("Content-Type") != "image/webp" || got.Body.String() != "RIFF....WEBPVP8 not decodable here" {
		t.Fatalf("pass-through = %d %q %q", got.Code, got.Header().Get("Content-Type"), got.Body.String())
	}
}

func TestReadingPageThumbNeedsTheReadingScope(t *testing.T) {
	calls := 0
	upstream := thumbUpstream(t, twoTonePage(t), "image/png", &calls)
	defer upstream.Close()
	handler := NewServer(readingCatalogConfig(upstream.URL, filepath.Join(t.TempDir(), "catalog.json"), []string{"read"})).Handler()
	if got := libraryRequest(handler, "/v1/reading/works/x/publications/6/pages/0/thumb"); got.Code != http.StatusForbidden || calls != 0 {
		t.Fatalf("without the reading scope: %d, %d Kavita calls", got.Code, calls)
	}
}

func TestScaleDownBlendsHalftoneAndKeepsPaperWhite(t *testing.T) {
	// A checkerboard of black dots on white averages to grey, not to either.
	dots := image.NewRGBA(image.Rect(0, 0, 400, 400))
	for y := 0; y < 400; y++ {
		for x := 0; x < 400; x++ {
			c := color.RGBA{R: 255, G: 255, B: 255, A: 255}
			if (x/2+y/2)%2 == 0 {
				c = color.RGBA{A: 255}
			}
			dots.SetRGBA(x, y, c)
		}
	}
	small := scaleDown(dots, 40)
	if r, _, _, _ := small.At(20, 20).RGBA(); r>>8 < 60 || r>>8 > 200 {
		t.Fatalf("halftone came out %d, want a mid grey", r>>8)
	}
	clear := image.NewNRGBA(image.Rect(0, 0, 100, 100))
	if r, g, b, _ := scaleDown(clear, 10).At(5, 5).RGBA(); r>>8 != 255 || g>>8 != 255 || b>>8 != 255 {
		t.Fatalf("a transparent page came out %d,%d,%d, want paper white", r>>8, g>>8, b>>8)
	}
}
