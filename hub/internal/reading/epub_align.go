package reading

import (
	"bytes"
	"strings"
)

// A book's own right, centre and end alignment, kept under the reader's text
// alignment (#57).
//
// Both readers default to publisher styles off and Justify. Readium CSS then makes
// the document inherit the reader's alignment with
//
//	:root[style*=readium-advanced-on][style*="--USER__textAlign"] :not(blockquote):not(figcaption) p,
//	:root[…] body,
//	:root[…] li { text-align: inherit !important }
//
// (ReadiumCSS-after.css of Readium Kotlin 3.0.0; the rtl set has the same rule, the
// two cjk sets have none). So the elements it replaces the book's alignment on are
// exactly `p`, `li` and `body`, and no other: not headings (h1 to h6), `div`,
// `blockquote`, `figcaption`, `section`, `td` or `dd`, which keep what the book gives
// them, and which hand it on to the paragraphs inside them, since a paragraph
// inherits. The selector reads as if it spared a paragraph inside a blockquote or a
// figcaption, but `body` is a descendant of the root that is neither, so it matches
// every paragraph there is (measured in Chrome 154: a `p.r { text-align: right }` in a
// blockquote or a figure is justified, an `h1.r`, `div.r`, `td.r` and `figcaption.r`
// stay right). Babel's epigraph attribution (`.epi_at { text-align: right }`) and the
// centred `* * *` of most books therefore ended up on the left, or justified.
//
// An inline declaration outranks every stylesheet's, `!important` ones included, so a
// `p`, `li` or `body` that the book aligns right, centre or end gets that alignment
// again as `text-align: … !important` in its own style attribute. What the book
// justifies, or leaves left or at the start, is the reader's to decide, as it was.
//
// What sets the alignment, in the order a browser would weigh it (importance, then the
// style attribute, then specificity, then which came last):
//   - the book's class, id and type rules, whether in a linked stylesheet or in a <style>
//     element: `.cls`, `p.cls`, `p`, `li.a.b`, `#id`, `p#id`, `body.cls`, and lists of
//     them;
//   - the element's own style attribute.
//
// Anything more than that is not evaluated: a descendant or child combinator, an
// attribute selector, a pseudo-class, a rule inside @media or @supports, a stylesheet
// that another one imports. It is not guessed at either: a rule of that kind that could
// win over what was worked out, and says something else, leaves the element exactly as
// it was.
//
// A right edge is the start of a line in a right-to-left book, which is where the
// reader's own alignment puts it already, and keeping it on every paragraph would
// unjustify the whole book. So `right` is not kept in a document that is right to left
// (its language, a dir="rtl" on the root, the body or the element, or a rule that
// sets `direction: rtl`); `center` and `end` are.
//
// `body` is given its alignment too (a title page that centres its body): its
// paragraphs inherit it, so a document in which some paragraph or list item is
// explicitly aligned another way would have that one centred as well, and for such a
// document the body is left alone.

// isAlignedElement says that Readium CSS replaces the alignment of an element of this
// name with the reader's.
func isAlignedElement(name string) bool {
	return name == "p" || name == "li" || name == "body"
}

// normalAlignment is a text-align value in lower case, without the vendor prefix that
// Chrome and WebKit accept for center and right.
func normalAlignment(value string) string {
	value = strings.ToLower(strings.TrimSpace(value))
	for _, prefix := range [...]string{"-webkit-", "-moz-", "-epub-"} {
		if rest, found := strings.CutPrefix(value, prefix); found {
			return rest
		}
	}
	return value
}

// keptAlignment says that the reader's alignment must not replace this one.
func keptAlignment(value string, rightToLeft bool) bool {
	switch value {
	case "center", "end":
		return true
	case "right":
		return !rightToLeft
	}
	return false
}

// inheritsAlignment: the value is the reader's own way of working out a paragraph's
// alignment, which is to take its parent's.
func inheritsAlignment(value string) bool { return value == "inherit" || value == "unset" }

// rightToLeftLanguage says that a language tag is one written from right to left.
func rightToLeftLanguage(tag string) bool {
	switch strings.ToLower(strings.SplitN(strings.TrimSpace(tag), "-", 2)[0]) {
	case "ar", "arc", "ckb", "dv", "fa", "he", "iw", "ks", "ps", "sd", "syr", "ug", "ur", "yi":
		return true
	}
	return false
}

// A style rule that says something about text-align.

// alignRule is one text-align declaration of one selector of a stylesheet.
type alignRule struct {
	subject     selectorSubject
	maybe       bool // not evaluated; see the notes above
	specificity int
	value       string // normalAlignment
	important   bool
	seq         int // the order in the document: later wins at equal specificity
}

// selectorSubject is what a selector says about the element it ends on.
type selectorSubject struct {
	// any: the selector could not be read, so it may be about any element.
	any     bool
	name    string // lower case; empty or "*" is any element
	classes []string
	ids     []string
}

// sheetAlignment is what a stylesheet, or a <style> element, says that bears on the
// alignment of a paragraph, a list item or a body.
type sheetAlignment struct {
	rules []alignRule
	// rightToLeft: some rule in it sets `direction: rtl`.
	rightToLeft bool
}

// unreadableSpecificity is the weight given a selector that could not be read: more
// than any that can, so that it is taken as able to win.
const unreadableSpecificity = 1 << 30

// parseAlignSheet reads the text-align and direction declarations of a stylesheet.
func parseAlignSheet(css []byte) *sheetAlignment {
	// A byte-order mark would be read as the first letters of the first selector.
	css = bytes.TrimPrefix(css, []byte{0xEF, 0xBB, 0xBF})
	sheet := &sheetAlignment{}
	seq := 0
	scanAlignRules(css, false, &seq, sheet)
	return sheet
}

// scanAlignRules walks the rules of css, which is a stylesheet or the inside of an
// at-rule. forced says that every rule found is to be taken as not evaluated (inside
// @media and the like).
func scanAlignRules(css []byte, forced bool, seq *int, out *sheetAlignment) {
	i, n := 0, len(css)
	for i < n {
		switch c := css[i]; {
		case isCSSSpace(c):
			i++
		case c == '/' && i+1 < n && css[i+1] == '*':
			i = skipCSSComment(css, i)
		case bytes.HasPrefix(css[i:], []byte("<!--")):
			// The markers that wrap a stylesheet in an XHTML <style> element.
			i += len("<!--")
		case bytes.HasPrefix(css[i:], []byte("-->")):
			i += len("-->")
		case bytes.HasPrefix(css[i:], []byte("]]>")):
			i += len("]]>")
		case bytes.HasPrefix(css[i:], []byte("<![CDATA[")):
			i += len("<![CDATA[")
		case c == '}':
			i++
		default:
			end, found := cssPreludeEnd(css, i)
			if !found {
				return
			}
			if css[end] == ';' {
				// @import, @charset, @namespace: a statement. An @import is not followed.
				i = end + 1
				continue
			}
			after, closed := skipCSSBlockClosed(css, end)
			if c == '@' {
				name := strings.ToLower(string(css[i+1 : identEnd(css, i+1)]))
				switch name {
				case "media", "supports", "layer", "container", "document", "-moz-document":
					inner := css[end+1 : after]
					if closed {
						inner = css[end+1 : after-1]
					}
					scanAlignRules(inner, true, seq, out)
				}
			} else {
				inner := css[end+1 : after]
				if closed {
					inner = css[end+1 : after-1]
				}
				recordAlignRule(css[i:end], inner, forced, seq, out)
			}
			i = after
		}
	}
}

// identEnd is where the identifier that begins at css[i] ends.
func identEnd(css []byte, i int) int {
	for i < len(css) && isCSSIdentChar(css[i]) {
		i++
	}
	return i
}

// cssPreludeEnd is the first "{" or ";" at or after css[i] that is not in a comment, a
// string, a bracket or a parenthesis.
func cssPreludeEnd(css []byte, i int) (int, bool) {
	depth := 0
	for i < len(css) {
		switch c := css[i]; {
		case c == '/' && i+1 < len(css) && css[i+1] == '*':
			i = skipCSSComment(css, i)
		case c == '"' || c == '\'':
			i = skipCSSStringAndBreak(css, i)
		case c == '\\':
			i += 2
		case c == '[' || c == '(':
			depth++
			i++
		case c == ']' || c == ')':
			if depth > 0 {
				depth--
			}
			i++
		case (c == '{' || c == ';') && depth == 0:
			return i, true
		default:
			i++
		}
	}
	return 0, false
}

// skipCSSBlockClosed moves past the block that opens at css[open], with any block in
// it, and says whether it was closed (a stylesheet may simply end).
func skipCSSBlockClosed(css []byte, open int) (after int, closed bool) {
	depth := 0
	for i := open; i < len(css); {
		switch c := css[i]; {
		case c == '/' && i+1 < len(css) && css[i+1] == '*':
			i = skipCSSComment(css, i)
		case c == '"' || c == '\'':
			i = skipCSSStringAndBreak(css, i)
		case c == '\\':
			i += 2
		case c == '{':
			depth++
			i++
		case c == '}':
			depth--
			i++
			if depth == 0 {
				return i, true
			}
		default:
			i++
		}
	}
	return len(css), false
}

// recordAlignRule adds the text-align declarations of one rule, for each selector of
// its list.
func recordAlignRule(selectors, body []byte, forced bool, seq *int, out *sheetAlignment) {
	var aligns []cssDeclaration
	for _, declaration := range cssDeclarations(body) {
		switch declaration.property {
		case "text-align":
			aligns = append(aligns, declaration)
		case "direction":
			if strings.EqualFold(declaration.value, "rtl") {
				out.rightToLeft = true
			}
		}
	}
	if len(aligns) == 0 {
		return
	}
	for _, text := range splitSelectorList(string(maskCSSComments(selectors))) {
		parsed, aboutElement := parseAlignSelector(text)
		if !aboutElement {
			continue
		}
		for _, declaration := range aligns {
			*seq++
			out.rules = append(out.rules, alignRule{
				subject:     parsed.subject,
				maybe:       forced || !parsed.exact,
				specificity: parsed.specificity,
				value:       normalAlignment(declaration.value),
				important:   declaration.important,
				seq:         *seq,
			})
		}
	}
}

// splitSelectorList splits a selector list at its commas, leaving those inside a
// bracket, a parenthesis or a string.
func splitSelectorList(text string) []string {
	var parts []string
	depth, start := 0, 0
	for i := 0; i < len(text); i++ {
		switch c := text[i]; {
		case c == '"' || c == '\'':
			if end := strings.IndexByte(text[i+1:], c); end >= 0 {
				i += end + 1
			} else {
				i = len(text)
			}
		case c == '\\':
			i++
		case c == '[' || c == '(':
			depth++
		case c == ']' || c == ')':
			if depth > 0 {
				depth--
			}
		case c == ',' && depth == 0:
			parts = append(parts, text[start:i])
			start = i + 1
		}
	}
	return append(parts, text[start:])
}

// parsedSelector is a selector as far as the alignment of a paragraph needs it.
type parsedSelector struct {
	subject     selectorSubject
	specificity int
	// exact: nothing but a type, classes and ids, so that it is evaluated.
	exact bool
}

// parseAlignSelector reads one selector. It answers false for one that is not about
// an element of the document that could be a paragraph, a list item or a body: a
// selector on another element, or on a pseudo-element.
func parseAlignSelector(text string) (parsedSelector, bool) {
	text = strings.TrimSpace(text)
	if text == "" {
		return parsedSelector{}, false
	}
	compounds, readable := splitCompounds(text)
	if !readable || len(compounds) == 0 {
		return parsedSelector{subject: selectorSubject{any: true}, specificity: unreadableSpecificity}, true
	}
	var result parsedSelector
	result.exact = len(compounds) == 1
	for index, compound := range compounds {
		parts, ok := parseCompound(compound)
		last := index == len(compounds)-1
		if !ok {
			if last {
				return parsedSelector{subject: selectorSubject{any: true}, specificity: unreadableSpecificity}, true
			}
			result.exact = false
			continue
		}
		if parts.pseudoElement {
			return parsedSelector{}, false
		}
		result.specificity += parts.ids*10000 + parts.others*100 + parts.types
		if parts.opaque {
			result.exact = false
		}
		if last {
			result.subject = selectorSubject{name: parts.name, classes: parts.classes, ids: parts.idNames}
		}
	}
	name := result.subject.name
	if !(name == "" || name == "*" || isAlignedElement(name)) {
		return parsedSelector{}, false
	}
	return result, true
}

// splitCompounds splits a selector at its combinators (a space, >, + or ~ outside a
// bracket, a parenthesis or a string). It is false for one that holds an escape.
func splitCompounds(text string) ([]string, bool) {
	var compounds []string
	depth, start := 0, 0
	flush := func(end int) {
		if end > start {
			compounds = append(compounds, text[start:end])
		}
	}
	for i := 0; i < len(text); i++ {
		switch c := text[i]; {
		case c == '\\':
			return nil, false
		case c == '"' || c == '\'':
			end := strings.IndexByte(text[i+1:], c)
			if end < 0 {
				return nil, false
			}
			i += end + 1
		case c == '[' || c == '(':
			depth++
		case c == ']' || c == ')':
			if depth > 0 {
				depth--
			}
		case depth == 0 && (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '>' || c == '+' || c == '~'):
			flush(i)
			start = i + 1
		}
	}
	flush(len(text))
	return compounds, true
}

// compoundParts is one compound selector: a type, classes, ids, and whether there was
// more (an attribute, a pseudo-class) that narrows it further.
type compoundParts struct {
	name          string
	classes       []string
	idNames       []string
	ids, others   int // the specificity columns for ids, and for classes, attributes and pseudo-classes
	types         int
	opaque        bool
	pseudoElement bool
}

func parseCompound(text string) (compoundParts, bool) {
	var parts compoundParts
	i := 0
	switch {
	case i < len(text) && text[i] == '*':
		parts.name = "*"
		i++
	case i < len(text) && isCSSIdentStart(text[i]):
		end := identChars(text, i)
		parts.name = strings.ToLower(text[i:end])
		parts.types = 1
		i = end
	}
	for i < len(text) {
		switch text[i] {
		case '.':
			end := identChars(text, i+1)
			if end == i+1 {
				return parts, false
			}
			parts.classes = append(parts.classes, text[i+1:end])
			parts.others++
			i = end
		case '#':
			end := identChars(text, i+1)
			if end == i+1 {
				return parts, false
			}
			parts.idNames = append(parts.idNames, text[i+1:end])
			parts.ids++
			i = end
		case '[':
			end := closingBracket(text, i, '[', ']')
			if end < 0 {
				return parts, false
			}
			parts.opaque = true
			parts.others++
			i = end + 1
		case ':':
			i++
			element := false
			if i < len(text) && text[i] == ':' {
				element = true
				i++
			}
			end := identChars(text, i)
			if end == i {
				return parts, false
			}
			switch strings.ToLower(text[i:end]) {
			case "first-line", "first-letter", "before", "after":
				element = true
			}
			i = end
			if i < len(text) && text[i] == '(' {
				close := closingBracket(text, i, '(', ')')
				if close < 0 {
					return parts, false
				}
				i = close + 1
			}
			if element {
				parts.pseudoElement = true
			} else {
				parts.opaque = true
				parts.others++
			}
		default:
			return parts, false
		}
	}
	return parts, true
}

func identChars(text string, i int) int {
	for i < len(text) && isCSSIdentChar(text[i]) {
		i++
	}
	return i
}

// closingBracket is where the bracket that opens at text[open] closes.
func closingBracket(text string, open int, opening, closing byte) int {
	depth := 0
	for i := open; i < len(text); i++ {
		switch c := text[i]; {
		case c == '"' || c == '\'':
			end := strings.IndexByte(text[i+1:], c)
			if end < 0 {
				return -1
			}
			i += end + 1
		case c == opening:
			depth++
		case c == closing:
			depth--
			if depth == 0 {
				return i
			}
		}
	}
	return -1
}

// A declaration, in a rule or in a style attribute.

type cssDeclaration struct {
	property  string // lower case
	value     string // without !important, trimmed
	important bool
	// valueEnd is where the value ends in the text the declaration was read from.
	valueEnd int
}

// maskCSSComments returns css with each comment turned into spaces of the same length,
// so that every offset in it is an offset of the original. It returns css itself when
// there is no comment in it.
func maskCSSComments(css []byte) []byte {
	var out []byte
	for i := 0; i < len(css); {
		switch c := css[i]; {
		case c == '/' && i+1 < len(css) && css[i+1] == '*':
			end := skipCSSComment(css, i)
			if out == nil {
				out = bytes.Clone(css)
			}
			for j := i; j < end; j++ {
				out[j] = ' '
			}
			i = end
		case c == '"' || c == '\'':
			i = skipCSSStringAndBreak(css, i)
		case c == '\\':
			i += 2
		default:
			i++
		}
	}
	if out == nil {
		return css
	}
	return out
}

// skipCSSStringAndBreak is skipCSSString, and steps over the line break that ended a
// string that was never closed, so that scanning goes on past it.
func skipCSSStringAndBreak(css []byte, i int) int {
	i = skipCSSString(css, i)
	if i < len(css) && (css[i] == '\n' || css[i] == '\r' || css[i] == '\f') {
		i++
	}
	return i
}

// cssDeclarations reads the declarations of a rule's block or a style attribute. A
// nested rule (CSS nesting) is skipped.
func cssDeclarations(text []byte) []cssDeclaration {
	text = maskCSSComments(text)
	var out []cssDeclaration
	start := 0
	flush := func(end int) {
		if end > len(text) {
			end = len(text)
		}
		if start < end {
			if declaration, ok := readDeclaration(text[start:end], start); ok {
				out = append(out, declaration)
			}
		}
	}
	depth := 0
	for i := 0; i < len(text); {
		switch c := text[i]; {
		case c == '/' && i+1 < len(text) && text[i+1] == '*':
			i = skipCSSComment(text, i)
		case c == '"' || c == '\'':
			i = skipCSSStringAndBreak(text, i)
		case c == '\\':
			i += 2
		case c == '(':
			depth++
			i++
		case c == ')':
			if depth > 0 {
				depth--
			}
			i++
		case c == '{':
			after, _ := skipCSSBlockClosed(text, i)
			i, start = after, after
		case c == ';' && depth == 0:
			flush(i)
			i++
			start = i
		default:
			i++
		}
	}
	flush(len(text))
	return out
}

// readDeclaration reads `property: value [!important]`. base is where piece begins in
// the text it was cut from.
func readDeclaration(piece []byte, base int) (cssDeclaration, bool) {
	colon := bytes.IndexByte(piece, ':')
	if colon < 0 {
		return cssDeclaration{}, false
	}
	name := bytes.TrimSpace(piece[:colon])
	rest := piece[colon+1:]
	lead := len(rest) - len(bytes.TrimLeft(rest, " \t\r\n\f"))
	value := bytes.TrimRight(rest[lead:], " \t\r\n\f")
	important := false
	if bang := bytes.LastIndexByte(value, '!'); bang >= 0 && strings.EqualFold(string(bytes.TrimSpace(value[bang+1:])), "important") {
		important = true
		value = bytes.TrimRight(value[:bang], " \t\r\n\f")
	}
	return cssDeclaration{
		property:  strings.ToLower(string(name)),
		value:     string(value),
		important: important,
		valueEnd:  base + colon + 1 + lead + len(value),
	}, true
}

// The cascade.

// alignTarget is an element the alignment of which is asked for.
type alignTarget struct {
	name    string
	classes []string
	id      string
	// inline are the text-align declarations of its style attribute.
	inline []cssDeclaration
}

func (s selectorSubject) matches(target alignTarget) bool {
	if s.any {
		return true
	}
	if s.name != "" && s.name != "*" && s.name != target.name {
		return false
	}
	for _, class := range s.classes {
		if !listContains(target.classes, class) {
			return false
		}
	}
	for _, id := range s.ids {
		if id != target.id {
			return false
		}
	}
	return true
}

func listContains(list []string, want string) bool {
	for _, item := range list {
		if item == want {
			return true
		}
	}
	return false
}

// cascadeEntry is where a declaration stands among the others.
type cascadeEntry struct {
	important   bool
	inline      bool
	specificity int
	seq         int
}

// beats: the browser's order. An important declaration beats one that is not, then a
// style attribute beats a rule, then the more specific rule, then the later one.
func (a cascadeEntry) beats(b cascadeEntry) bool {
	switch {
	case a.important != b.important:
		return a.important
	case a.inline != b.inline:
		return a.inline
	case a.specificity != b.specificity:
		return a.specificity > b.specificity
	}
	return a.seq > b.seq
}

// resolvedAlignment is what the book itself says an element's alignment is.
type resolvedAlignment struct {
	// value is the alignment of the winning declaration, or empty when the element
	// has none of its own.
	value     string
	inline    bool // the winning declaration is in the element's style attribute
	important bool
	// uncertain: a rule that is not evaluated could win over this one and says
	// something else.
	uncertain bool
	// explicitOther: the element is, or may be, aligned in some way that is neither
	// kept nor inherited.
	explicitOther bool
}

func resolveAlignment(rules []alignRule, target alignTarget, rightToLeft bool) resolvedAlignment {
	var best cascadeEntry
	var result resolvedAlignment
	found := false
	consider := func(entry cascadeEntry, value string) {
		if !found || entry.beats(best) {
			best, result.value, found = entry, value, true
			result.inline, result.important = entry.inline, entry.important
		}
	}
	for _, rule := range rules {
		if !rule.maybe && rule.subject.matches(target) {
			consider(cascadeEntry{important: rule.important, specificity: rule.specificity, seq: rule.seq}, rule.value)
		}
	}
	for index, declaration := range target.inline {
		consider(cascadeEntry{important: declaration.important, inline: true, seq: index}, normalAlignment(declaration.value))
	}
	if found && !keptAlignment(result.value, rightToLeft) && !inheritsAlignment(result.value) {
		result.explicitOther = true
	}
	for _, rule := range rules {
		if !rule.maybe || !rule.subject.matches(target) {
			continue
		}
		if !keptAlignment(rule.value, rightToLeft) && !inheritsAlignment(rule.value) {
			result.explicitOther = true
		}
		entry := cascadeEntry{important: rule.important, specificity: rule.specificity, seq: rule.seq}
		if found && entry.beats(best) && rule.value != result.value {
			result.uncertain = true
		}
	}
	if result.uncertain {
		result.explicitOther = true
	}
	return result
}

// What the book's stylesheets give a document.

// styleSource is a stylesheet a document uses, in the order the document gives them.
type styleSource struct {
	// href is a linked stylesheet's address; from and to are the inside of a <style>
	// element when block is set.
	href     string
	block    bool
	from, to int
}

// bookStyles is what the stylesheets of a book say about alignment, by entry name.
type bookStyles struct {
	sheets map[string]*sheetAlignment
}

// rulesFor puts together the rules a document is under, in the order of its
// <link> and <style> elements, and says whether any of them sets a direction of
// right to left. A nil bookStyles knows no linked stylesheet.
func (b *bookStyles) rulesFor(doc []byte, sources []styleSource, name string) (rules []alignRule, rightToLeft bool) {
	for index, source := range sources {
		var sheet *sheetAlignment
		switch {
		case source.block:
			sheet = parseAlignSheet(doc[source.from:source.to])
		case b != nil:
			if resolved, _, ok := resolveRef(name, source.href); ok {
				sheet = b.sheets[resolved]
			}
		}
		if sheet == nil {
			continue
		}
		rightToLeft = rightToLeft || sheet.rightToLeft
		for _, rule := range sheet.rules {
			rule.seq += (index + 1) << 24
			rules = append(rules, rule)
		}
	}
	return rules, rightToLeft
}

// The edit.

// alignFix is what is written on one element.
type alignFix struct {
	value string // right, center or end
	// merge: the element's own text-align declaration is the one that wins, and it is
	// made important where it stands rather than another added after it.
	merge bool
}

// styleAttribute is the whole attribute, for an element that has none.
func (f alignFix) styleAttribute() string {
	return ` style="text-align: ` + f.value + ` !important"`
}

// applyToStyle returns the value of a style attribute with the alignment made
// important, the other declarations as they were.
func (f alignFix) applyToStyle(style []byte) []byte {
	if f.merge {
		declarations := cssDeclarations(style)
		for i := len(declarations) - 1; i >= 0; i-- {
			if declarations[i].property == "text-align" && !declarations[i].important {
				at := declarations[i].valueEnd
				out := make([]byte, 0, len(style)+len(" !important"))
				out = append(out, style[:at]...)
				out = append(out, " !important"...)
				return append(out, style[at:]...)
			}
		}
	}
	trimmed := bytes.TrimRight(style, " \t\r\n\f")
	declaration := "text-align: " + f.value + " !important"
	out := make([]byte, 0, len(trimmed)+len(declaration)+3)
	out = append(out, trimmed...)
	switch {
	case len(trimmed) == 0:
		return append(out, declaration...)
	case trimmed[len(trimmed)-1] == ';':
		return append(append(out, ' '), declaration+";"...)
	}
	return append(out, "; "+declaration...)
}

// alignInsert is a style attribute put on an element that had none.
type alignInsert struct {
	at   int
	text string
}

// alignPlan is every change to a document's alignment.
type alignPlan struct {
	// styles are the fixes for elements that have a style attribute, by where the
	// inside of the attribute begins.
	styles  map[int]alignFix
	inserts []alignInsert
	count   int
}

// planAlignment works out which paragraphs, list items and bodies of a document are
// to be given their book's alignment.
func planAlignment(doc []byte, found markup, name, language string, book *bookStyles) alignPlan {
	if len(found.elements) == 0 {
		return alignPlan{}
	}
	rules, sheetsRightToLeft := book.rulesFor(doc, found.sources, name)
	documentLanguage := language
	if found.root.lang != "" {
		documentLanguage = found.root.lang
	}
	documentRTL := sheetsRightToLeft || rightToLeftLanguage(documentLanguage) || found.root.dir == "rtl"
	if found.root.dir == "ltr" {
		documentRTL = false
	}

	plan := alignPlan{styles: map[int]alignFix{}}
	apply := func(element markupElement, fix alignFix) {
		if element.hasStyle {
			plan.styles[element.style.from] = fix
		} else {
			plan.inserts = append(plan.inserts, alignInsert{at: element.nameEnd, text: fix.styleAttribute()})
		}
		plan.count++
	}
	var body *markupElement
	var bodyFix alignFix
	conflict := false // some paragraph or list item is aligned another way than its body
	bodyRTL := documentRTL
	for _, element := range found.elements {
		element := element
		if element.unsafe {
			if element.name != "body" {
				conflict = true
			}
			continue
		}
		target := alignTarget{name: element.name, classes: element.classes, id: element.id}
		rightToLeft := bodyRTL
		if element.name == "body" {
			rightToLeft = documentRTL
		}
		switch element.dir {
		case "rtl":
			rightToLeft = true
		case "ltr":
			rightToLeft = false
		}
		if element.hasStyle {
			for _, declaration := range cssDeclarations(doc[element.style.from:element.style.to]) {
				switch declaration.property {
				case "text-align":
					target.inline = append(target.inline, declaration)
				case "direction":
					if strings.EqualFold(declaration.value, "rtl") {
						rightToLeft = true
					}
				}
			}
		}
		resolved := resolveAlignment(rules, target, rightToLeft)
		if element.name == "body" {
			bodyRTL = rightToLeft
		} else if resolved.explicitOther {
			conflict = true
		}
		if resolved.value == "" || resolved.uncertain || !keptAlignment(resolved.value, rightToLeft) {
			continue
		}
		if resolved.inline && resolved.important {
			continue // it outranks the reader's already
		}
		// keptAlignment let only right, center and end through: safe to write as they are.
		fix := alignFix{value: resolved.value, merge: resolved.inline}
		if element.name == "body" {
			body, bodyFix = &element, fix
			continue
		}
		apply(element, fix)
	}
	if body != nil && !conflict {
		apply(*body, bodyFix)
	}
	return plan
}
