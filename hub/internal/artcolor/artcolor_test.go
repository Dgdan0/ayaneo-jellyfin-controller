package artcolor

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"math"
	"strconv"
	"testing"
)

func canvas(w, h int, c color.Color) *image.NRGBA {
	img := image.NewNRGBA(image.Rect(0, 0, w, h))
	draw.Draw(img, img.Bounds(), &image.Uniform{C: c}, image.Point{}, draw.Src)
	return img
}

func paint(img *image.NRGBA, r image.Rectangle, c color.Color) {
	draw.Draw(img, r, &image.Uniform{C: c}, image.Point{}, draw.Src)
}

func rgbOf(t *testing.T, hexColour string) (int, int, int) {
	t.Helper()
	if len(hexColour) != 7 || hexColour[0] != '#' {
		t.Fatalf("not #rrggbb: %q", hexColour)
	}
	v, err := strconv.ParseUint(hexColour[1:], 16, 32)
	if err != nil {
		t.Fatalf("bad hex %q", hexColour)
	}
	return int(v >> 16), int(v >> 8 & 0xff), int(v & 0xff)
}

func labOf(t *testing.T, hexColour string) lab {
	r, g, b := rgbOf(t, hexColour)
	return fromSRGB(float64(r)/255, float64(g)/255, float64(b)/255)
}

func hueGap(x, y lab) float64 {
	d := math.Abs(math.Atan2(x.b, x.a) - math.Atan2(y.b, y.a))
	if d > math.Pi {
		d = 2*math.Pi - d
	}
	return d
}

func TestASolidColourIsItsOwnDominantAndKeepsItsHueInEveryTone(t *testing.T) {
	red := color.NRGBA{R: 0xd6, G: 0x28, B: 0x28, A: 0xff}
	p, err := FromImage(canvas(60, 90, red))
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b := rgbOf(t, p.Dominant); abs(r-0xd6) > 2 || abs(g-0x28) > 2 || abs(b-0x28) > 2 {
		t.Fatalf("dominant = %s, want about #d62828", p.Dominant)
	}
	want := labOf(t, p.Dominant)
	for name, got := range map[string]string{"dark": p.Dark, "light": p.Light, "vivid": p.Vivid} {
		if gap := hueGap(labOf(t, got), want); gap > 0.12 {
			t.Errorf("%s %s drifted %.2f rad from the dominant hue", name, got, gap)
		}
	}
	if l := labOf(t, p.Dark).l; math.Abs(l-0.20) > 0.02 {
		t.Errorf("dark lightness = %.3f, want 0.20", l)
	}
	if l := labOf(t, p.Light).l; math.Abs(l-0.92) > 0.02 {
		t.Errorf("light lightness = %.3f, want 0.92", l)
	}
}

// Light Bringer's cover is black with gold type. Most of its pixels are black,
// but the page should be tinted gold.
func TestBlackArtworkWithGoldTypeIsGoldNotBlack(t *testing.T) {
	img := canvas(100, 150, color.NRGBA{R: 0x0d, G: 0x0c, B: 0x0a, A: 0xff})
	paint(img, image.Rect(10, 40, 90, 70), color.NRGBA{R: 0xe7, G: 0xc4, B: 0x6b, A: 0xff})
	p, err := FromImage(img)
	if err != nil {
		t.Fatal(err)
	}
	r, g, b := rgbOf(t, p.Dominant)
	if !(r > 150 && r > g && g > b) {
		t.Fatalf("dominant = %s, want the gold", p.Dominant)
	}
}

// The real Light Bringer cover: the gold is thin lines and lettering, about a
// twentieth of the cover. By share alone the black wins.
func TestThinGoldLinesOnBlackStillReadGold(t *testing.T) {
	gold := color.NRGBA{R: 0xd0, G: 0xb3, B: 0x66, A: 0xff}
	img := canvas(100, 150, color.NRGBA{R: 0x1e, G: 0x16, B: 0x1d, A: 0xff})
	for _, y := range []int{30, 75, 120} {
		paint(img, image.Rect(0, y, 100, y+2), gold)
	}
	paint(img, image.Rect(45, 50, 55, 65), gold)
	p, err := FromImage(img)
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b := rgbOf(t, p.Dominant); !(r > 150 && r > g && g > b) {
		t.Fatalf("dominant = %s, want the gold", p.Dominant)
	}
	if r, g, b := rgbOf(t, p.Dark); !(r > b && g > b) {
		t.Fatalf("dark = %s, want a warm near-black, not a cold one", p.Dark)
	}
}

// A dim, colourless film still stays neutral: nothing in it is a real colour.
func TestADimNeutralStillStaysNeutral(t *testing.T) {
	img := canvas(160, 90, color.NRGBA{R: 0x1a, G: 0x1e, B: 0x1f, A: 0xff})
	paint(img, image.Rect(60, 20, 110, 70), color.NRGBA{R: 0x77, G: 0x7d, B: 0x6c, A: 0xff})
	p, err := FromImage(img)
	if err != nil {
		t.Fatal(err)
	}
	if c := labOf(t, p.Dominant); math.Hypot(c.a, c.b) > 0.04 {
		t.Fatalf("dominant = %s, want a neutral", p.Dominant)
	}
}

// Iron Gold's cover is mostly white with an orange mark.
func TestWhiteArtworkWithAnOrangeMarkIsOrange(t *testing.T) {
	img := canvas(100, 150, color.NRGBA{R: 0xf4, G: 0xef, B: 0xe6, A: 0xff})
	paint(img, image.Rect(20, 50, 80, 90), color.NRGBA{R: 0xe8, G: 0x5d, B: 0x04, A: 0xff})
	p, err := FromImage(img)
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b := rgbOf(t, p.Dominant); !(r > 180 && g < 140 && b < 80) {
		t.Fatalf("dominant = %s, want the orange", p.Dominant)
	}
}

func TestGreyArtworkHasNoInventedColour(t *testing.T) {
	p, err := FromImage(canvas(40, 40, color.NRGBA{R: 0x80, G: 0x80, B: 0x80, A: 0xff}))
	if err != nil {
		t.Fatal(err)
	}
	for name, got := range map[string]string{"dominant": p.Dominant, "dark": p.Dark, "light": p.Light, "vivid": p.Vivid} {
		if c := labOf(t, got); math.Hypot(c.a, c.b) > 0.01 {
			t.Errorf("%s %s has chroma %.3f in a grey picture", name, got, math.Hypot(c.a, c.b))
		}
	}
}

func TestTheVividColourIsTheStrongestRealColourLiftedToGlow(t *testing.T) {
	// A muted green field with a saturated magenta band over a fifth of it.
	img := canvas(100, 100, color.NRGBA{R: 0x4f, G: 0x6b, B: 0x5a, A: 0xff})
	paint(img, image.Rect(0, 0, 100, 20), color.NRGBA{R: 0xc2, G: 0x1f, B: 0x8a, A: 0xff})
	p, err := FromImage(img)
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b := rgbOf(t, p.Vivid); !(r > g && b > g) {
		t.Fatalf("vivid = %s, want the magenta", p.Vivid)
	}
	if l := labOf(t, p.Vivid).l; l < 0.57 || l > 0.87 {
		t.Fatalf("vivid lightness = %.3f, want 0.58-0.86 so it shows on a dark page", l)
	}
}

func TestTransparentMarginsAreIgnored(t *testing.T) {
	img := image.NewNRGBA(image.Rect(0, 0, 80, 80))
	paint(img, image.Rect(40, 0, 80, 80), color.NRGBA{R: 0x1d, G: 0x4f, B: 0xa8, A: 0xff})
	p, err := FromImage(img)
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b := rgbOf(t, p.Dominant); !(b > 150 && r < 60 && g < 100) {
		t.Fatalf("dominant = %s, want the blue half", p.Dominant)
	}
	if _, err := FromImage(image.NewNRGBA(image.Rect(0, 0, 10, 10))); !errors.Is(err, ErrUnreadable) {
		t.Fatalf("a fully transparent image = %v, want ErrUnreadable", err)
	}
}

func TestTheSameArtworkAlwaysGivesTheSamePalette(t *testing.T) {
	img := canvas(120, 180, color.NRGBA{R: 0x12, G: 0x05, B: 0x07, A: 0xff})
	paint(img, image.Rect(0, 60, 120, 100), color.NRGBA{R: 0xa3, G: 0x16, B: 0x1a, A: 0xff})
	paint(img, image.Rect(30, 120, 90, 160), color.NRGBA{R: 0xf2, G: 0xef, B: 0xef, A: 0xff})
	first, err := FromImage(img)
	if err != nil {
		t.Fatal(err)
	}
	for range 3 {
		if again, _ := FromImage(img); again != first {
			t.Fatalf("palette changed between runs: %+v then %+v", first, again)
		}
	}
}

func TestWidthDoesNotChangeTheAnswer(t *testing.T) {
	big := canvas(1280, 720, color.NRGBA{R: 0x0b, G: 0x20, B: 0x16, A: 0xff})
	paint(big, image.Rect(640, 0, 1280, 360), color.NRGBA{R: 0xbd, G: 0xe6, B: 0x6c, A: 0xff})
	small := canvas(180, 101, color.NRGBA{R: 0x0b, G: 0x20, B: 0x16, A: 0xff})
	paint(small, image.Rect(90, 0, 180, 50), color.NRGBA{R: 0xbd, G: 0xe6, B: 0x6c, A: 0xff})
	a, _ := FromImage(big)
	b, _ := FromImage(small)
	if gap := math.Sqrt(labOf(t, a.Dominant).distance(labOf(t, b.Dominant))); gap > 0.02 {
		t.Fatalf("dominant at 1280px %s and at 180px %s differ by %.3f", a.Dominant, b.Dominant, gap)
	}
}

func TestAnalyzeDecodesPNGAndRefusesNonImages(t *testing.T) {
	var buf bytes.Buffer
	if err := png.Encode(&buf, canvas(30, 45, color.NRGBA{R: 0x2e, G: 0x6a, B: 0x3b, A: 0xff})); err != nil {
		t.Fatal(err)
	}
	p, err := Analyze(buf.Bytes())
	if err != nil {
		t.Fatal(err)
	}
	if r, g, b := rgbOf(t, p.Dominant); abs(r-0x2e) > 2 || abs(g-0x6a) > 2 || abs(b-0x3b) > 2 {
		t.Fatalf("dominant = %s, want about #2e6a3b", p.Dominant)
	}
	for _, junk := range [][]byte{nil, []byte("not an image"), []byte(`{"error":"no such image"}`)} {
		if _, err := Analyze(junk); !errors.Is(err, ErrUnreadable) {
			t.Errorf("Analyze(%q) = %v, want ErrUnreadable", junk, err)
		}
	}
}

func TestOklabRoundTripsAndDerivedTonesStayDisplayable(t *testing.T) {
	for _, c := range [][3]float64{{0, 0, 0}, {1, 1, 1}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0.84, 0.16, 0.16}, {0.2, 0.5, 0.7}} {
		r, g, b := toSRGB(fromSRGB(c[0], c[1], c[2]))
		if math.Abs(r-c[0]) > 1e-5 || math.Abs(g-c[1]) > 1e-5 || math.Abs(b-c[2]) > 1e-5 {
			t.Errorf("round trip of %v gave %.6f %.6f %.6f", c, r, g, b)
		}
	}
	// A dark tone of a vivid blue is out of gamut at full chroma; fitting keeps
	// lightness and hue.
	blue := fromSRGB(0, 0, 1)
	fitted := fitGamut(lab{0.2, blue.a, blue.b})
	if !inGamut(fitted) || math.Abs(fitted.l-0.2) > 1e-9 || hueGap(fitted, blue) > 1e-6 {
		t.Fatalf("fitGamut moved lightness or hue: %+v", fitted)
	}
	if got := hex(fromSRGB(1, 1, 1)); got != "#ffffff" {
		t.Fatalf("hex(white) = %s", got)
	}
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}
