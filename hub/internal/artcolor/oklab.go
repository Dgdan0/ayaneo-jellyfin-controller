package artcolor

import (
	"fmt"
	"math"
)

// lab is a colour in Oklab (Björn Ottosson, 2020): l is lightness from 0 to 1,
// and a and b place the hue; their length is the chroma. Distances in it match
// how different two colours look, which RGB distances do not.
type lab struct{ l, a, b float64 }

func (c lab) distance(o lab) float64 {
	dl, da, db := c.l-o.l, c.a-o.a, c.b-o.b
	return dl*dl + da*da + db*db
}

// fromSRGB takes sRGB components from 0 to 1.
func fromSRGB(r, g, b float64) lab {
	r, g, b = toLinear(r), toLinear(g), toLinear(b)
	l := math.Cbrt(0.4122214708*r + 0.5363325363*g + 0.0514459929*b)
	m := math.Cbrt(0.2119034982*r + 0.6806995451*g + 0.1073969566*b)
	s := math.Cbrt(0.0883024619*r + 0.2817188376*g + 0.6299787005*b)
	return lab{
		l: 0.2104542553*l + 0.7936177850*m - 0.0040720468*s,
		a: 1.9779984951*l - 2.4285922050*m + 0.4505937099*s,
		b: 0.0259040371*l + 0.7827717662*m - 0.8086757660*s,
	}
}

// toSRGB returns sRGB components that may fall outside 0..1 when the colour
// cannot be shown; fitGamut brings it inside first.
func toSRGB(c lab) (float64, float64, float64) {
	l := c.l + 0.3963377774*c.a + 0.2158037573*c.b
	m := c.l - 0.1055613458*c.a - 0.0638541728*c.b
	s := c.l - 0.0894841775*c.a - 1.2914855480*c.b
	l, m, s = l*l*l, m*m*m, s*s*s
	return fromLinear(+4.0767416621*l - 3.3077115913*m + 0.2309699292*s),
		fromLinear(-1.2684380046*l + 2.6097574011*m - 0.3413193965*s),
		fromLinear(-0.0041960863*l - 0.7034186147*m + 1.7076147010*s)
}

func toLinear(c float64) float64 {
	if c <= 0.04045 {
		return c / 12.92
	}
	return math.Pow((c+0.055)/1.055, 2.4)
}

func fromLinear(c float64) float64 {
	if c <= 0.0031308 {
		return 12.92 * c
	}
	return 1.055*math.Pow(c, 1/2.4) - 0.055
}

func inGamut(c lab) bool {
	r, g, b := toSRGB(c)
	const e = 1e-4
	return r >= -e && r <= 1+e && g >= -e && g <= 1+e && b >= -e && b <= 1+e
}

// fitGamut keeps lightness and hue and gives up chroma until the colour can be
// shown, so a derived dark or pale tone never shifts hue by being clipped.
func fitGamut(c lab) lab {
	c.l = clamp(c.l, 0, 1)
	if inGamut(c) {
		return c
	}
	lo, hi := 0.0, 1.0
	for i := 0; i < 24; i++ {
		mid := (lo + hi) / 2
		if inGamut(lab{c.l, c.a * mid, c.b * mid}) {
			lo = mid
		} else {
			hi = mid
		}
	}
	return lab{c.l, c.a * lo, c.b * lo}
}

func hex(c lab) string {
	r, g, b := toSRGB(c)
	byteOf := func(v float64) int { return int(math.Round(clamp(v, 0, 1) * 255)) }
	return fmt.Sprintf("#%02x%02x%02x", byteOf(r), byteOf(g), byteOf(b))
}
