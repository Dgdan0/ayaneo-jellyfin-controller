package reading

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The book's own right, centre and end alignment under the reader's Justify (#57).

// alignDoc is a document with one <style> element and the given body.
func alignDoc(style, body string) string {
	return `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style type="text/css">` + style + `</style></head><body>` + body + `</body></html>`
}

// alignedBy runs a document through the restyle and takes the column style out of
// what comes back, which is not what these tests are about. A document that was
// well-formed must still be, and a second pass over the result must find nothing
// left to do.
func alignedBy(t *testing.T, doc string, ctx documentContext) (string, documentResult) {
	t.Helper()
	out, result := restyleDocument([]byte(doc), ctx)
	if wellFormed([]byte(doc)) && !wellFormed(out) {
		t.Fatalf("the result is not well-formed:\n%s", out)
	}
	if again, second := restyleDocument(out, ctx); second.aligned != 0 || !bytes.Equal(again, out) {
		t.Fatalf("a second pass changed the document (%d):\n%s", second.aligned, again)
	}
	return strings.Replace(string(out), columnStyleElement, "", 1), result
}

func TestAlignmentIsKeptWhenTheBookAlignsRightCentreOrEnd(t *testing.T) {
	for name, test := range map[string]struct{ style, body, want string }{
		"Babel's epigraph attribution": {
			`.epi_at { margin-left:1em; text-align:right; }`,
			`<p class="epi_at">A<span class="make_smallcaps">NTONIO DE</span> N<span class="make_smallcaps">EBRIJA</span></p>`,
			`<p style="text-align: right !important" class="epi_at">A<span class="make_smallcaps">NTONIO DE</span> N<span class="make_smallcaps">EBRIJA</span></p>`,
		},
		"a centred scene break":      {`p.break { text-align: center }`, `<p class="break">* * *</p>`, `<p style="text-align: center !important" class="break">* * *</p>`},
		"end":                        {`.sig { text-align: end }`, `<p class="sig">Anon</p>`, `<p style="text-align: end !important" class="sig">Anon</p>`},
		"a list item":                {`li.page { text-align: right }`, `<ul><li class="page">12</li></ul>`, `<ul><li style="text-align: right !important" class="page">12</li></ul>`},
		"an id":                      {`#last { text-align: right }`, `<p id="first">a</p><p id="last">b</p>`, `<p id="first">a</p><p style="text-align: right !important" id="last">b</p>`},
		"a type with an id":          {`p#last { text-align: right }`, `<p id="last">b</p>`, `<p style="text-align: right !important" id="last">b</p>`},
		"two classes":                {`.a.b { text-align: center }`, `<p class="a">1</p><p class="b a">2</p>`, `<p class="a">1</p><p style="text-align: center !important" class="b a">2</p>`},
		"a list of selectors":        {`h1, .x , p.y { text-align: center }`, `<p class="y">1</p><p class="x">2</p><p class="z">3</p>`, `<p style="text-align: center !important" class="y">1</p><p style="text-align: center !important" class="x">2</p><p class="z">3</p>`},
		"a vendor spelling":          {`.c { text-align: -webkit-center }`, `<p class="c">1</p>`, `<p style="text-align: center !important" class="c">1</p>`},
		"any case, odd spacing":      {"P.C {\n  TEXT-ALIGN : CENTER ;\n}", `<p class="C">1</p>`, `<p style="text-align: center !important" class="C">1</p>`},
		"an important rule":          {`.c { text-align: center !important }`, `<p class="c">1</p>`, `<p style="text-align: center !important" class="c">1</p>`},
		"comments in the rule":       {`/* a */ .c /* b */ { /* c */ text-align: /* d */ center /* e */ ; }`, `<p class="c">1</p>`, `<p style="text-align: center !important" class="c">1</p>`},
		"CDATA around the sheet":     {"/*<![CDATA[*/ .c { text-align: center } /*]]>*/", `<p class="c">1</p>`, `<p style="text-align: center !important" class="c">1</p>`},
		"an old-style comment wrap":  {"<!--\n.c { text-align: center }\n-->", `<p class="c">1</p>`, `<p style="text-align: center !important" class="c">1</p>`},
		"a rule after a font-face":   {`@font-face { font-family: X; src: url(x.otf) } @charset "utf-8"; .c { text-align: right }`, `<p class="c">1</p>`, `<p style="text-align: right !important" class="c">1</p>`},
		"a self-closing paragraph":   {`.c { text-align: center }`, `<p class="c"/>`, `<p style="text-align: center !important" class="c"/>`},
		"the class has no neighbour": {`.c { text-align: center }`, `<p class='c' id='k'>1</p>`, `<p style="text-align: center !important" class='c' id='k'>1</p>`},
	} {
		t.Run(name, func(t *testing.T) {
			doc := alignDoc(test.style, `<p class="before">x</p>`+test.body)
			out, result := alignedBy(t, doc, documentContext{})
			want := alignDoc(test.style, `<p class="before">x</p>`+test.want)
			if out != want {
				t.Fatalf("\n got %s\nwant %s", out, want)
			}
			if result.aligned != strings.Count(test.want, `!important"`) {
				t.Fatalf("aligned = %d, want %d", result.aligned, strings.Count(test.want, `!important"`))
			}
		})
	}
}

func TestAlignmentOfATypeSelectorIsGivenToEveryOfItsKind(t *testing.T) {
	const style = `p { text-align: center } li { text-align: right }`
	out, result := alignedBy(t, alignDoc(style, `<p>a</p><ul><li>b</li></ul><div>c</div>`), documentContext{})
	if want := alignDoc(style, `<p style="text-align: center !important">a</p><ul><li style="text-align: right !important">b</li></ul><div>c</div>`); out != want || result.aligned != 2 {
		t.Fatalf("\n got %s (%d)\nwant %s", out, result.aligned, want)
	}
}

func TestAlignmentIsLeftToTheReaderWhenTheBookJustifiesOrLeavesItLeft(t *testing.T) {
	for _, value := range []string{"justify", "left", "start", "inherit", "unset", "match-parent", "justify-all", "initial", "nonsense", "-webkit-auto"} {
		t.Run(value, func(t *testing.T) {
			doc := alignDoc(`.c { text-align: `+value+` }`, `<p class="c">1</p><ul><li class="c">2</li></ul><p>3</p>`)
			out, result := alignedBy(t, doc, documentContext{})
			if out != doc || result.aligned != 0 {
				t.Fatalf("aligned %d:\n%s", result.aligned, out)
			}
		})
	}
}

func TestAlignmentIsOnlyAskedOfWhatReadiumCSSReplaces(t *testing.T) {
	// Readium CSS puts `text-align: inherit !important` on p, li and body, and on nothing else.
	doc := alignDoc(`h1, h2, .c, td, blockquote, dd, figcaption, div { text-align: center }`,
		`<h1 class="c">Title</h1><h2>Part</h2><div class="c"><p>inside a centred div</p></div>`+
			`<blockquote class="c"><p>inside a centred blockquote</p></blockquote>`+
			`<table><tr><td class="c">cell</td></tr></table><dl><dd class="c">d</dd></dl><figure><figcaption class="c">c</figcaption></figure>`)
	out, result := alignedBy(t, doc, documentContext{})
	// The paragraphs inherit what the div and the blockquote say, which the reader leaves.
	if out != doc || result.aligned != 0 {
		t.Fatalf("aligned %d:\n%s", result.aligned, out)
	}
}

func TestAlignmentIsMergedWithTheStyleAttributeTheElementHas(t *testing.T) {
	for name, test := range map[string]struct {
		style, body, want string
		count             int
	}{
		"other declarations stay": {
			`.c { text-align: center }`,
			`<p class="c" style="color:red">1</p>`,
			`<p class="c" style="color:red; text-align: center !important">1</p>`, 1,
		},
		"a trailing semicolon": {
			`.c { text-align: center }`,
			`<p class="c" style="color:red; ">1</p>`,
			`<p class="c" style="color:red; text-align: center !important;">1</p>`, 1,
		},
		"an empty attribute": {
			`.c { text-align: right }`,
			`<p class="c" style="">1</p>`,
			`<p class="c" style="text-align: right !important">1</p>`, 1,
		},
		"single quotes": {
			`.c { text-align: right }`,
			`<p class='c' style='margin:0'>1</p>`,
			`<p class='c' style='margin:0; text-align: right !important'>1</p>`, 1,
		},
		"the attribute's own alignment wins and is made important where it stands": {
			``,
			`<p style="text-align:right;color:red">1</p><p style='color:red ; TEXT-ALIGN: CENTER'>2</p>`,
			`<p style="text-align:right !important;color:red">1</p><p style='color:red ; TEXT-ALIGN: CENTER !important'>2</p>`, 2,
		},
		"the attribute's alignment over the class": {
			`.j { text-align: justify }`,
			`<p class="j" style="text-align:center">1</p>`,
			`<p class="j" style="text-align:center !important">1</p>`, 1,
		},
		"already important, so already outranking the reader's": {
			`.c { text-align: justify }`,
			`<p class="c" style="text-align:center !important">1</p>`,
			`<p class="c" style="text-align:center !important">1</p>`, 0,
		},
		"the attribute says left, over a centring class": {
			`.c { text-align: center }`,
			`<p class="c" style="text-align:left">1</p>`,
			`<p class="c" style="text-align:left">1</p>`, 0,
		},
		"an important class over the attribute that is not": {
			`.c { text-align: center !important }`,
			`<p class="c" style="text-align:left">1</p>`,
			`<p class="c" style="text-align:left; text-align: center !important">1</p>`, 1,
		},
		"font sizes and alignment in one attribute": {
			`.c { text-align: center }`,
			`<p class="c" style="font-size:12px;line-height:18px">1</p>`,
			`<p class="c" style="font-size:.75rem;line-height:1.125rem; text-align: center !important">1</p>`, 1,
		},
		"a semicolon inside a url": {
			`.c { text-align: center }`,
			`<p class="c" style="background:url(data:image/png;base64,AAAA)">1</p>`,
			`<p class="c" style="background:url(data:image/png;base64,AAAA); text-align: center !important">1</p>`, 1,
		},
	} {
		t.Run(name, func(t *testing.T) {
			out, result := alignedBy(t, alignDoc(test.style, test.body), documentContext{})
			if want := alignDoc(test.style, test.body); out == want && test.count != 0 {
				t.Fatalf("nothing changed:\n%s", out)
			}
			want := strings.Replace(alignDoc(test.style, test.want), "font-size:12px", "font-size:12px", 1)
			if out != want || result.aligned != test.count {
				t.Fatalf("\n got %s (%d)\nwant %s (%d)", out, result.aligned, want, test.count)
			}
		})
	}
}

func TestAlignmentIsNotGuessedFromSelectorsItDoesNotEvaluate(t *testing.T) {
	// None of these says, to this pass, that the paragraph is centred.
	for name, style := range map[string]string{
		"a descendant":              `div p { text-align: center }`,
		"a child":                   `blockquote > p { text-align: center }`,
		"a sibling":                 `h2 + p { text-align: center }`,
		"a general sibling":         `h2 ~ p { text-align: center }`,
		"a class on an ancestor":    `.verse p { text-align: center }`,
		"an attribute":              `p[class="c"] { text-align: center }`,
		"an attribute and a class":  `p.c[lang] { text-align: center }`,
		"a pseudo-class":            `p.c:first-child { text-align: center }`,
		"a negation":                `p:not(.x) { text-align: center }`,
		"inside @media":             `@media screen { p.c { text-align: center } }`,
		"inside @supports":          `@supports (display: grid) { .c { text-align: center } }`,
		"an escape":                 `p\.c { text-align: center }`,
		"another element":           `div.c, span.c { text-align: center }`,
		"a pseudo-element":          `p.c::first-line { text-align: center }`,
		"a first-letter":            `p.c:first-letter { text-align: center }`,
		"after an @import":          `@import url(other.css); .z { text-align: center }`,
		"nothing but a comment":     `/* .c { text-align: center } */`,
		"another property":          `.c { text-indent: 0; margin: 0 auto }`,
		"the property in a value":   `.c { content: "text-align: center"; x: y }`,
		"a custom property":         `.c { --text-align: center; text-align-last: center }`,
		"a different class":         `.cc { text-align: center } .c-x { text-align: center } .C { text-align: center }`,
		"a prefixed property":       `.c { -webkit-text-align: center }`,
		"an unclosed block comment": `.c { text-align: justify } /* .c { text-align: center }`,
	} {
		t.Run(name, func(t *testing.T) {
			doc := alignDoc(style, `<p class="c">1</p><div class="verse"><p>2</p></div><h2>x</h2><p>3</p>`)
			out, result := alignedBy(t, doc, documentContext{})
			if out != doc || result.aligned != 0 {
				t.Fatalf("aligned %d:\n%s", result.aligned, out)
			}
		})
	}
}

func TestAlignmentIsLeftAloneWhenARuleItDoesNotEvaluateCouldChangeIt(t *testing.T) {
	for name, test := range map[string]struct {
		style string
		want  string // the paragraph <p class="c">
	}{
		"a more specific rule on a descendant says left": {`.c { text-align: center } div p.c { text-align: left }`, `<p class="c">`},
		"one inside @media that comes later":             {`.c { text-align: center } @media screen { .c { text-align: left } }`, `<p class="c">`},
		"an attribute rule that comes later":             {`.c { text-align: center } p[class] { text-align: left }`, `<p class="c">`},
		"a pseudo-class rule that comes later":           {`.c { text-align: center } .c:first-child { text-align: justify }`, `<p class="c">`},
		"an unreadable selector":                         {`.c { text-align: center } \61 { text-align: left }`, `<p class="c">`},
		// A rule that cannot win, or that says the same, does not stop it.
		"a less specific complex rule":    {`p.c { text-align: center } div p { text-align: left }`, `<p style="text-align: center !important" class="c">`},
		"a complex rule that agrees":      {`.c { text-align: center } div .c { text-align: center }`, `<p style="text-align: center !important" class="c">`},
		"a complex rule that comes first": {`div .c { text-align: left } p.c { text-align: center }`, `<p style="text-align: center !important" class="c">`},
		"one that comes after":            {`p.c { text-align: center } div .c { text-align: left }`, `<p class="c">`},
		"an unfinished rule at the end":   {`.c { text-align: center`, `<p style="text-align: center !important" class="c">`},
		"a rule on a pseudo-element":      {`.c { text-align: center } .c::first-line { text-align: left }`, `<p style="text-align: center !important" class="c">`},
	} {
		t.Run(name, func(t *testing.T) {
			doc := alignDoc(test.style, `<p class="c">1</p>`)
			out, _ := alignedBy(t, doc, documentContext{})
			if want := alignDoc(test.style, test.want+`1</p>`); out != want {
				t.Fatalf("\n got %s\nwant %s", out, want)
			}
		})
	}
}

func TestAlignmentFollowsTheCascadeOfTheBook(t *testing.T) {
	for name, test := range map[string]struct {
		style, element string
		kept           bool
	}{
		"a class over a type":                                {`p { text-align: justify } .c { text-align: center }`, `<p class="c">`, true},
		"a type that a class overrides":                      {`p { text-align: center } .j { text-align: justify }`, `<p class="j">`, false},
		"a type alone":                                       {`p { text-align: center } .j { text-align: justify }`, `<p>`, true},
		"a type and class over a class":                      {`.a { text-align: center } p.a { text-align: justify }`, `<p class="a">`, false},
		"later at equal specificity":                         {`.a { text-align: justify } .b { text-align: center }`, `<p class="a b">`, true},
		"earlier loses at equal specificity":                 {`.a { text-align: center } .b { text-align: justify }`, `<p class="a b">`, false},
		"an id over a class":                                 {`#i { text-align: justify } p.c.d { text-align: center }`, `<p id="i" class="c d">`, false},
		"a class over an id elsewhere":                       {`#i { text-align: justify } p.c.d { text-align: center }`, `<p class="c d">`, true},
		"an important earlier rule over a later one":         {`.a { text-align: center !important } .b { text-align: justify }`, `<p class="a b">`, true},
		"an important rule over a more specific one":         {`p.b.b { text-align: justify } .a { text-align: center !important }`, `<p class="a b">`, true},
		"the last declaration of a rule":                     {`.a { text-align: justify; text-align: center }`, `<p class="a">`, true},
		"an important declaration over a later one":          {`.a { text-align: center !important; text-align: justify }`, `<p class="a">`, true},
		"the last declaration is the one that loses":         {`.a { text-align: center; text-align: left }`, `<p class="a">`, false},
		"a second style element comes later":                 {`.a { text-align: justify }</style><style>.a { text-align: center }`, `<p class="a">`, true},
		"a later style element that justifies":               {`.a { text-align: center }</style><style>.a { text-align: justify }`, `<p class="a">`, false},
		"a body that justifies leaves its paragraph its own": {`body { text-align: justify } .a { text-align: center }`, `<p class="a">`, true},
	} {
		t.Run(name, func(t *testing.T) {
			doc := alignDoc(test.style, test.element+`1</p>`)
			out, result := alignedBy(t, doc, documentContext{})
			if kept := result.aligned == 1; kept != test.kept {
				t.Fatalf("kept = %v, want %v:\n%s", kept, test.kept, out)
			}
		})
	}
}

func TestRightIsTheStartOfALineInARightToLeftBookAndIsNotKept(t *testing.T) {
	style := `.r { text-align: right } .c { text-align: center } .e { text-align: end }`
	body := `<p class="r">1</p><p class="c">2</p><p class="e">3</p>`
	for name, test := range map[string]struct {
		doc string
		ctx documentContext
		// right: the right-aligned paragraph is kept; the other two always are.
		right bool
	}{
		"left to right":                       {alignDoc(style, body), documentContext{language: "en"}, true},
		"a Hebrew package":                    {alignDoc(style, body), documentContext{language: "he"}, false},
		"an Arabic package with a tag":        {alignDoc(style, body), documentContext{language: "ar-EG"}, false},
		"a root in Hebrew":                    {strings.Replace(alignDoc(style, body), "<html ", `<html lang="he" `, 1), documentContext{language: "en"}, false},
		"a root in English, a Hebrew package": {strings.Replace(alignDoc(style, body), "<html ", `<html xml:lang="en" `, 1), documentContext{language: "he"}, true},
		"a root with dir":                     {strings.Replace(alignDoc(style, body), "<html ", `<html dir="rtl" `, 1), documentContext{}, false},
		"a dir that says left to right":       {strings.Replace(alignDoc(style, body), "<html ", `<html dir="ltr" `, 1), documentContext{language: "he"}, true},
		"a body with dir":                     {strings.Replace(alignDoc(style, body), "<body>", `<body dir="rtl">`, 1), documentContext{}, false},
		"a rule for direction":                {alignDoc(style+` body { direction: rtl }`, body), documentContext{}, false},
		"a paragraph with dir":                {alignDoc(style, strings.Replace(body, `<p class="r">`, `<p class="r" dir="rtl">`, 1)), documentContext{}, false},
		"a paragraph with direction":          {alignDoc(style, strings.Replace(body, `<p class="r">`, `<p class="r" style="direction:rtl">`, 1)), documentContext{}, false},
	} {
		t.Run(name, func(t *testing.T) {
			out, result := alignedBy(t, test.doc, test.ctx)
			want := 2
			if test.right {
				want = 3
			}
			if result.aligned != want {
				t.Fatalf("aligned %d, want %d:\n%s", result.aligned, want, out)
			}
			if strings.Contains(out, `class="r"`) && strings.Contains(out, `text-align: right !important"`) != test.right {
				t.Fatalf("right kept = %v, want %v:\n%s", !test.right, test.right, out)
			}
			if !strings.Contains(out, `text-align: center !important"`) || !strings.Contains(out, `text-align: end !important"`) {
				t.Fatalf("centre and end are kept in every direction:\n%s", out)
			}
		})
	}
}

func TestABodyThatIsAlignedGivesItsAlignmentToWhatInheritsIt(t *testing.T) {
	const style = `body.title { text-align: center } .j { text-align: justify } .l { text-align: left } h1 { text-align: left }`
	body := func(open, inside string) string { return strings.Replace(`<body>`+inside+`</body>`, `<body>`, open, 1) }
	doc := func(open, inside string) string {
		return `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><style>` + style + `</style></head>` + body(open, inside) + `</html>`
	}
	// Paragraphs that say nothing of their own take their body's.
	out, result := alignedBy(t, doc(`<body class="title">`, `<h1>Title</h1><p>by someone</p>`), documentContext{})
	if want := doc(`<body style="text-align: center !important" class="title">`, `<h1>Title</h1><p>by someone</p>`); out != want || result.aligned != 1 {
		t.Fatalf("\n got %s (%d)\nwant %s", out, result.aligned, want)
	}
	// A paragraph or list item that is justified, or left, would take it as well.
	for _, inside := range []string{
		`<p class="j">1</p><p>2</p>`,
		`<p>1</p><ul><li class="l">2</li></ul>`,
		`<p style="text-align:justify">1</p>`,
	} {
		out, result := alignedBy(t, doc(`<body class="title">`, inside), documentContext{})
		if out != doc(`<body class="title">`, inside) || result.aligned != 0 {
			t.Errorf("a body was aligned over %s:\n%s", inside, out)
		}
	}
	// One that is centred itself is kept all the same, and the body with it.
	out, result = alignedBy(t, doc(`<body class="title">`, `<p style="text-align:center">1</p>`), documentContext{})
	if result.aligned != 2 || !strings.Contains(out, `<body style="text-align: center !important" class="title">`) || !strings.Contains(out, `text-align:center !important`) {
		t.Fatalf("aligned %d:\n%s", result.aligned, out)
	}
}

func TestAnElementItCannotEditWithCertaintyIsLeftAlone(t *testing.T) {
	style := `.c { text-align: center }`
	for name, body := range map[string]string{
		"an unquoted style":      `<p class="c" style=color:red>1</p>`,
		"two style attributes":   `<p class="c" style="color:red" style="color:blue">1</p>`,
		"an entity in a class":   `<p class="c&#32;d">1</p>`,
		"two class attributes":   `<p class="c" class="d">1</p>`,
		"a namespaced element":   `<h:p class="c">1</h:p>`,
		"inside an illustration": `<svg xmlns="http://www.w3.org/2000/svg"><foreignObject><p class="c">1</p></foreignObject></svg>`,
	} {
		t.Run(name, func(t *testing.T) {
			doc := alignDoc(style, body)
			out, result := alignedBy(t, doc, documentContext{})
			if out != doc || result.aligned != 0 {
				t.Fatalf("aligned %d:\n%s", result.aligned, out)
			}
		})
	}
	// A document in HTML that is not XML is read as far as it goes.
	out, result := alignedBy(t, `<html><head><style>.c { text-align: center }</style></head><body><p class=c>1<p class="c">2<br><p class="d">3`, documentContext{})
	if !strings.Contains(out, `<p style="text-align: center !important" class=c>`) || !strings.Contains(out, `<p style="text-align: center !important" class="c">`) || strings.Contains(out, `class="d"`+`>`) && strings.Contains(out, `<p style="text-align: center !important" class="d">`) || result.aligned != 2 {
		t.Fatalf("aligned %d:\n%s", result.aligned, out)
	}
}

func TestAlignmentOfAParserIsNotFooledByTheShapeOfTheSheet(t *testing.T) {
	sheet := parseAlignSheet([]byte("@charset \"utf-8\";\n@import url('a;b.css');\n/* .x { text-align: right } */\n" +
		".a { background: url(x.png?a=1;b=2); text-align: right }\n" +
		".b { content: \"}\"; text-align: center }\n" +
		"@media print { .c { text-align: right } }\n" +
		"@font-face { src: url(f.otf) }\n" +
		".d, .e .f, p.g:hover { text-align: end; direction: rtl }\n" +
		"p::before { text-align: right }\n"))
	type seen struct {
		maybe bool
		value string
	}
	var got []seen
	for _, rule := range sheet.rules {
		got = append(got, seen{rule.maybe, rule.value})
	}
	want := []seen{{false, "right"}, {false, "center"}, {true, "right"}, {false, "end"}, {true, "end"}, {true, "end"}}
	if len(got) != len(want) {
		t.Fatalf("rules = %+v", sheet.rules)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("rule %d = %+v, want %+v", i, got[i], want[i])
		}
	}
	if !sheet.rightToLeft {
		t.Error("a rule that sets direction: rtl was not noticed")
	}
	if plain := parseAlignSheet([]byte(`.a { text-align: right }`)); plain.rightToLeft {
		t.Error("a direction came from nowhere")
	}
}

func TestAStylesheetWithAByteOrderMarkIsReadFromItsFirstRule(t *testing.T) {
	sheet := parseAlignSheet([]byte("\xEF\xBB\xBF.c { text-align: center }"))
	if len(sheet.rules) != 1 || sheet.rules[0].maybe || sheet.rules[0].value != "center" || len(sheet.rules[0].subject.classes) != 1 {
		t.Fatalf("rules = %+v", sheet.rules)
	}
}

func TestSelectorsAreSplitIntoWhatTheyAreAbout(t *testing.T) {
	for _, test := range []struct {
		selector    string
		about       bool
		exact       bool
		name        string
		classes     string
		ids         string
		specificity int
	}{
		{".a", true, true, "", "a", "", 100},
		{"p.a.b", true, true, "p", "a,b", "", 201},
		{"li#x.a", true, true, "li", "a", "x", 10101},
		{"body", true, true, "body", "", "", 1},
		{"*.a", true, true, "*", "a", "", 100},
		{"div .a", true, false, "", "a", "", 101},
		{"div > p", true, false, "p", "", "", 2},
		{"p:first-child", true, false, "p", "", "", 101},
		{"p[lang|=en]", true, false, "p", "", "", 101},
		{"p:not(.a .b)", true, false, "p", "", "", 101},
		{"div", false, false, "", "", "", 0},
		{"span.a", false, false, "", "", "", 0},
		{"p::first-line", false, false, "", "", "", 0},
		{"p:first-letter", false, false, "", "", "", 0},
		{"", false, false, "", "", "", 0},
	} {
		got, about := parseAlignSelector(test.selector)
		if about != test.about {
			t.Errorf("%q: about = %v", test.selector, about)
			continue
		}
		if !about {
			continue
		}
		if got.exact != test.exact || got.subject.name != test.name || strings.Join(got.subject.classes, ",") != test.classes ||
			strings.Join(got.subject.ids, ",") != test.ids || got.specificity != test.specificity {
			t.Errorf("%q = %+v", test.selector, got)
		}
	}
	// One that cannot be read is about any element, and weighs more than anything.
	if got, about := parseAlignSelector(`p\.a`); !about || !got.subject.any || got.exact || got.specificity <= 1000000 {
		t.Errorf("an escape = %+v", got)
	}
}

func TestStylesheetsOfTheBookAreFoundThroughTheLinksOfEachDocument(t *testing.T) {
	const (
		// OEBPS/Styles/book.css and base.css, after the documents in the archive; a
		// second book.css next to the document; a print sheet.
		page = `<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>%s</title>%s</head><body><p class="c">1</p><p class="r">2</p><p class="j">3</p></body></html>`
	)
	links := func(hrefs ...string) string {
		var out strings.Builder
		for _, href := range hrefs {
			out.WriteString(`<link rel="stylesheet" type="text/css" href="` + href + `"/>`)
		}
		return out.String()
	}
	path := makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", bookPackage("",
			`<item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="ch3" href="Text/ch3.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="ch4" href="Text/ch4.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="base" href="Styles/base.css" media-type="text/css"/>`,
			`<item id="css" href="Styles/book.css" media-type="text/css"/>`,
			`<item id="print" href="Styles/print.css" media-type="text/css"/>`), false},
		// Chapter 1: both sheets, the second overrides .r.
		{"OEBPS/Text/ch1.xhtml", fmt.Sprintf(page, "One", links("../Styles/base.css", "../Styles/book.css")), false},
		// Chapter 2: the book's sheet only, by a percent-encoded address.
		{"OEBPS/Text/ch2.xhtml", fmt.Sprintf(page, "Two", links("../Styles/book%2Ecss")), false},
		// Chapter 3: no sheet that exists, and one for print.
		{"OEBPS/Text/ch3.xhtml", fmt.Sprintf(page, "Three", links("../Styles/gone.css")+`<link rel="stylesheet" media="print" href="../Styles/print.css"/>`+`<link rel="alternate stylesheet" href="../Styles/book.css"/>`), false},
		// Chapter 4: a <style> element after a link, which wins.
		{"OEBPS/Text/ch4.xhtml", fmt.Sprintf(page, "Four", links("../Styles/book.css")+`<style>.c { text-align: justify }</style>`), false},
		{"OEBPS/Styles/base.css", `.c { text-align: center } .r { text-align: center } .j { text-align: justify }`, false},
		{"OEBPS/Styles/book.css", `@charset "utf-8"; .r { text-align: right } p { font-size: medium }`, false},
		{"OEBPS/Styles/print.css", `.j { text-align: center }`, false},
		{"OEBPS/images/cover.jpg", "not really a jpeg", true},
	})
	data, report := copyOf(t, path, CopyOptions{Restyle: true})
	_, copied := archiveOf(t, data)
	paragraph := func(entry, class string) string {
		doc := string(copied[entry])
		at := strings.Index(doc, `class="`+class+`"`)
		if at < 0 {
			t.Fatalf("%s has no %s:\n%s", entry, class, doc)
		}
		start := strings.LastIndex(doc[:at], "<p")
		return doc[start : at+len(`class="`+class+`">`)]
	}
	aligned := func(entry, class string) string {
		switch paragraph(entry, class) {
		case `<p style="text-align: center !important" class="` + class + `">`:
			return "center"
		case `<p style="text-align: right !important" class="` + class + `">`:
			return "right"
		case `<p class="` + class + `">`:
			return ""
		}
		return "?" + paragraph(entry, class)
	}
	for _, test := range []struct{ entry, c, r, j string }{
		{"OEBPS/Text/ch1.xhtml", "center", "right", ""},
		{"OEBPS/Text/ch2.xhtml", "", "right", ""},
		{"OEBPS/Text/ch3.xhtml", "", "", ""},
		{"OEBPS/Text/ch4.xhtml", "", "right", ""},
	} {
		if got := [3]string{aligned(test.entry, "c"), aligned(test.entry, "r"), aligned(test.entry, "j")}; got != [3]string{test.c, test.r, test.j} {
			t.Errorf("%s: c, r, j = %q, want %q", test.entry, got, [3]string{test.c, test.r, test.j})
		}
	}
	// Two in the first, one in the second and in the fourth (whose own sheet justifies .c), none in the third.
	// Edited: the four documents (each gets its column style) and book.css (a font size).
	if report.Aligned != 4 || report.Edited != 5 || report.FixedLayout || len(report.Left) != 0 {
		t.Errorf("report = %+v", report)
	}
	for name, content := range copied {
		if strings.HasSuffix(name, ".xhtml") && !wellFormed(content) {
			t.Errorf("%s is not well-formed:\n%s", name, content)
		}
	}
	// Made again from its own copy, nothing changes.
	again := filepath.Join(t.TempDir(), "again.epub")
	if err := os.WriteFile(again, data, 0o644); err != nil {
		t.Fatal(err)
	}
	second, secondReport := copyOf(t, again, CopyOptions{Restyle: true})
	if secondReport.Aligned != 0 || secondReport.Edited != 0 || !bytes.Equal(second, data) {
		t.Errorf("a second pass: %+v", secondReport)
	}
}

func TestAlignmentIsNotTouchedInACopyThatIsNotRestyledOrInAFixedLayoutBook(t *testing.T) {
	const doc = `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title><link rel="stylesheet" href="s.css"/></head><body><p class="c">1</p></body></html>`
	files := func(extraMeta string) []bookFile {
		return []bookFile{
			{"META-INF/container.xml", bookContainer, false},
			{"OEBPS/content.opf", bookPackage(extraMeta,
				`<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>`,
				`<item id="css" href="s.css" media-type="text/css"/>`), false},
			{"OEBPS/s.css", `.c { text-align: center }`, false},
			{"OEBPS/ch1.xhtml", doc, false},
		}
	}
	for name, test := range map[string]struct {
		files   []bookFile
		options CopyOptions
	}{
		"without Restyle":   {files(""), CopyOptions{}},
		"audio left out":    {files(""), CopyOptions{OmitAudio: true}},
		"a fixed layout":    {files(`<meta property="rendition:layout">pre-paginated</meta>`), CopyOptions{Restyle: true}},
		"narration mended":  {files(""), CopyOptions{MendNarration: true}},
		"a plain narration": {files(""), CopyOptions{OmitAudio: true, MendNarration: true}},
	} {
		t.Run(name, func(t *testing.T) {
			path := makeBook(t, test.files)
			original, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			data, report := copyOf(t, path, test.options)
			if report.Aligned != 0 || report.Edited != 0 {
				t.Fatalf("report = %+v", report)
			}
			_, copied := archiveOf(t, data)
			_, originals := archiveOf(t, original)
			if !bytes.Equal(copied["OEBPS/ch1.xhtml"], originals["OEBPS/ch1.xhtml"]) || !bytes.Equal(copied["OEBPS/s.css"], originals["OEBPS/s.css"]) {
				t.Fatalf("a document changed:\n%s", copied["OEBPS/ch1.xhtml"])
			}
		})
	}
}

// Babel (Harper Voyager, 9780063021440): the epigraph that Kindle sets on the right.
func TestBabelsEpigraphAttributionIsKeptOnTheRight(t *testing.T) {
	const sheet = `body
{
margin-left:1em;
margin-right:1em;
}
div.titlepage
{
text-align: center;
}
.also_para {
text-indent:0em;
text-align:center;
}
.epi_pb {
margin-left:1em;
text-align:justify;
}
.epi_at {
font-size:0.9em;
margin-top:1em;
text-indent:0em;
text-align:right;
}
.in_para {
text-indent:1.5em;
text-align:justify;
}
span[epub|type~='pagebreak'] {
display:none;
}
`
	const chapter = `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="en" xml:lang="en">
<head>
<title>Babel</title>
<link href="../styles/template.css" rel="stylesheet" type="text/css"/>
</head>
<body epub:type="bodymatter">
<section>
<h2 class="chap_head" id="ch1">Chapter One</h2>
<p class="epi_pb"><i>Language was always the companion of empire.</i></p>
<p class="epi_at">A<span class="make_smallcaps">NTONIO DE</span> N<span class="make_smallcaps">EBRIJA</span>, <i>Gramática de la lengua castellana</i></p>
<p class="in_para">The air was rank, the floors slippery.</p>
<p class="also_para">* * *</p>
</section>
</body>
</html>`
	path := makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", bookPackage("",
			`<item id="ch1" href="text/Chapter_1.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="css" href="styles/template.css" media-type="text/css"/>`), false},
		{"OEBPS/styles/template.css", sheet, false},
		{"OEBPS/text/Chapter_1.xhtml", chapter, false},
	})
	data, report := copyOf(t, path, CopyOptions{Restyle: true})
	_, copied := archiveOf(t, data)
	got := string(copied["OEBPS/text/Chapter_1.xhtml"])
	for _, want := range []string{
		`<p style="text-align: right !important" class="epi_at">A<span class="make_smallcaps">NTONIO DE</span>`,
		`<p style="text-align: center !important" class="also_para">* * *</p>`,
		`<p class="epi_pb"><i>`, `<p class="in_para">`, `<h2 class="chap_head" id="ch1">`, `<body epub:type="bodymatter">`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("the chapter lacks %s:\n%s", want, got)
		}
	}
	if report.Aligned != 2 {
		t.Errorf("aligned = %d", report.Aligned)
	}
	if !wellFormed(copied["OEBPS/text/Chapter_1.xhtml"]) {
		t.Errorf("not well-formed:\n%s", got)
	}
}
