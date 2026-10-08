package reading

import (
	"testing"
)

// Each case is a stylesheet and what the rewrite makes of it. A rewrite that
// finds nothing returns the input itself.
func TestRewriteFontSizesMakesAbsoluteSizesRelativeToTheRoot(t *testing.T) {
	for _, test := range []struct {
		name, in, want string
		count          int
	}{
		{"every keyword", `a{font-size:xx-small} b{font-size:x-small} c{font-size:small} d{font-size:medium} e{font-size:large} f{font-size:x-large} g{font-size:xx-large} h{font-size:xxx-large}`,
			`a{font-size:.5625rem} b{font-size:.625rem} c{font-size:.8125rem} d{font-size:1rem} e{font-size:1.125rem} f{font-size:1.5rem} g{font-size:2rem} h{font-size:3rem}`, 8},
		{"A Game of Thrones", `body { font-size: small; line-height: 1.3 } p.calibre1 { font-size: medium; text-indent: 1em }`,
			`body { font-size: .8125rem; line-height: 1.3 } p.calibre1 { font-size: 1rem; text-indent: 1em }`, 2},
		{"no spaces at all", `p{font-size:medium}h1{font-size:large}`, `p{font-size:1rem}h1{font-size:1.125rem}`, 2},
		{"several declarations on a line", `p{color:red;font-size:12px;margin:0;font-size:9pt}`, `p{color:red;font-size:.75rem;margin:0;font-size:.75rem}`, 2},
		{"pixels are a sixteenth", `a{font-size:16px} b{font-size:13px} c{font-size:80px} d{font-size:10.4px} e{font-size:1px}`,
			`a{font-size:1rem} b{font-size:.8125rem} c{font-size:5rem} d{font-size:.65rem} e{font-size:.0625rem}`, 5},
		{"points are a twelfth", `a{font-size:12pt} b{font-size:10pt} c{font-size:9pt} d{font-size:18pt} e{font-size:.5pt}`,
			`a{font-size:1rem} b{font-size:.8333rem} c{font-size:.75rem} d{font-size:1.5rem} e{font-size:.0417rem}`, 5},
		{"a sign and a dot on its own", `a{font-size:+12px} b{font-size:.8px}`, `a{font-size:.75rem} b{font-size:.05rem}`, 2},
		{"any case", `P{FONT-SIZE:MEDIUM} H1{Font-Size: 24PX} H2{font-size:Large!IMPORTANT}`, `P{FONT-SIZE:1rem} H1{Font-Size: 1.5rem} H2{font-size:1.125rem!IMPORTANT}`, 3},
		{"important is kept as written", `p{font-size:medium !important} q{font-size: 12px ! important ;} r{font-size:small!important}`,
			`p{font-size:1rem !important} q{font-size: .75rem ! important ;} r{font-size:.8125rem!important}`, 3},
		{"inside media rules", `@media screen and (min-width: 600px) { p { font-size: small } } @media print { @page { font-size: 9pt } }`,
			`@media screen and (min-width: 600px) { p { font-size: .8125rem } } @media print { @page { font-size: .75rem } }`, 2},
		{"a comment between the parts", `p{font-size /* a */ : /* b */ medium /* c */ ; color:red}`, `p{font-size /* a */ : /* b */ 1rem /* c */ ; color:red}`, 1},
		{"the last declaration with no closing", `p{font-size:medium`, `p{font-size:1rem`, 1},
		{"after a url", `@font-face{src:url(a.woff)} p{font-size:medium}`, `@font-face{src:url(a.woff)} p{font-size:1rem}`, 1},
		{"a newline in the value gap", "p{font-size:\n\tmedium\n}", "p{font-size:\n\t1rem\n}", 1},
	} {
		t.Run(test.name, func(t *testing.T) {
			got, count := rewriteFontSizes([]byte(test.in), false)
			if string(got) != test.want || count != test.count {
				t.Fatalf("\n got %q (%d)\nwant %q (%d)", got, count, test.want, test.count)
			}
		})
	}
}

func TestRewriteFontSizesLeavesEverythingElseAlone(t *testing.T) {
	for name, css := range map[string]string{
		"relative units":                         `a{font-size:1.2em} b{font-size:120%} c{font-size:1.5rem} d{font-size:2ex} e{font-size:3ch} f{font-size:4vw} g{font-size:5vmin} h{font-size:12Q}`,
		"relative keywords":                      `a{font-size:smaller} b{font-size:larger} c{font-size:inherit} d{font-size:initial} e{font-size:unset} f{font-size:math}`,
		"functions":                              `a{font-size:calc(12px + 1em)} b{font-size:var(--size)} c{font-size:min(12px, 2vw)} d{font-size:clamp(10px, 2vw, 20px)}`,
		"the font shorthand":                     `a{font:12px/1.5 serif} b{font: small-caps bold medium Georgia} c{font:medium serif}`,
		"other properties":                       `a{line-height:12px;margin:12px;width:medium;height:small;letter-spacing:10pt;text-indent:12px}`,
		"zero and negative":                      `a{font-size:0} b{font-size:0px} c{font-size:0.0pt} d{font-size:-12px}`,
		"more than one value":                    `a{font-size:12px 14px} b{font-size:medium serif} c{font-size:12px/14px}`,
		"no number and no unit":                  `a{font-size:px} b{font-size:12} c{font-size:.} d{font-size:1.2.3px} e{font-size:12 px}`,
		"units that are not px or pt":            `a{font-size:12pc} b{font-size:12mm} c{font-size:12cm} d{font-size:12in}`,
		"custom properties":                      `:root{--font-size:medium;--x:12px} a{font-size:var(--x)}`,
		"another property that ends in the name": `a{-webkit-font-size:medium;x-font-size:small;font-size-adjust:0.5}`,
		"hacks":                                  `a{*font-size:12px;_font-size:12px}`,
		"a selector that has the words":          `.font-size:hover{color:red} a:font-size{color:red} font-size{color:red} #font-size:not(.x){color:red}`,
		"comments":                               `/* p { font-size: medium } */ a{color:red} /* font-size: 12px; */`,
		"strings":                                `a{content:"font-size: medium;"} b{font-family:'x;font-size:small'} c[data-x="{font-size:12px}"]{color:red}`,
		"urls":                                   `a{background:url(font-size:medium;.png)} b{background:url( "x;font-size:small" )} c{background:URL(data:image/svg+xml;font-size:12px)}`,
		"at-rule preludes":                       `@supports (font-size: medium) { a{color:red} } @import url(a.css) screen; @charset "utf-8"; @media (font-size: 12px) { a{color:red} }`,
		"a bad stylesheet":                       `}}} ;;; font-size: medium }`,
		"nothing at all":                         ``,
	} {
		t.Run(name, func(t *testing.T) {
			input := []byte(css)
			got, count := rewriteFontSizes(input, false)
			if string(got) != css || count != 0 {
				t.Fatalf("\n got %q (%d)\nwant %q unchanged", got, count, css)
			}
			if len(input) > 0 && &got[0] != &input[0] {
				t.Fatal("an unchanged stylesheet was copied")
			}
		})
	}
}

// A declaration outside any block is not one in a stylesheet, but it is the whole
// of a style attribute.
func TestRewriteFontSizesReadsAStyleAttributeAsADeclarationList(t *testing.T) {
	for _, test := range []struct{ in, want string }{
		{`font-size:12px`, `font-size:.75rem`},
		{`color:red; font-size: small`, `color:red; font-size: .8125rem`},
		{`font-size:large;color:blue`, `font-size:1.125rem;color:blue`},
		{`FONT-SIZE : MEDIUM !important;`, `FONT-SIZE : 1rem !important;`},
		{`font-family:'Times; font-size:12px'; font-size:medium`, `font-family:'Times; font-size:12px'; font-size:1rem`},
		{`line-height:12px`, `line-height:12px`},
		{`font-size:1.2em`, `font-size:1.2em`},
		// An entity may be anything; what follows it is not known, so it is left.
		{`font-size:medium&#59;color:red`, `font-size:medium&#59;color:red`},
		{`font-size:12px&#59;`, `font-size:12px&#59;`},
	} {
		if got, _ := rewriteFontSizes([]byte(test.in), true); string(got) != test.want {
			t.Errorf("%q -> %q, want %q", test.in, got, test.want)
		}
	}
	// The same text as a stylesheet is no declaration.
	if got, count := rewriteFontSizes([]byte(`font-size:12px`), false); string(got) != `font-size:12px` || count != 0 {
		t.Errorf("a declaration outside a block in a stylesheet: %q (%d)", got, count)
	}
}

// What a rewrite makes can be rewritten again and stays: it is the form it asked
// for.
func TestRewriteFontSizesIsIdempotent(t *testing.T) {
	once, count := rewriteFontSizes([]byte(`body{font-size:small} p{font-size:12pt!important} h1{font-size:30px}`), false)
	if count != 3 {
		t.Fatalf("count = %d", count)
	}
	twice, again := rewriteFontSizes(once, false)
	if string(twice) != string(once) || again != 0 {
		t.Fatalf("a second pass changed %q to %q (%d)", once, twice, again)
	}
}
