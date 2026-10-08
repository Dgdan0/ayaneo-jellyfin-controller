package reading

import (
	"bytes"
	"fmt"
	"math/rand"
	"strings"
	"testing"
)

// Stylesheets and documents made at random from the pieces that make the rewrite
// hard: the properties it must not touch beside the one it must, strings and
// comments and urls that hold the words, entities, and every way a tag can be
// opened and closed. Nothing is asserted of the output but what must hold of all.

var cssPieces = []string{
	"font-size", "FONT-SIZE", "font", "-webkit-font-size", "line-height", "margin", "--size",
	":", ": ", " : ", ";", "; ", "{", "}", "{ ", " }", "(", ")", ",", "!important", " ! important", "!", "/", "*", "+",
	"medium", "small", "large", "xx-large", "smaller", "larger", "inherit", "12px", "9pt", "1.5em", "100%", "0", "calc(1em + 2px)", ".5px", "10",
	"p", ".a", "#b", "a:hover", "@media screen", "@media (min-width: 30em)", "@supports (font-size: medium)", "@import url(x.css);",
	"/* font-size: medium */", "/*", "*/", `"font-size: medium;"`, `'a;b'`, `"`, "'", "url(a;font-size:small.png)", `url("b")`, "\\", "\\;",
	" ", "\n", "\t", "<![CDATA[", "]]>", "<!--", "-->", "&amp;", "&#59;", "é",
}

func randomCSS(random *rand.Rand) string {
	pick := func(list []string) string { return list[random.Intn(len(list))] }
	var out strings.Builder
	for i, n := 0, 1+random.Intn(30); i < n; i++ {
		switch random.Intn(10) {
		case 0, 1, 2:
			out.WriteString(pick(cssPieces))
		case 3, 4, 5, 6:
			// A declaration, nearly well made.
			out.WriteString(pick([]string{"font-size", "FONT-SIZE", "font-size", "line-height", "LINE-HEIGHT", "line-height", "--size", "-webkit-font-size", "-webkit-line-height"}))
			out.WriteString(pick([]string{":", ": ", " : ", ":", "/**/:/**/"}))
			out.WriteString(pick([]string{"medium", "small", "large", "xx-large", "smaller", "inherit", "12px", "9pt", "1.5em", "100%", "0", "calc(1em + 2px)", ".5px", "10", "MEDIUM", "12PX", "url(a;b)", `"x"`, "18px", "14pt", "normal", "1.4", "70PX"}))
			out.WriteString(pick([]string{"", "", "!important", " !important", " ! IMPORTANT", " /* c */", "&#59;"}))
			out.WriteString(pick([]string{";", "; ", "}", ";}", "", "\n"}))
		case 7, 8:
			out.WriteString(pick([]string{"p", ".a", "a:hover", "@media screen", "@page", "h1, h2", "[a=\"{\"]"}))
			out.WriteString(pick([]string{"{", " {", "{\n"}))
		default:
			out.WriteString(pick([]string{"}", " }", "}}"}))
		}
	}
	return out.String()
}

func TestRewriteSizesSurvivesAnythingAndIsStable(t *testing.T) {
	random := rand.New(rand.NewSource(20261008))
	fonts, lines := 0, 0
	for i := 0; i < 20000; i++ {
		css := randomCSS(random)
		lower := strings.ToLower(css)
		for _, declarations := range []bool{false, true} {
			once, count := rewriteSizes([]byte(css), declarations)
			if count.total() == 0 && string(once) != css {
				t.Fatalf("%q: nothing counted but the text changed to %q", css, once)
			}
			if count.fontSizes > 0 {
				fonts++
			}
			if count.lineHeights > 0 {
				lines++
			}
			twice, again := rewriteSizes(once, declarations)
			if again.total() != 0 || !bytes.Equal(twice, once) {
				t.Fatalf("declarations=%v %q -> %q -> %q (%+v)", declarations, css, once, twice, again)
			}
			// Only a size can have changed: take the sizes' own text out of both and
			// what is left is the same.
			if count.total() > 0 && stripSizes(string(once)) != stripSizes(css) {
				t.Fatalf("more than a size changed: %q -> %q", css, once)
			}
			if !strings.Contains(lower, "font-size") && count.fontSizes != 0 {
				t.Fatalf("%q has no font-size and was changed", css)
			}
			if !strings.Contains(lower, "line-height") && count.lineHeights != 0 {
				t.Fatalf("%q has no line-height and was changed", css)
			}
		}
	}
	if fonts < 500 || lines < 500 {
		t.Fatalf("%d sheets had a font size to rewrite and %d a line height of the 40000 tried: the pieces do not exercise the rewrite", fonts, lines)
	}
}

// stripSizes removes every number-and-unit and size keyword, so that two texts that
// differ only in a size compare equal.
func stripSizes(s string) string {
	replacer := strings.NewReplacer("xxx-large", "", "xx-large", "", "x-large", "", "xx-small", "", "x-small", "", "medium", "", "small", "", "large", "",
		"px", "", "pt", "", "rem", "")
	s = replacer.Replace(strings.ToLower(s))
	var out strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' || r == '.' || r == '+' {
			continue
		}
		out.WriteRune(r)
	}
	return out.String()
}

func randomXHTML(random *rand.Rand) string {
	var out strings.Builder
	var element func(depth int, name string)
	text := func() {
		out.WriteString([]string{"words", " &amp; ", "&nbsp;", "é", "\n", "a &lt; b", " ", "font-size: medium"}[random.Intn(8)])
	}
	style := func() string {
		return []string{"font-size:medium", "color:red; font-size: 12pt", "FONT-SIZE:LARGE!important", "line-height:12px", "line-height:1.2", "line-height: 18pt !important", "font-size:1.2em", "font-family:&quot;A&quot;;font-size:small", ""}[random.Intn(9)]
	}
	element = func(depth int, name string) {
		fmt.Fprintf(&out, "<%s", name)
		for i, n := 0, random.Intn(3); i < n; i++ {
			quote := []string{`"`, `'`}[random.Intn(2)]
			attribute := []string{"style", "STYLE", "class", "id", "data-style", "title"}[random.Intn(6)]
			value := style()
			if quote == "'" {
				value = strings.ReplaceAll(value, "&quot;", "")
			}
			fmt.Fprintf(&out, " %s=%s%s%s", attribute, quote, value, quote)
		}
		if random.Intn(5) == 0 {
			out.WriteString("/>")
			return
		}
		out.WriteString(">")
		for i, n := 0, random.Intn(4); i < n && depth < 4; i++ {
			switch random.Intn(9) {
			case 0:
				text()
			case 1:
				out.WriteString("<!-- <p style=\"font-size:medium\"> </head> -->")
			case 2:
				out.WriteString("<![CDATA[ <p style=\"font-size:small\"> </head> ]]>")
			case 3:
				out.WriteString("<?pi font-size:medium?>")
			case 4:
				fmt.Fprintf(&out, "<style type=\"text/css\">%s</style>", []string{"p { font-size: medium }", "/*<![CDATA[*/ a { font-size: 9pt } /*]]>*/", "<![CDATA[ h1 { font-size: 20px } ]]>", "span.d { font-size: 80px; line-height: 70px }", ""}[random.Intn(5)])
			case 5:
				out.WriteString("<svg xmlns=\"http://www.w3.org/2000/svg\"><text style=\"font-size:12px\">t</text></svg>")
			case 6:
				out.WriteString("<script>/*<![CDATA[*/ var s = '<p style=\"font-size:medium\">'; /*]]>*/</script>")
			default:
				element(depth+1, []string{"div", "p", "span", "section", "em"}[random.Intn(5)])
			}
		}
		fmt.Fprintf(&out, "</%s>", name)
	}
	if random.Intn(2) == 0 {
		out.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
	}
	if random.Intn(2) == 0 {
		out.WriteString("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n")
	}
	root := []string{"", ` lang="fr"`, ` xml:lang="he"`, ` lang="fr" xml:lang="fr"`, ""}[random.Intn(5)]
	out.WriteString("<html" + root + " xmlns=\"http://www.w3.org/1999/xhtml\">")
	if random.Intn(6) > 0 {
		out.WriteString("<head><title>t</title>")
		if random.Intn(2) == 0 {
			out.WriteString("<style>body { font-size: small }</style>")
		}
		out.WriteString("</head>")
	}
	out.WriteString("<body>")
	element(0, "div")
	out.WriteString("</body></html>")
	return out.String()
}

// The rewrite never turns a well-formed document into one that is not, whatever is
// in it, and it is stable: a second pass finds nothing.
func TestRestyleDocumentKeepsAWellFormedDocumentWellFormed(t *testing.T) {
	random := rand.New(rand.NewSource(8102026))
	styled, resized, lined, languaged := 0, 0, 0, 0
	for i := 0; i < 5000; i++ {
		doc := randomXHTML(random)
		if !wellFormed([]byte(doc)) {
			t.Fatalf("the generator made a document that is not well-formed:\n%s", doc)
		}
		out, result := restyleDocument([]byte(doc), documentContext{language: "en-US"})
		if !wellFormed(out) {
			t.Fatalf("a well-formed document is not any more:\n%s\n%s", doc, out)
		}
		// The language goes on a root that has none of its own, and only there.
		hadOwn := strings.Contains(doc, " lang=") || strings.Contains(doc, "xml:lang=")
		if result.language == hadOwn || (result.language && strings.Count(string(out), `lang="en-US" xml:lang="en-US"`) != 1) {
			t.Fatalf("language = %v for a document that had its own: %v\n%s", result.language, hadOwn, out)
		}
		if result.language {
			languaged++
		}
		if result.styled {
			styled++
		}
		if result.lineHeights > 0 {
			lined++
		}
		if result.fontSizes > 0 {
			resized++
		}
		again, second := restyleDocument(out, documentContext{language: "en-US"})
		if second.fontSizes != 0 || second.lineHeights != 0 || second.styled || second.language || !bytes.Equal(again, out) {
			t.Fatalf("a second pass changed the document:\n%s\n%s", out, again)
		}
		if result.styled != (strings.Count(string(out), columnStyleElement) == 1) || strings.Count(string(out), columnStyleElement) > 1 {
			t.Fatalf("%d column styles in\n%s", strings.Count(string(out), columnStyleElement), out)
		}
	}
	if styled < 3000 || resized < 2500 || lined < 400 || languaged < 1500 {
		t.Fatalf("%d documents were styled, %d resized and %d given line heights of 5000: the generator does not exercise the rewrite", styled, resized, lined)
	}
}
