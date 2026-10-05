package api

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/color"
	_ "image/gif" // decoders a comic page may arrive in
	"image/jpeg"
	_ "image/png"
	"io"
	"math"
	"net/http"
	"strconv"

	"ayaneohub/internal/cache"
)

const (
	readingThumbDefaultWidth = 160
	readingThumbMinWidth     = 64
	readingThumbMaxWidth     = 512
	// A scanned page is a few MB; anything far beyond that is not a page.
	readingThumbMaxSource = 64 << 20
	// Decoding is skipped above this, so one huge image cannot take the
	// hub's memory: 60 megapixels is twice a 300 dpi tabloid scan.
	readingThumbMaxPixels = 60_000_000
)

var errReadingThumbSource = errors.New("reading page is too large to scale")

type readingThumb struct {
	body        []byte
	contentType string
}

// handleReadingPageThumb serves
// GET /v1/reading/works/{workId}/publications/{sourceItemId}/pages/{page}/thumb?w=
// a small JPEG of one page, for the reader's page slider and a Pages grid
// (#16, H1). The page is read from Kavita as the full-size page route reads it,
// scaled here with the standard library (the hub keeps one external module),
// and kept as long as any image: a chapter's pages do not change. A page Go
// cannot decode, such as WebP, is passed through as it is, so the app still
// gets a picture.
func (s *Server) handleReadingPageThumb(w http.ResponseWriter, r *http.Request) {
	if !s.requireReading(w, r) {
		return
	}
	page, err := strconv.Atoi(r.PathValue("page"))
	if err != nil || page < 0 {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "invalid reading page"})
		return
	}
	width := readingThumbDefaultWidth
	if raw := r.URL.Query().Get("w"); raw != "" {
		asked, err := strconv.Atoi(raw)
		if err != nil {
			writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "w must be a number of pixels"})
			return
		}
		width = min(max(asked, readingThumbMinWidth), readingThumbMaxWidth)
	}
	publication, ok := s.resolveReadingPublication(w, r, r.Context(), false)
	if !ok {
		return
	}
	if page >= publication.manifest.PageCount {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "reading page is outside this publication"})
		return
	}
	key := "reading:thumb:" + strconv.Itoa(publication.chapterID) + ":" + strconv.Itoa(page) + ":" + strconv.Itoa(width)
	thumb, _, err := cache.Fetch(r.Context(), s.cache, key, cache.ReadingThumbs,
		func(ctx context.Context) (*readingThumb, error) {
			response, err := s.kavita.OpenPage(ctx, publication.chapterID, page)
			if err != nil {
				return nil, err
			}
			defer response.Body.Close()
			source, err := io.ReadAll(io.LimitReader(response.Body, readingThumbMaxSource+1))
			if err != nil {
				return nil, err
			}
			if len(source) > readingThumbMaxSource {
				return nil, errReadingThumbSource
			}
			return makeReadingThumb(source, response.Header.Get("Content-Type"), width), nil
		})
	if err != nil {
		writeUpstreamError(w, r, "kavita", err)
		return
	}
	w.Header().Set("Content-Type", thumb.contentType)
	w.Header().Set("Content-Length", strconv.Itoa(len(thumb.body)))
	w.Header().Set("Cache-Control", "private, max-age="+strconv.Itoa(int(cache.ImageMaxAge.Seconds())))
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(thumb.body)
}

// makeReadingThumb scales a page to width (never up) as a JPEG, or returns
// the page untouched when it is not a picture Go can decode.
func makeReadingThumb(source []byte, sourceType string, width int) *readingThumb {
	original := &readingThumb{body: source, contentType: sourceType}
	if original.contentType == "" {
		original.contentType = "application/octet-stream"
	}
	config, _, err := image.DecodeConfig(bytes.NewReader(source))
	if err != nil || config.Width <= 0 || config.Height <= 0 || config.Width*config.Height > readingThumbMaxPixels {
		return original
	}
	picture, _, err := image.Decode(bytes.NewReader(source))
	if err != nil {
		return original
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, scaleDown(picture, width), &jpeg.Options{Quality: 80}); err != nil {
		return original
	}
	return &readingThumb{body: out.Bytes(), contentType: "image/jpeg"}
}

// scaleDown shrinks src to width, keeping its shape. Each new pixel averages
// a few samples spread over the block of the page it covers, so halftone dots
// blend instead of turning into moire, at a fraction of a full box filter's
// cost. Transparent areas come out white, as paper is.
func scaleDown(src image.Image, width int) *image.RGBA {
	bounds := src.Bounds()
	sw, sh := bounds.Dx(), bounds.Dy()
	width = min(width, sw)
	height := max(1, int(math.Round(float64(sh)*float64(width)/float64(sw))))
	dst := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		y0 := bounds.Min.Y + y*sh/height
		y1 := max(bounds.Min.Y+(y+1)*sh/height, y0+1)
		stepY := max(1, (y1-y0)/4)
		for x := 0; x < width; x++ {
			x0 := bounds.Min.X + x*sw/width
			x1 := max(bounds.Min.X+(x+1)*sw/width, x0+1)
			stepX := max(1, (x1-x0)/4)
			var r, g, b, n uint64
			for sy := y0; sy < y1; sy += stepY {
				for sx := x0; sx < x1; sx += stepX {
					cr, cg, cb, ca := src.At(sx, sy).RGBA()
					// Composite onto white: premultiplied colour plus the paper
					// showing through what is transparent.
					paper := uint64(0xffff - ca)
					r += uint64(cr) + paper
					g += uint64(cg) + paper
					b += uint64(cb) + paper
					n++
				}
			}
			dst.SetRGBA(x, y, color.RGBA{
				R: uint8((r / n) >> 8), G: uint8((g / n) >> 8), B: uint8((b / n) >> 8), A: 0xff,
			})
		}
	}
	return dst
}
