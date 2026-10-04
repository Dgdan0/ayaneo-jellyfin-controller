// Package artcolor works out the four colours the Glass look tints a page with
// from one piece of artwork: a poster, a backdrop, a cover.
//
// It lives in the hub rather than in the apps so the Pocket DS, the iPads, the
// iPhone and the Mac tint a page identically from a single implementation, and
// so neither app spends its frame budget analysing pictures. See GLASS_PLAN.md.
package artcolor

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"math"
	"sort"

	// The hub's artwork arrives as JPEG (Jellyfin, TMDB, Storyteller, the *arrs)
	// or PNG (Kavita); GIF costs nothing to accept. Measured on 2026-10-04: no
	// service here serves WebP, which would need a module beyond the standard
	// library.
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
)

// Palette is what the apps tint with. Every value is "#rrggbb".
type Palette struct {
	// Dominant is the colour the artwork reads as. It tints panels on the Pocket
	// and is the hue the other three are built from.
	Dominant string
	// Dark is the dominant hue at page-background lightness: never pure black,
	// so a page sits in the artwork's colour even before the image has loaded.
	Dark string
	// Vivid is the most saturated colour that covers a real part of the
	// artwork, lifted into a range that glows on a dark page.
	Vivid string
	// Light is the dominant hue at a pale lightness, for type drawn straight on
	// the artwork.
	Light string
}

// ErrUnreadable means the bytes are not an image this package can decode, or
// one too large to be artwork.
var ErrUnreadable = errors.New("artcolor: not a readable image")

// maxPixels refuses decompression bombs before decoding. A 4K backdrop is 8.3
// megapixels, so 40 is far above anything a poster server sends.
const maxPixels = 40_000_000

// Analyze decodes artwork and returns its palette.
func Analyze(body []byte) (Palette, error) {
	config, _, err := image.DecodeConfig(bytes.NewReader(body))
	if err != nil {
		return Palette{}, fmt.Errorf("%w: %v", ErrUnreadable, err)
	}
	if config.Width <= 0 || config.Height <= 0 || config.Width*config.Height > maxPixels {
		return Palette{}, fmt.Errorf("%w: %dx%d", ErrUnreadable, config.Width, config.Height)
	}
	img, _, err := image.Decode(bytes.NewReader(body))
	if err != nil {
		return Palette{}, fmt.Errorf("%w: %v", ErrUnreadable, err)
	}
	return FromImage(img)
}

// FromImage returns the palette of an already decoded image.
func FromImage(img image.Image) (Palette, error) {
	samples := sample(img, 4096)
	if len(samples) == 0 {
		return Palette{}, fmt.Errorf("%w: no opaque pixels", ErrUnreadable)
	}
	clusters := kmeans(samples, 6, 12)
	dominant := pickDominant(clusters)
	vivid := pickVivid(clusters, dominant)

	// The page background and the pale tone keep the dominant hue but not its
	// strength: a fully saturated dark red behind type looks like an error state.
	chroma := dominant.chroma()
	dark := withLightnessChroma(dominant.lab, 0.20, math.Min(chroma, 0.06))
	light := withLightnessChroma(dominant.lab, 0.92, math.Min(chroma, 0.05))
	// A glow must show on a dark page: keep the hue and colour, and bring the
	// lightness into a range that reads against Dark.
	glow := vivid.lab
	glow.l = clamp(glow.l, 0.58, 0.86)
	return Palette{
		Dominant: hex(dominant.lab),
		Dark:     hex(dark),
		Vivid:    hex(fitGamut(glow)),
		Light:    hex(light),
	}, nil
}

// sample takes about target opaque pixels on an even grid, so a 1280px
// backdrop and a 180px poster of the same artwork give the same answer.
func sample(img image.Image, target int) []lab {
	bounds := img.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	if w <= 0 || h <= 0 {
		return nil
	}
	step := int(math.Sqrt(float64(w*h) / float64(target)))
	if step < 1 {
		step = 1
	}
	out := make([]lab, 0, target+target/4)
	for y := bounds.Min.Y + step/2; y < bounds.Max.Y; y += step {
		for x := bounds.Min.X + step/2; x < bounds.Max.X; x += step {
			r, g, b, a := img.At(x, y).RGBA()
			// Transparent margins on a PNG cover are not part of the artwork.
			if a < 0x8000 {
				continue
			}
			// RGBA is alpha-premultiplied; undo it so a soft edge keeps its colour.
			out = append(out, fromSRGB(
				float64(r)/float64(a),
				float64(g)/float64(a),
				float64(b)/float64(a),
			))
		}
	}
	return out
}

type cluster struct {
	lab   lab
	share float64
}

func (c cluster) chroma() float64 { return math.Hypot(c.lab.a, c.lab.b) }

// kmeans clusters in Oklab, where equal distances look equally different. The
// start is fixed (the mean, then repeatedly the sample farthest from every
// centre so far), so the same artwork always yields the same palette.
func kmeans(samples []lab, k, rounds int) []cluster {
	var mean lab
	for _, s := range samples {
		mean.l += s.l
		mean.a += s.a
		mean.b += s.b
	}
	n := float64(len(samples))
	mean = lab{mean.l / n, mean.a / n, mean.b / n}

	centres := []lab{mean}
	for len(centres) < k {
		best, bestDistance := -1, 0.0
		for i, s := range samples {
			nearest := math.Inf(1)
			for _, c := range centres {
				nearest = math.Min(nearest, s.distance(c))
			}
			if nearest > bestDistance {
				best, bestDistance = i, nearest
			}
		}
		// Fewer distinct colours than k: a flat image needs no more centres.
		if best < 0 || bestDistance < 1e-6 {
			break
		}
		centres = append(centres, samples[best])
	}

	assignment := make([]int, len(samples))
	counts := make([]int, len(centres))
	for round := 0; round < rounds; round++ {
		changed := round == 0
		for i := range counts {
			counts[i] = 0
		}
		sums := make([]lab, len(centres))
		for i, s := range samples {
			nearest, nearestDistance := 0, math.Inf(1)
			for j, c := range centres {
				if d := s.distance(c); d < nearestDistance {
					nearest, nearestDistance = j, d
				}
			}
			if assignment[i] != nearest {
				assignment[i] = nearest
				changed = true
			}
			counts[nearest]++
			sums[nearest].l += s.l
			sums[nearest].a += s.a
			sums[nearest].b += s.b
		}
		for j := range centres {
			if counts[j] > 0 {
				c := float64(counts[j])
				centres[j] = lab{sums[j].l / c, sums[j].a / c, sums[j].b / c}
			}
		}
		if !changed {
			break
		}
	}

	out := make([]cluster, 0, len(centres))
	for j, c := range centres {
		if counts[j] > 0 {
			out = append(out, cluster{lab: c, share: float64(counts[j]) / n})
		}
	}
	// Largest first, so ties below resolve the same way every time.
	sort.SliceStable(out, func(i, j int) bool { return out[i].share > out[j].share })
	return out
}

// pickDominant prefers the colour the artwork reads as over the colour it has
// most of. Posters are mostly near-black or near-white with the title's colour
// on top: Light Bringer's cover is black with gold type, and it should tint the
// page gold, not grey.
func pickDominant(clusters []cluster) cluster {
	best, bestScore := clusters[0], -1.0
	for _, c := range clusters {
		colourful := 0.35 + math.Min(c.chroma()/0.12, 1)
		lightness := clamp((c.lab.l-0.10)/0.20, 0.12, 1) * clamp((0.98-c.lab.l)/0.18, 0.2, 1)
		if score := c.share * colourful * lightness; score > bestScore {
			best, bestScore = c, score
		}
	}
	// A neutral winner yields to a real colour that covers a visible part of
	// the artwork. Measured on the real cover on 2026-10-04: Light Bringer's
	// gold is thin type and line work, about a twentieth of the cover, and by
	// share alone the page came out near-black where it should read gold.
	if best.chroma() < 0.035 {
		var colour *cluster
		for i, c := range clusters {
			if c.share < 0.03 || c.chroma() < 0.07 || c.lab.l < 0.30 || c.lab.l > 0.92 {
				continue
			}
			if colour == nil || c.chroma()*math.Sqrt(c.share) > colour.chroma()*math.Sqrt(colour.share) {
				colour = &clusters[i]
			}
		}
		if colour != nil {
			return *colour
		}
	}
	return best
}

// pickVivid is the most saturated cluster that covers at least 3% of the
// artwork and is neither near-black nor near-white. A grey picture has none, and
// then the dominant colour is the closest thing to a glow it has.
func pickVivid(clusters []cluster, dominant cluster) cluster {
	best, bestScore := dominant, dominant.chroma()*math.Sqrt(dominant.share)
	for _, c := range clusters {
		if c.share < 0.03 || c.lab.l < 0.30 || c.lab.l > 0.92 {
			continue
		}
		if score := c.chroma() * math.Sqrt(c.share); score > bestScore && c.chroma() > dominant.chroma() {
			best, bestScore = c, score
		}
	}
	return best
}

func withLightnessChroma(c lab, lightness, chroma float64) lab {
	current := math.Hypot(c.a, c.b)
	if current < 1e-9 {
		return fitGamut(lab{lightness, 0, 0})
	}
	scale := chroma / current
	return fitGamut(lab{lightness, c.a * scale, c.b * scale})
}

func clamp(v, lo, hi float64) float64 { return math.Max(lo, math.Min(hi, v)) }
