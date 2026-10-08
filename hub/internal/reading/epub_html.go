package reading

import (
	"bytes"
	"regexp"
	"sort"
	"strings"
	"unicode/utf8"
)

// The four changes made to each content document of a book's reading copy, and
// the cases in which a document is left exactly as it was.
//
// 1. Font sizes and line heights in `<style>` blocks and `style="…"` attributes become rem
//    (epub_css.go), as they do in the stylesheets.
// 2. One `<style>` is put before the first `</head>` that gives two columns on a
//    screen too narrow for Readium CSS to give them.
// 3. The language the package gives the book goes on the root `<html>` as lang and
//    xml:lang, when it has neither. WebKit and Chrome hyphenate only text whose
//    language they know, and many books (A Game of Thrones) name none in their
//    documents, only in the package.
// 4. A paragraph, list item or body that the book aligns right, centre or end gets
//    that alignment in its own style attribute, as `!important`, so that the
//    reader's text alignment does not replace it (epub_align.go).
//
// The document is never parsed into a tree and never re-serialised. A scan walks
// its markup far enough to know where the stylesheets are, where the root begins
// and where the head ends, and the changes are spliced into the original bytes at
// those places. So whatever the publisher wrote (entities, namespace prefixes, the
// order of attributes, the way a tag is closed) is what the reader gets, and a
// document that was well-formed is still well-formed: the only new characters are
// letters, digits, hyphens and CSS punctuation, the one new element sits whole
// inside the head, and the two new attributes are on a start tag that lacks them.

// columnStyle is the CSS of the second change. Readium CSS honours an explicit
// `--USER__colCount: 2` only inside `@media screen and (min-width: 60em), …`, so the
// reading area must be 960 CSS pixels wide: the Pocket's 853dp, every iPhone (956pt
// sideways, less with the safe areas) and an iPad mini upright (744pt) get one
// column however many the reader asks for. This rule asks for the same two columns
// from 30em (480px) up, which leaves an upright phone at one.
//
// It is written for the root's style attribute, which is where both navigators put
// the setting, in the two spellings the CSS serialiser may use.
//
// Scroll mode is not mentioned and not touched. Readium CSS ends every scroll-mode
// rule with `!important`, the column ones too (`:root[style*=readium-scroll-on]
// { columns: auto auto !important }`, in the ReadiumCSS-after.css of Readium
// Kotlin 3.0.0): the same specificity as the selectors here (a pseudo-class and
// an attribute), and in a sheet the navigator puts after the book's own styles. At
// equal specificity between important declarations the later one wins, so scroll
// stays one column wherever this rule would otherwise apply.
//
// The text must be safe inside an XHTML element: no `<` and no `&`.
const columnStyle = `@media screen and (min-width: 30em) { ` +
	`:root[style*="--USER__colCount: 2"], :root[style*="--USER__colCount:2"] { ` +
	`--RS__colWidth: auto !important; ` +
	`-webkit-column-count: 2 !important; column-count: 2 !important; ` +
	`-webkit-column-width: auto !important; column-width: auto !important; ` +
	`} }`

const columnStyleElement = `<style type="text/css">` + columnStyle + `</style>`

// documentResult is what was done to one document.
type documentResult struct {
	// fontSizes and lineHeights are the absolute declarations made relative.
	fontSizes, lineHeights int
	// styled: the two-column style was put in.
	styled bool
	// language: the package's language was put on the root element.
	language bool
	// aligned is how many elements were given the alignment their book gives them.
	aligned int
	// left is why the document was not touched at all, or empty.
	left string
}

const (
	leftEncoding = "encoding"
	leftTooLarge = "too_large"
	leftUnread   = "unreadable"
)

// documentContext is what the rest of the book tells the restyle of one document.
type documentContext struct {
	// language is the package's language, or empty: see validLanguage.
	language string
	// name is the document's entry name, from which its linked stylesheets are found.
	name string
	// styles is what the book's stylesheets say about alignment. Nil knows none, and
	// the document's own <style> elements and style attributes still count.
	styles *bookStyles
}

// restyleDocument returns doc with its font sizes and line heights as rem, the
// column style in its head, when ctx.language is not empty that language on its
// <html> if it names none, and the alignment its book gives its paragraphs, list
// items and body. It returns doc itself when there is nothing to change.
func restyleDocument(doc []byte, ctx documentContext) ([]byte, documentResult) {
	if !isUTF8Text(doc) || declaresOtherCharset(doc) {
		return doc, documentResult{left: leftEncoding}
	}
	found := scanMarkup(doc)
	type edit struct {
		from, to    int
		replacement []byte
	}
	var edits []edit
	result := documentResult{}
	aligned := planAlignment(doc, found, ctx.name, ctx.language, ctx.styles)
	result.aligned = aligned.count
	for _, span := range found.attributes {
		rewritten, count := rewriteSizes(doc[span.from:span.to], true)
		fix, fixed := aligned.styles[span.from]
		if fixed {
			// One edit for the attribute, whichever of the two changes it.
			rewritten = fix.applyToStyle(rewritten)
		}
		if count.total() > 0 || fixed {
			edits = append(edits, edit{span.from, span.to, rewritten})
			result.fontSizes += count.fontSizes
			result.lineHeights += count.lineHeights
		}
	}
	for _, insert := range aligned.inserts {
		edits = append(edits, edit{insert.at, insert.at, []byte(insert.text)})
	}
	for _, span := range found.blocks {
		if rewritten, count := rewriteSizes(doc[span.from:span.to], false); count.total() > 0 {
			edits = append(edits, edit{span.from, span.to, rewritten})
			result.fontSizes += count.fontSizes
			result.lineHeights += count.lineHeights
		}
	}
	if found.headEnd >= 0 && !bytes.Contains(doc, []byte(columnStyle)) {
		edits = append(edits, edit{found.headEnd, found.headEnd, []byte(columnStyleElement)})
		result.styled = true
	}
	// Only the root's start tag, only when it says nothing of language: a document
	// that has a lang or an xml:lang, even an empty one, is the publisher's own word.
	// The value was checked (validLanguage), so it holds nothing XML would refuse.
	if ctx.language != "" && found.root.nameEnd > 0 && !found.root.hasLanguage {
		attributes := ` lang="` + ctx.language + `" xml:lang="` + ctx.language + `"`
		edits = append(edits, edit{found.root.nameEnd, found.root.nameEnd, []byte(attributes)})
		result.language = true
	}
	if len(edits) == 0 {
		return doc, result
	}
	// The spans were found in the order of the document, attributes and blocks
	// apart, so they are put in order once, here. None overlaps another.
	sort.SliceStable(edits, func(a, b int) bool { return edits[a].from < edits[b].from })
	out := make([]byte, 0, len(doc)+len(columnStyleElement)+64)
	at := 0
	for _, e := range edits {
		out = append(out, doc[at:e.from]...)
		out = append(out, e.replacement...)
		at = e.to
	}
	return append(out, doc[at:]...), result
}

// isUTF8Text says that the bytes can be edited as they are: they are not UTF-16
// or UTF-32 (a byte-order mark, or the NULs that ASCII markup leaves in them) and
// they are valid UTF-8, which includes the plain ASCII that ISO-8859 and Windows
// code pages share only when it is declared as such (declaresOtherCharset).
func isUTF8Text(doc []byte) bool {
	if bytes.HasPrefix(doc, []byte{0xFE, 0xFF}) || bytes.HasPrefix(doc, []byte{0xFF, 0xFE}) {
		return false
	}
	if bytes.IndexByte(doc[:min(len(doc), 512)], 0) >= 0 {
		return false
	}
	return utf8.Valid(doc)
}

var (
	xmlDeclaredEncoding = regexp.MustCompile(`(?i)^\s*<\?xml[^>]*?\sencoding\s*=\s*["']\s*([A-Za-z0-9_.:-]+)\s*["']`)
	metaDeclaredCharset = regexp.MustCompile(`(?i)charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)`)
	cssDeclaredCharset  = regexp.MustCompile(`(?i)^@charset\s+["']\s*([A-Za-z0-9_.:-]+)\s*["']`)
)

func isUTF8Label(label string) bool {
	switch strings.ToLower(label) {
	case "utf-8", "utf8", "us-ascii", "ascii":
		return true
	}
	return false
}

// declaresOtherCharset: the document says it is in an encoding that is not UTF-8.
// Bytes of such a document would still be ASCII where the edits are, but a
// multi-byte encoding (Shift_JIS, GBK, Big5) can hide an ASCII-looking byte inside
// a character, so it is left alone.
//
// An XML declaration that names an encoding is what the document is in: an XML
// parser reads it as that and ignores every meta element (The Well of Ascension's
// documents say utf-8 in the declaration and carry a stale windows-1252 meta from
// the Word file they were made from, and are UTF-8). Only a document with no such
// declaration is judged by its meta elements, any one of which that names another
// encoding leaves it alone.
func declaresOtherCharset(doc []byte) bool {
	head := bytes.TrimPrefix(doc, []byte{0xEF, 0xBB, 0xBF})
	if match := xmlDeclaredEncoding.FindSubmatch(head[:min(len(head), 512)]); match != nil {
		return !isUTF8Label(string(match[1]))
	}
	// A meta element is in the head, which ends well inside the first kilobytes.
	region := head[:min(len(head), 8<<10)]
	if end := indexFold(region, "</head"); end >= 0 {
		region = region[:end]
	}
	for _, match := range metaDeclaredCharset.FindAllSubmatch(region, -1) {
		if !isUTF8Label(string(match[1])) {
			return true
		}
	}
	return false
}

// restyleSheet is restyleDocument for a stylesheet: its font sizes and line heights
// as rem.
func restyleSheet(sheet []byte) ([]byte, documentResult) {
	body := bytes.TrimPrefix(sheet, []byte{0xEF, 0xBB, 0xBF})
	if bytes.HasPrefix(body, []byte{0xFE, 0xFF}) || bytes.HasPrefix(body, []byte{0xFF, 0xFE}) ||
		bytes.IndexByte(body[:min(len(body), 512)], 0) >= 0 || !utf8.Valid(body) {
		return sheet, documentResult{left: leftEncoding}
	}
	if match := cssDeclaredCharset.FindSubmatch(body[:min(len(body), 128)]); match != nil && !isUTF8Label(string(match[1])) {
		return sheet, documentResult{left: leftEncoding}
	}
	rewritten, count := rewriteSizes(sheet, false)
	return rewritten, documentResult{fontSizes: count.fontSizes, lineHeights: count.lineHeights}
}

type span struct{ from, to int }

// markup is what the scan of a document found.
type markup struct {
	attributes []span // the inside of each style="…"
	blocks     []span // the inside of each <style> element
	headEnd    int    // where the first </head> begins, or -1
	// root is the document's first element when it is <html>.
	root rootElement
	// sources are the stylesheets the document uses, in the order it names them:
	// its <link> and <style> elements that apply on a screen.
	sources []styleSource
	// elements are its paragraphs, list items and body, in document order.
	elements []markupElement
}

// markupElement is a paragraph, a list item or a body: an element Readium CSS gives
// the reader's alignment (epub_align.go).
type markupElement struct {
	name    string // p, li or body
	nameEnd int    // just past "<p", where an attribute can be put
	classes []string
	id      string
	dir     string // lower case
	// style is the inside of its style="…" when it has one; hasStyle says it does.
	style    span
	hasStyle bool
	// unsafe: it cannot be edited with certainty (a style attribute that is unquoted
	// or given twice, an entity in its class or id).
	unsafe bool
}

// rootElement is where the document's <html> begins and whether it already says
// what language it is in.
type rootElement struct {
	// nameEnd is the offset just past "<html", where an attribute can be put; zero
	// when the first element is not <html>.
	nameEnd int
	// hasLanguage: it has a lang or an xml:lang attribute, whatever the value.
	hasLanguage bool
	// lang is the language it names, lang before xml:lang; dir is its direction in
	// lower case.
	lang, dir string
}

// scanMarkup walks the tags of a document. It knows comments, CDATA sections,
// processing instructions, declarations and the raw text of <script> and <style>,
// so a "</head>" or a "<style" inside any of them is not mistaken for markup. It
// skips <svg> and <math>: a size inside an illustration is a size in its own
// units, which the text-size setting must not stretch. It stops quietly at
// anything it cannot follow (a tag or a comment that never ends); what it had
// found by then is what it reports.
func scanMarkup(doc []byte) markup {
	found := markup{headEnd: -1}
	foreign := 0
	firstElement := true
	strayText := false // text, not markup, ahead of the first element
	i, n := 0, len(doc)
	for i < n {
		next := bytes.IndexByte(doc[i:], '<')
		if next < 0 {
			break
		}
		if firstElement && len(bytes.TrimSpace(bytes.TrimPrefix(doc[i:i+next], []byte{0xEF, 0xBB, 0xBF}))) > 0 {
			strayText = true
		}
		i += next
		rest := doc[i:]
		switch {
		case bytes.HasPrefix(rest, []byte("<!--")):
			end := bytes.Index(rest[4:], []byte("-->"))
			if end < 0 {
				return found
			}
			i += 4 + end + 3
		case bytes.HasPrefix(rest, []byte("<![CDATA[")):
			end := bytes.Index(rest, []byte("]]>"))
			if end < 0 {
				return found
			}
			i += end + 3
		case bytes.HasPrefix(rest, []byte("<?")):
			end := bytes.Index(rest, []byte("?>"))
			if end < 0 {
				return found
			}
			i += end + 2
		case bytes.HasPrefix(rest, []byte("<!")):
			end := declarationEnd(rest)
			if end < 0 {
				return found
			}
			i += end
		case bytes.HasPrefix(rest, []byte("</")):
			name := tagName(rest[2:])
			end := bytes.IndexByte(rest, '>')
			if end < 0 {
				return found
			}
			switch strings.ToLower(name) {
			case "head":
				if found.headEnd < 0 && foreign == 0 {
					found.headEnd = i
				}
			case "svg", "math":
				if foreign > 0 {
					foreign--
				}
			}
			i += end + 1
		case len(rest) > 1 && isMarkupLetter(rest[1]):
			tag, ok := readStartTag(rest)
			if !ok {
				return found
			}
			if firstElement {
				// The root element: the first start tag outside comments, declarations
				// and processing instructions, which is all the scan has passed.
				firstElement = false
				if tag.name == "html" && !strayText {
					found.root.nameEnd = i + 1 + len("html")
					for _, attribute := range tag.attributes {
						if attribute.name == "lang" || attribute.name == "xml:lang" {
							found.root.hasLanguage = true
							if found.root.lang == "" || attribute.name == "lang" {
								found.root.lang = strings.TrimSpace(attribute.value)
							}
						}
					}
					found.root.dir = strings.ToLower(strings.TrimSpace(tag.attribute("dir")))
				}
			}
			inForeign := foreign > 0 || tag.name == "svg" || tag.name == "math"
			if !inForeign {
				for _, attribute := range tag.attributes {
					if attribute.name == "style" && attribute.quoted {
						found.attributes = append(found.attributes, span{i + attribute.from, i + attribute.to})
					}
				}
			}
			if !inForeign {
				switch tag.name {
				case "p", "li", "body":
					found.elements = append(found.elements, alignedElementOf(tag, i))
				case "link":
					if href, ok := stylesheetLink(tag); ok {
						found.sources = append(found.sources, styleSource{href: href})
					}
				}
			}
			i += tag.end
			if tag.selfClosing {
				continue
			}
			switch tag.name {
			case "svg", "math":
				foreign++
			case "script", "style":
				// Raw text to the end tag, whatever it looks like.
				stop := indexFold(doc[i:], "</"+tag.name)
				if stop < 0 {
					return found
				}
				if tag.name == "style" && !inForeign && isCSSType(tag.attribute("type")) {
					found.blocks = append(found.blocks, span{i, i + stop})
					if appliesOnScreen(tag.attribute("media")) {
						found.sources = append(found.sources, styleSource{block: true, from: i, to: i + stop})
					}
				}
				i += stop
			}
		default:
			i++
		}
	}
	return found
}

// alignedElementOf notes the start tag at doc[at] of a paragraph, a list item or a body.
func alignedElementOf(tag startTag, at int) markupElement {
	element := markupElement{name: tag.name, nameEnd: at + 1 + len(tag.name)}
	styles, classes, ids := 0, 0, 0
	for _, attribute := range tag.attributes {
		switch attribute.name {
		case "style":
			styles++
			if attribute.quoted {
				element.style, element.hasStyle = span{at + attribute.from, at + attribute.to}, true
			}
		case "class":
			classes++
			element.classes = strings.Fields(attribute.value)
			if strings.ContainsAny(attribute.value, "&\\") {
				element.unsafe = true
			}
		case "id":
			ids++
			element.id = attribute.value
			if strings.ContainsAny(attribute.value, "&\\") {
				element.unsafe = true
			}
		case "dir":
			element.dir = strings.ToLower(strings.TrimSpace(attribute.value))
		}
	}
	if styles > 1 || classes > 1 || ids > 1 || (styles == 1 && !element.hasStyle) {
		element.unsafe = true
	}
	return element
}

// stylesheetLink is the address a <link> gives a stylesheet that applies on a screen.
func stylesheetLink(tag startTag) (string, bool) {
	relations := strings.Fields(strings.ToLower(tag.attribute("rel")))
	if !listContains(relations, "stylesheet") || listContains(relations, "alternate") {
		return "", false
	}
	if !isCSSType(tag.attribute("type")) || !appliesOnScreen(tag.attribute("media")) {
		return "", false
	}
	href := strings.TrimSpace(tag.attribute("href"))
	if href == "" || strings.Contains(href, "&") {
		return "", false
	}
	return href, true
}

// appliesOnScreen: a stylesheet with no media, or all or screen, which is what a
// reader shows. Anything else (print, a Kindle's amzn-kf8, a width) is left out.
func appliesOnScreen(media string) bool {
	media = strings.ToLower(strings.TrimSpace(media))
	return media == "" || media == "all" || media == "screen"
}

func isCSSType(value string) bool {
	value = strings.ToLower(strings.TrimSpace(value))
	return value == "" || value == "text/css"
}

func isMarkupLetter(c byte) bool { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' }

// tagName is the name at the start of s: up to a space, a slash or a bracket.
func tagName(s []byte) string {
	end := 0
	for end < len(s) && !isMarkupSpace(s[end]) && s[end] != '/' && s[end] != '>' {
		end++
	}
	return string(s[:end])
}

// declarationEnd is where a <!DOCTYPE …> ends in s, past its closing ">". An
// internal subset in square brackets may hold ">" and quoted strings may hold
// anything.
func declarationEnd(s []byte) int {
	bracket := 0
	for i := 2; i < len(s); i++ {
		switch s[i] {
		case '"', '\'':
			end := bytes.IndexByte(s[i+1:], s[i])
			if end < 0 {
				return -1
			}
			i += end + 1
		case '[':
			bracket++
		case ']':
			if bracket > 0 {
				bracket--
			}
		case '>':
			if bracket == 0 {
				return i + 1
			}
		}
	}
	return -1
}

type tagAttribute struct {
	name     string // lower case
	from, to int    // the value, inside its quotes, relative to the tag's "<"
	quoted   bool
	value    string
}

type startTag struct {
	name        string // lower case
	end         int    // past the ">", relative to the tag's "<"
	selfClosing bool
	attributes  []tagAttribute
}

func (t startTag) attribute(name string) string {
	for _, attribute := range t.attributes {
		if attribute.name == name {
			return attribute.value
		}
	}
	return ""
}

// readStartTag reads the start tag that opens at s[0] == '<'. It is false for a
// tag that never ends.
func readStartTag(s []byte) (startTag, bool) {
	tag := startTag{}
	i := 1
	for i < len(s) && !isMarkupSpace(s[i]) && s[i] != '/' && s[i] != '>' {
		i++
	}
	tag.name = strings.ToLower(string(s[1:i]))
	for {
		for i < len(s) && isMarkupSpace(s[i]) {
			i++
		}
		if i >= len(s) {
			return tag, false
		}
		switch s[i] {
		case '>':
			tag.end = i + 1
			return tag, true
		case '/':
			if i+1 < len(s) && s[i+1] == '>' {
				tag.selfClosing = true
				tag.end = i + 2
				return tag, true
			}
			i++
			continue
		}
		nameFrom := i
		for i < len(s) && !isMarkupSpace(s[i]) && s[i] != '=' && s[i] != '/' && s[i] != '>' {
			i++
		}
		attribute := tagAttribute{name: strings.ToLower(string(s[nameFrom:i]))}
		for i < len(s) && isMarkupSpace(s[i]) {
			i++
		}
		if i < len(s) && s[i] == '=' {
			i++
			for i < len(s) && isMarkupSpace(s[i]) {
				i++
			}
			if i >= len(s) {
				return tag, false
			}
			if quote := s[i]; quote == '"' || quote == '\'' {
				end := bytes.IndexByte(s[i+1:], quote)
				if end < 0 {
					return tag, false
				}
				attribute.from, attribute.to, attribute.quoted = i+1, i+1+end, true
				attribute.value = string(s[attribute.from:attribute.to])
				i += end + 2
			} else {
				from := i
				for i < len(s) && !isMarkupSpace(s[i]) && s[i] != '>' {
					i++
				}
				attribute.from, attribute.to, attribute.value = from, i, string(s[from:i])
			}
		}
		if attribute.name != "" {
			tag.attributes = append(tag.attributes, attribute)
		} else {
			i++ // a stray "=" or quote: step over it
		}
	}
}

// indexFold is bytes.Index with the needle matched in any case. It looks for the
// needle's first byte as written, so the needle must begin with a byte that has no
// case: every one here begins with "<".
func indexFold(haystack []byte, needle string) int {
	want := []byte(needle)
	for from := 0; from < len(haystack); {
		at := bytes.IndexByte(haystack[from:], want[0])
		if at < 0 {
			return -1
		}
		at += from
		if at+len(want) > len(haystack) {
			return -1
		}
		if bytes.EqualFold(haystack[at:at+len(want)], want) {
			return at
		}
		from = at + 1
	}
	return -1
}
