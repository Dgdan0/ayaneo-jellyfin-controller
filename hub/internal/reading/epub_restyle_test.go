package reading

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// wellFormed: the document parses as XML to its end, with the HTML entities that
// XHTML allows (&nbsp;) and whatever encoding it declares read as bytes.
func wellFormed(doc []byte) bool {
	decoder := xml.NewDecoder(bytes.NewReader(doc))
	decoder.Strict = true
	decoder.Entity = xml.HTMLEntity
	decoder.CharsetReader = func(_ string, input io.Reader) (io.Reader, error) { return input, nil }
	for {
		if _, err := decoder.Token(); err != nil {
			return err == io.EOF
		}
	}
}

const gameOfThronesCSS = `@charset "utf-8";
body { font-size: small; line-height: 1.3em; margin: 0 }
p.calibre1 { font-size: medium; text-indent: 1em }
p.big { font-size: 24px !important; }
.note { font-size: 10pt; color: #333 }
h1 { font-size: 1.5em }
sup { font-size: smaller }
span.dropcaps { font-size: 80px; line-height: 70px; float: left }
p.tight { line-height: 18px; font: 12px/18px serif }
/* font-size: large; */
`

const chapterXHTML = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title><link rel="stylesheet" href="../Styles/book.css" type="text/css"/><style type="text/css">.drop { font-size: large; line-height: 20pt }</style></head><body><h1 id="top">One</h1><p class="calibre1" id="p1" style="font-size: 14px; color: red">Hello&nbsp;world <span style='font-size:small'>and</span> <span style="line-height:12px">more</span></p></body></html>`

// bookFile is one entry of a hand-made book.
type bookFile struct {
	name  string
	data  string
	store bool
}

func bookPackage(extraMeta string, items ...string) string {
	return `<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">urn:x</dc:identifier><dc:title>T</dc:title><dc:language>en</dc:language>` + extraMeta + `</metadata><manifest>` + strings.Join(items, "") + `</manifest><spine><itemref idref="ch1"/></spine></package>`
}

const bookContainer = `<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>`

// makeBook writes an EPUB of the given entries after its mimetype.
func makeBook(t *testing.T, files []bookFile) string {
	t.Helper()
	specs := make([]zipFileSpec, len(files))
	for i, file := range files {
		specs[i] = zipFileSpec{name: file.name, data: []byte(file.data), store: file.store}
	}
	data, err := writeZip("application/epub+zip", specs)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "book.epub")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func standardBook(t *testing.T) string {
	return makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", bookPackage("",
			`<item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="css" href="Styles/book.css" media-type="text/css"/>`), false},
		{"OEBPS/Styles/book.css", gameOfThronesCSS, false},
		{"OEBPS/Text/ch1.xhtml", chapterXHTML, false},
		{"OEBPS/images/cover.jpg", "not really a jpeg but not text either", true},
	})
}

func TestReadingCopyRewritesTheStylesAndLeavesTheRestOfTheBookAlone(t *testing.T) {
	path := standardBook(t)
	data, report := copyOf(t, path, CopyOptions{Restyle: true})
	files, copied := archiveOf(t, data)
	originalBytes, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	originalFiles, originals := archiveOf(t, originalBytes)

	// The same entries in the same order; the mimetype first and stored.
	if strings.Join(namesOf(files), "|") != strings.Join(namesOf(originalFiles), "|") {
		t.Fatalf("entries %v, want %v", namesOf(files), namesOf(originalFiles))
	}
	if files[0].Name != "mimetype" || files[0].Method != zip.Store {
		t.Fatalf("the first entry is %q (method %d)", files[0].Name, files[0].Method)
	}
	edited := map[string]bool{"OEBPS/Styles/book.css": true, "OEBPS/Text/ch1.xhtml": true}
	for i, file := range files {
		original := originalFiles[i]
		if edited[file.Name] {
			if file.Method != zip.Deflate || bytes.Equal(copied[file.Name], originals[file.Name]) {
				t.Errorf("%s: method %d, unchanged: %v", file.Name, file.Method, bytes.Equal(copied[file.Name], originals[file.Name]))
			}
			continue
		}
		// What was not rewritten is the very entry: its method, checksum and bytes.
		if file.Method != original.Method || file.CRC32 != original.CRC32 || file.CompressedSize64 != original.CompressedSize64 ||
			!bytes.Equal(copied[file.Name], originals[file.Name]) {
			t.Errorf("%s changed", file.Name)
		}
	}

	wantCSS := strings.NewReplacer(
		"body { font-size: small;", "body { font-size: .8125rem;",
		"p.calibre1 { font-size: medium;", "p.calibre1 { font-size: 1rem;",
		"font-size: 24px !important;", "font-size: 1.5rem !important;",
		".note { font-size: 10pt;", ".note { font-size: .8333rem;",
		"span.dropcaps { font-size: 80px; line-height: 70px;", "span.dropcaps { font-size: 5rem; line-height: 4.375rem;",
		"p.tight { line-height: 18px;", "p.tight { line-height: 1.125rem;",
	).Replace(gameOfThronesCSS)
	if got := string(copied["OEBPS/Styles/book.css"]); got != wantCSS {
		t.Fatalf("stylesheet\n%s\nwant\n%s", got, wantCSS)
	}

	chapter := string(copied["OEBPS/Text/ch1.xhtml"])
	for _, want := range []string{
		`.drop { font-size: 1.125rem; line-height: 1.6667rem }`,
		`style="font-size: .875rem; color: red"`,
		`style='font-size:.8125rem'`,
		`style="line-height:.75rem"`,
		`Hello&nbsp;world`,
		`<h1 id="top">One</h1>`, `<p class="calibre1" id="p1"`,
		columnStyleElement + `</head>`,
	} {
		if !strings.Contains(chapter, want) {
			t.Errorf("the chapter lacks %s:\n%s", want, chapter)
		}
	}
	if strings.Count(chapter, columnStyleElement) != 1 {
		t.Errorf("%d column styles", strings.Count(chapter, columnStyleElement))
	}
	// Font sizes: 1 in the block, 2 inline, 5 in the sheet. Line heights: 1 in the block, 1 inline, 2 in the sheet;
	// the font: shorthand with a line height in it is as it was.
	if report.FontSizes != 8 || report.LineHeights != 4 || report.Styled != 1 || report.Edited != 2 || report.FixedLayout || len(report.Left) != 0 {
		t.Errorf("report = %+v", report)
	}
	if !wellFormed([]byte(chapterXHTML)) || !wellFormed([]byte(chapter)) {
		t.Errorf("the chapter is not well-formed (before: %v)", wellFormed([]byte(chapterXHTML)))
	}
}

// A book made by this pass is left as it is by the next.
func TestReadingCopyOfAReadingCopyChangesNothing(t *testing.T) {
	path := standardBook(t)
	first, _ := copyOf(t, path, CopyOptions{Restyle: true})
	again := filepath.Join(t.TempDir(), "again.epub")
	if err := os.WriteFile(again, first, 0o644); err != nil {
		t.Fatal(err)
	}
	second, report := copyOf(t, again, CopyOptions{Restyle: true})
	if report.FontSizes != 0 || report.Styled != 0 || report.Edited != 0 {
		t.Fatalf("a second pass: %+v", report)
	}
	if !bytes.Equal(first, second) {
		t.Fatal("a second pass changed the bytes")
	}
}

func TestInjectedColumnStyleIsSafeInsideXHTML(t *testing.T) {
	if strings.ContainsAny(columnStyle, "<&") {
		t.Fatalf("the column style holds a character XML cannot take as text: %s", columnStyle)
	}
	doc := `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>x</title></head><body><p>x</p></body></html>`
	out, result := restyleDocument([]byte(doc))
	if !result.styled || !wellFormed(out) {
		t.Fatalf("styled %v, well-formed %v:\n%s", result.styled, wellFormed(out), out)
	}
	decoder := xml.NewDecoder(bytes.NewReader(out))
	found := ""
	for {
		token, err := decoder.Token()
		if err != nil {
			break
		}
		if start, ok := token.(xml.StartElement); ok && start.Name.Local == "style" {
			var text struct {
				Text string `xml:",chardata"`
			}
			if err := decoder.DecodeElement(&text, &start); err != nil {
				t.Fatal(err)
			}
			found = text.Text
		}
	}
	if found != columnStyle {
		t.Fatalf("the parser reads the style as %q", found)
	}
}

func TestRestyleDocumentPutsOneStyleBeforeTheFirstHeadEndOnly(t *testing.T) {
	for name, test := range map[string]struct {
		doc    string
		styled bool
		// at is what the style goes directly in front of.
		at string
	}{
		"an ordinary head":          {`<html><head><title>x</title></head><body/></html>`, true, `</head>`},
		"an uppercase head":         {`<HTML><HEAD><TITLE>x</TITLE></HEAD><BODY></BODY></HTML>`, true, `</HEAD>`},
		"a mixed-case end tag":      {`<html><head></Head><body/></html>`, true, `</Head>`},
		"spaces in the end tag":     {`<html><head><title>x</title></head ><body/></html>`, true, `</head >`},
		"two heads":                 {`<html><head><title>x</title></head><body><svg><head/></svg><head></head></body></html>`, true, `</head><body>`},
		"a head in a comment first": {`<html><!-- </head> --><head></head><body/></html>`, true, `</head><body/>`},
		"a head in CDATA first":     {`<html><head><script><![CDATA[ var s = "</head>"; ]]></script></head><body/></html>`, true, `</head><body/>`},
		"a head in a script":        {`<html><head><script>document.write("</head>")</script></head><body/></html>`, true, `</head><body/>`},
		"a head in a style":         {`<html><head><style>/* </head> */ p {}</style></head><body/></html>`, true, `</head><body/>`},
		"a head in an attribute":    {`<html><head><meta content="</head>"/></head><body/></html>`, true, `</head><body/>`},
		"no head":                   {`<html><body><p>x</p></body></html>`, false, ``},
		"an empty head element":     {`<html><head/><body><p>x</p></body></html>`, false, ``},
		"an unfinished comment":     {`<html><!-- <head></head> <body/></html>`, false, ``},
		"no markup":                 {`just text`, false, ``},
		"empty":                     {``, false, ``},
		"namespace prefixes":        {`<h:html xmlns:h="http://www.w3.org/1999/xhtml"><h:head></h:head><h:body/></h:html>`, false, ``},
	} {
		t.Run(name, func(t *testing.T) {
			out, result := restyleDocument([]byte(test.doc))
			if result.styled != test.styled || result.left != "" {
				t.Fatalf("styled = %v (want %v), left %q", result.styled, test.styled, result.left)
			}
			if !test.styled {
				if string(out) != test.doc {
					t.Fatalf("a document without a head was changed:\n%s", out)
				}
				return
			}
			// The style sits immediately before the first end tag, and there is one.
			if !strings.Contains(string(out), columnStyleElement+test.at) || strings.Count(string(out), columnStyleElement) != 1 {
				t.Fatalf("the style is not once, directly before %q:\n%s", test.at, out)
			}
			// Nothing else of the document is lost.
			if strings.Replace(string(out), columnStyleElement, "", 1) != test.doc {
				t.Fatalf("the document lost something:\n%s", out)
			}
			// And once it has it, it does not get a second.
			again, second := restyleDocument(out)
			if second.styled || !bytes.Equal(again, out) {
				t.Fatalf("a second pass changed the document:\n%s", again)
			}
		})
	}
}

func TestRestyleDocumentRewritesInlineStylesAndStyleBlocksAndOnlyThose(t *testing.T) {
	doc := `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>font-size: medium</title>` +
		`<style>p { font-size: medium }</style>` +
		`<style type="text/css" media="print">/*<![CDATA[*/ h1 { font-size: 20pt } /*]]>*/</style>` +
		`<style type="text/css"><![CDATA[ h2 { font-size: x-large } ]]></style>` +
		`<style type="text/less">p { font-size: medium }</style>` +
		`<style type="text/css">` + "\n" + `<!--` + "\n" + `.a { font-size: 12px }` + "\n" + `-->` + "\n" + `</style>` +
		`<script>/*<![CDATA[*/ var s = "<p style='font-size:medium'>"; var t = '</head>'; /*]]>*/</script>` +
		`</head><body>` +
		`<p STYLE="FONT-SIZE: LARGE">a</p><p style='font-size:12pt;color:red'>b</p><p data-style="font-size:medium" title="font-size:large">c</p>` +
		`<p style="">e</p><p style="font-size:1.2em">f</p>` +
		`<!-- <p style="font-size:medium"> --><![CDATA[ <p style="font-size:small"> ]]>` +
		`<svg xmlns="http://www.w3.org/2000/svg"><style>text { font-size: 12px }</style><text style="font-size:12px">svg</text></svg>` +
		`<math><mtext style="font-size:12px">m</mtext></math>` +
		`<p style="font-size:small">after the svg</p>` +
		`</body></html>`
	out, result := restyleDocument([]byte(doc))
	want := strings.NewReplacer(
		`<style>p { font-size: medium }</style>`, `<style>p { font-size: 1rem }</style>`,
		`h1 { font-size: 20pt }`, `h1 { font-size: 1.6667rem }`,
		`h2 { font-size: x-large }`, `h2 { font-size: 1.5rem }`,
		`.a { font-size: 12px }`, `.a { font-size: .75rem }`,
		`STYLE="FONT-SIZE: LARGE"`, `STYLE="FONT-SIZE: 1.125rem"`,
		`style='font-size:12pt;color:red'`, `style='font-size:1rem;color:red'`,
		`<p style="font-size:small">after the svg</p>`, `<p style="font-size:.8125rem">after the svg</p>`,
	).Replace(doc)
	want = strings.Replace(want, `</head><body>`, columnStyleElement+`</head><body>`, 1)
	if string(out) != want {
		t.Fatalf("\n got %s\nwant %s", out, want)
	}
	// 4 style blocks and 3 attributes; the text/less one, the SVG and the MathML
	// are not touched.
	if result.fontSizes != 7 || !result.styled {
		t.Fatalf("result = %+v", result)
	}
	if !wellFormed([]byte(doc)) || !wellFormed(out) {
		t.Fatalf("well-formed before %v after %v", wellFormed([]byte(doc)), wellFormed(out))
	}
}

// Line heights follow the font sizes they are set for, in every place a font size is
// read: <style> blocks, style attributes and (in epub_css_test.go) stylesheets. A bare
// number, ems, percent, normal and the font: shorthand stay.
func TestRestyleDocumentMakesAbsoluteLineHeightsRelativeToo(t *testing.T) {
	doc := `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>line-height: 18px</title>` +
		`<style type="text/css">span.dropcaps { font-size: 80px; line-height: 70px; float: left } p { line-height: 1.4 } h1 { line-height: 150% } h2 { line-height: normal; font: 12px/18px serif }</style>` +
		`</head><body>` +
		`<p style="line-height:18px">a</p><p style='color:red; LINE-HEIGHT: 14pt !important'>b</p>` +
		`<p style="line-height:1.5em">c</p><p style="line-height:normal;font:12px/18px serif">d</p>` +
		`<p data-style="line-height:18px" title="line-height:18px">e</p>` +
		`<svg xmlns="http://www.w3.org/2000/svg"><text style="line-height:18px">svg</text></svg>` +
		`</body></html>`
	out, result := restyleDocument([]byte(doc))
	want := strings.NewReplacer(
		`font-size: 80px; line-height: 70px;`, `font-size: 5rem; line-height: 4.375rem;`,
		`<p style="line-height:18px">a</p>`, `<p style="line-height:1.125rem">a</p>`,
		`LINE-HEIGHT: 14pt !important`, `LINE-HEIGHT: 1.1667rem !important`,
		`</head>`, columnStyleElement+`</head>`,
	).Replace(doc)
	if string(out) != want || result.fontSizes != 1 || result.lineHeights != 3 || !result.styled {
		t.Fatalf("\n got %s (%+v)\nwant %s", out, result, want)
	}
	if !wellFormed([]byte(doc)) || !wellFormed(out) {
		t.Fatalf("well-formed before %v after %v", wellFormed([]byte(doc)), wellFormed(out))
	}
	// A stylesheet reports its line heights as well.
	sheet, sheetResult := restyleSheet([]byte(`span.dropcaps { font-size: 80px; line-height: 70px }`))
	if string(sheet) != `span.dropcaps { font-size: 5rem; line-height: 4.375rem }` || sheetResult.fontSizes != 1 || sheetResult.lineHeights != 1 {
		t.Fatalf("sheet = %q (%+v)", sheet, sheetResult)
	}
}

// HTML that is not XML: what the scan cannot follow it leaves, and keeps what it
// had already found.
func TestRestyleDocumentFollowsHTMLThatIsNotXMLAsFarAsItCan(t *testing.T) {
	doc := `<html><head><style>p { font-size: medium }</style></head><body>` +
		`<p style=font-size:small>unquoted</p><br><p style="font-size:large">a < b and c<d</p>` +
		`<img src=x.png style='font-size:12px'><p style="font-size:small`
	out, result := restyleDocument([]byte(doc))
	want := strings.NewReplacer(
		`p { font-size: medium }`, `p { font-size: 1rem }`,
		`style="font-size:large"`, `style="font-size:1.125rem"`,
		`style='font-size:12px'`, `style='font-size:.75rem'`,
		`</head>`, columnStyleElement+`</head>`,
	).Replace(doc)
	if string(out) != want || result.fontSizes != 3 || !result.styled {
		t.Fatalf("\n got %s (%+v)\nwant %s", out, result, want)
	}
}

// A document the pass cannot edit byte for byte is left exactly as it was, and the
// report says so.
func TestRestyleDocumentLeavesAnEncodingItCannotEditAlone(t *testing.T) {
	utf16 := func(s string) string {
		var out []byte
		out = append(out, 0xFF, 0xFE)
		for _, r := range s {
			out = append(out, byte(r), byte(r>>8))
		}
		return string(out)
	}
	body := `<head><style>p { font-size: medium }</style></head><body><p style="font-size:small">é</p></body></html>`
	for name, doc := range map[string]string{
		"latin-1 declared in the XML declaration":                     `<?xml version="1.0" encoding="ISO-8859-1"?><html>` + body,
		"shift_jis declared":                                          `<?xml version='1.0' encoding='Shift_JIS'?><html>` + body,
		"a declaration of another encoding outweighs a meta in UTF-8": `<?xml version="1.0" encoding="windows-1252"?><html><head><meta charset="utf-8"/><style>p { font-size: medium }</style></head><body/></html>`,
		"a charset in a meta element":                                 `<html><head><meta http-equiv="Content-Type" content="text/html; charset=windows-1252"/><style>p { font-size: medium }</style></head><body/></html>`,
		"a meta charset attribute":                                    `<html><head><meta charset="iso-8859-1"><style>p { font-size: medium }</style></head><body/></html>`,
		"UTF-16 with a byte-order mark":                               utf16(`<html>` + body),
		"UTF-16 without one":                                          "<\x00h\x00t\x00m\x00l\x00>\x00",
		"bytes that are not UTF-8":                                    "<html><head><style>p { font-size: medium }</style></head><body><p>caf\xe9</p></body></html>",
	} {
		t.Run(name, func(t *testing.T) {
			out, result := restyleDocument([]byte(doc))
			if result.left != leftEncoding || string(out) != doc || result.fontSizes != 0 || result.styled {
				t.Fatalf("result = %+v; changed: %v", result, string(out) != doc)
			}
		})
	}
	// What is declared as UTF-8, in any of the usual spellings, is edited, with a
	// byte-order mark or without one.
	for name, doc := range map[string]string{
		"utf-8":              `<?xml version="1.0" encoding="UTF-8"?><html>` + body,
		"utf8 in lower case": `<?xml version="1.0" encoding="utf8"?><html>` + body,
		"us-ascii":           `<?xml version="1.0" encoding="us-ascii"?><html>` + body,
		"a meta in UTF-8":    `<html><head><meta http-equiv="Content-Type" content="application/xhtml+xml; charset=utf-8"/><style>p { font-size: medium }</style></head><body/></html>`,
		"a UTF-8 declaration outweighs a stale meta": `<?xml version="1.0" encoding="utf-8"?><html><head><meta http-equiv="CONTENT-TYPE" content="text/html; charset=windows-1252"/><meta content="http://www.w3.org/1999/xhtml; charset=utf-8" http-equiv="Content-Type"/><style>p { font-size: medium }</style></head><body/></html>`,
		"a byte-order mark":                          "\xEF\xBB\xBF" + `<?xml version="1.0" encoding="UTF-8"?><html>` + body,
		"no declaration":                             `<html>` + body,
	} {
		t.Run(name, func(t *testing.T) {
			out, result := restyleDocument([]byte(doc))
			if result.left != "" || result.fontSizes == 0 || !result.styled {
				t.Fatalf("result = %+v", result)
			}
			if strings.HasPrefix(doc, "\xEF\xBB\xBF") && !strings.HasPrefix(string(out), "\xEF\xBB\xBF<?xml") {
				t.Fatal("the byte-order mark was lost")
			}
			if !strings.Contains(string(out), "é") && strings.Contains(doc, "é") {
				t.Fatal("a character was lost")
			}
		})
	}
}

func TestRestyleSheetLeavesAnEncodingItCannotEditAlone(t *testing.T) {
	for name, sheet := range map[string]string{
		"windows-1252":    `@charset "windows-1252"; p { font-size: medium }`,
		"UTF-16":          "\xFF\xFEp\x00{\x00",
		"not UTF-8 bytes": "p { font-size: medium; content: \"\xe9\" }",
		"a NUL up front":  "p\x00 { font-size: medium }",
	} {
		out, result := restyleSheet([]byte(sheet))
		if result.left != leftEncoding || string(out) != sheet {
			t.Errorf("%s: %+v", name, result)
		}
	}
	for name, sheet := range map[string]string{
		"utf-8 declared":    `@charset "UTF-8"; p { font-size: medium }`,
		"a byte-order mark": "\xEF\xBB\xBFp { font-size: medium }",
		"no declaration":    `p { font-size: medium }`,
		"UTF-8 text in it":  "p { font-size: medium; content: \"é\" }",
	} {
		out, result := restyleSheet([]byte(sheet))
		if result.left != "" || result.fontSizes != 1 || !strings.Contains(string(out), "1rem") {
			t.Errorf("%s: %+v %q", name, result, out)
		}
	}
}

// The manifest says what a file is; the extension only when it says nothing.
func TestReadingCopyTakesWhatIsAStylesheetOrADocumentFromThePackage(t *testing.T) {
	sheet := `p { font-size: medium }`
	doc := `<html><head></head><body><p style="font-size:small">x</p></body></html>`
	path := makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", bookPackage("",
			`<item id="ch1" href="Text/ch%201.dat" media-type="application/xhtml+xml"/>`,
			`<item id="css" href="Styles/style.dat" media-type="text/css; charset=utf-8"/>`,
			`<item id="img" href="Styles/looks-like-css.css" media-type="image/svg+xml"/>`,
			`<item id="notype" href="Text/no-type.xhtml"/>`), false},
		{"OEBPS/Text/ch 1.dat", doc, false},
		{"OEBPS/Styles/style.dat", sheet, false},
		{"OEBPS/Styles/looks-like-css.css", sheet, false},
		{"OEBPS/Text/no-type.xhtml", doc, false},
		{"OEBPS/Text/unlisted.xht", doc, false},
		{"OEBPS/Styles/unlisted.css", sheet, false},
		{"OEBPS/Text/page.html", doc, false},
		{"OEBPS/Text/page.htm", doc, false},
		{"OEBPS/notes.txt", sheet, false},
		{"OEBPS/image.svg", `<svg xmlns="http://www.w3.org/2000/svg"><style>text { font-size: 12px }</style></svg>`, false},
	})
	data, report := copyOf(t, path, CopyOptions{Restyle: true})
	_, copied := archiveOf(t, data)
	rewrittenSheet, rewrittenDoc := `p { font-size: 1rem }`, strings.Replace(strings.Replace(doc, "small", ".8125rem", 1), "</head>", columnStyleElement+"</head>", 1)
	for name, want := range map[string]string{
		"OEBPS/Text/ch 1.dat":             rewrittenDoc,
		"OEBPS/Styles/style.dat":          rewrittenSheet,
		"OEBPS/Styles/looks-like-css.css": sheet, // the package says it is an image
		"OEBPS/Text/no-type.xhtml":        rewrittenDoc,
		"OEBPS/Text/unlisted.xht":         rewrittenDoc,
		"OEBPS/Styles/unlisted.css":       rewrittenSheet,
		"OEBPS/Text/page.html":            rewrittenDoc,
		"OEBPS/Text/page.htm":             rewrittenDoc,
		"OEBPS/notes.txt":                 sheet,
		"OEBPS/image.svg":                 `<svg xmlns="http://www.w3.org/2000/svg"><style>text { font-size: 12px }</style></svg>`,
	} {
		if got := string(copied[name]); got != want {
			t.Errorf("%s:\n got %s\nwant %s", name, got, want)
		}
	}
	// Five documents and two stylesheets, a size in each.
	if report.Edited != 7 || report.Styled != 5 || report.FontSizes != 7 {
		t.Errorf("report = %+v", report)
	}

	// With no readable package the extension decides everything.
	noPackage := rewriteEPUB(t, path, func(files map[string][]byte, order *[]string) {
		delete(files, "OEBPS/content.opf")
		delete(files, "META-INF/container.xml")
	})
	data, _ = copyOf(t, noPackage, CopyOptions{Restyle: true})
	_, copied = archiveOf(t, data)
	if string(copied["OEBPS/Styles/looks-like-css.css"]) != rewrittenSheet || string(copied["OEBPS/Styles/style.dat"]) != sheet {
		t.Errorf("without a package: %q, %q", copied["OEBPS/Styles/looks-like-css.css"], copied["OEBPS/Styles/style.dat"])
	}
}

// A package with a DOCTYPE, an undeclared entity or an unknown charset is still a
// package.
func TestReadingCopyReadsAPackageThatAStrictParserWouldRefuse(t *testing.T) {
	opf := `<?xml version="1.0" encoding="ISO-8859-1"?><!DOCTYPE package [<!ENTITY x "y">]><package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">caf&eacute; &undeclared;</dc:title></metadata><manifest><item id="a" href="a.bin" media-type="text/css"/></manifest></package>`
	path := makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", opf, false},
		{"OEBPS/a.bin", "p { font-size: medium }", false},
	})
	data, _ := copyOf(t, path, CopyOptions{Restyle: true})
	if _, copied := archiveOf(t, data); string(copied["OEBPS/a.bin"]) != "p { font-size: 1rem }" {
		t.Fatalf("the manifest was not read: %q", copied["OEBPS/a.bin"])
	}
}

// A fixed-layout book is laid out by its publisher: the reader gives it neither a
// text size nor columns, and its pixel sizes are its page.
func TestReadingCopyLeavesAFixedLayoutBookAlone(t *testing.T) {
	path := makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", bookPackage(`<meta property="rendition:layout">pre-paginated</meta>`,
			`<item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="css" href="Styles/book.css" media-type="text/css"/>`), false},
		{"OEBPS/Styles/book.css", gameOfThronesCSS, false},
		{"OEBPS/Text/ch1.xhtml", chapterXHTML, false},
	})
	data, report := copyOf(t, path, CopyOptions{Restyle: true})
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	_, copied := archiveOf(t, data)
	_, originals := archiveOf(t, original)
	for name, content := range originals {
		if !bytes.Equal(copied[name], content) {
			t.Errorf("%s changed", name)
		}
	}
	if !report.FixedLayout || report.Edited != 0 || report.FontSizes != 0 || report.Styled != 0 {
		t.Fatalf("report = %+v", report)
	}
	// The audio is still left out of one that is read along.
	if _, report := copyOf(t, path, CopyOptions{Restyle: true, OmitAudio: true}); !report.FixedLayout {
		t.Fatal("omitting audio forgot the layout")
	}
}

func TestReadingCopyCopiesWhatItCannotSafelyEditAndSaysSo(t *testing.T) {
	latin := `<?xml version="1.0" encoding="ISO-8859-1"?><html><head><style>p { font-size: medium }</style></head><body/></html>`
	good := `<html><head></head><body><p style="font-size:small">x</p></body></html>`
	big := strings.Repeat("a", 3000) + " p { font-size: medium }"
	path := makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", bookPackage("",
			`<item id="a" href="latin.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="b" href="good.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="c" href="big.css" media-type="text/css"/>`,
			`<item id="d" href="broken.css" media-type="text/css"/>`), false},
		{"OEBPS/latin.xhtml", latin, false},
		{"OEBPS/good.xhtml", good, false},
		{"OEBPS/big.css", big, false},
		// Stored, so a changed byte is a bad checksum rather than a bad stream.
		{"OEBPS/broken.css", "p { font-size: medium; color: red; background: blue }", true},
	})
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	at := bytes.Index(raw, []byte("color: red"))
	raw[at] = 'X'
	if err := os.WriteFile(path, raw, 0o644); err != nil {
		t.Fatal(err)
	}

	previous := maxRestyleBytes
	t.Cleanup(func() { maxRestyleBytes = previous })
	maxRestyleBytes = 2000
	data, report := copyOf(t, path, CopyOptions{Restyle: true})
	got := map[string]string{}
	for _, left := range report.Left {
		got[left.Name] = left.Reason
	}
	want := map[string]string{"OEBPS/latin.xhtml": "encoding", "OEBPS/big.css": "too_large", "OEBPS/broken.css": "unreadable"}
	if len(got) != len(want) {
		t.Fatalf("left alone: %v, want %v", got, want)
	}
	for name, reason := range want {
		if got[name] != reason {
			t.Errorf("%s: %q, want %q", name, got[name], reason)
		}
	}
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range reader.File {
		switch entry.Name {
		case "OEBPS/latin.xhtml":
			if string(readAll(t, entry)) != latin {
				t.Error("an unsafe document was edited")
			}
		case "OEBPS/big.css":
			if string(readAll(t, entry)) != big {
				t.Error("a large stylesheet was edited")
			}
		case "OEBPS/good.xhtml":
			if !strings.Contains(string(readAll(t, entry)), ".8125rem") {
				t.Error("a good document was not edited")
			}
		case "OEBPS/broken.css":
			// Copied as it was, damage and all: the same entry fails to read.
			stream, err := entry.Open()
			if err != nil {
				t.Fatal(err)
			}
			if _, err := io.ReadAll(stream); err == nil {
				t.Error("a damaged entry was repaired")
			}
		}
	}
	if report.FontSizes != 1 || report.Styled != 1 || report.Edited != 1 {
		t.Errorf("report = %+v", report)
	}
}

// The slim read-along copy gets the same treatment in the same pass.
func TestTheSlimReadAlongCopyIsRestyledToo(t *testing.T) {
	fixture := generateAligned(t, threeFileNarration())
	path := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, order *[]string) {
		files["OEBPS/Styles/book.css"] = []byte("p { font-size: medium }")
		*order = append(*order, "OEBPS/Styles/book.css")
		replaceIn(files, "OEBPS/content.opf", "</manifest>", `<item id="css" href="Styles/book.css" media-type="text/css"/></manifest>`)
	})
	originalBytes, _ := os.ReadFile(path)
	_, originals := archiveOf(t, originalBytes)

	data, report := copyOf(t, path, CopyOptions{OmitAudio: true, Restyle: true})
	files, copied := archiveOf(t, data)
	for _, name := range fixture.Audio {
		if _, there := copied[name]; there {
			t.Errorf("%s is still there", name)
		}
	}
	// The three chapters and the navigation document have heads; the stylesheet one size.
	if len(report.Omitted) != len(fixture.Audio) || report.Styled != 4 || report.FontSizes != 1 || report.Edited != 5 {
		t.Fatalf("report = %+v", report)
	}
	if files[0].Name != "mimetype" || files[0].Method != zip.Store {
		t.Fatal("the archive does not open on a stored mimetype")
	}
	// Places are unchanged: the overlays, the package and every id.
	for name, content := range originals {
		if _, audio := AudioKindOf(name); audio {
			continue
		}
		switch {
		case strings.HasSuffix(name, ".smil"), name == "OEBPS/content.opf", name == "META-INF/container.xml":
			if !bytes.Equal(copied[name], content) {
				t.Errorf("%s changed", name)
			}
		case strings.HasPrefix(name, "OEBPS/text/"), name == "OEBPS/nav.xhtml":
			for _, fragment := range fixtureFragments(string(content)) {
				if !strings.Contains(string(copied[name]), `id="`+fragment+`"`) {
					t.Errorf("%s lost the id %s", name, fragment)
				}
			}
			if strings.Replace(string(copied[name]), columnStyleElement, "", 1) != string(content) {
				t.Errorf("%s changed more than its head", name)
			}
		}
	}
}

func fixtureFragments(doc string) []string {
	var ids []string
	for _, part := range strings.Split(doc, `id="`)[1:] {
		ids = append(ids, part[:strings.Index(part, `"`)])
	}
	return ids
}
