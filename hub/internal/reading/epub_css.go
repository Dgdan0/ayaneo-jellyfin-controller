package reading

import (
	"bytes"
	"strconv"
	"strings"
)

// A book's own font sizes, made to follow the reader's text size.
//
// Both apps scale text by setting the root element's font size (Readium CSS:
// `:root { font-size: var(--USER__fontSize) !important }`). Only a size that is
// relative to the root follows it. A publisher's `font-size: medium`, `small`,
// `12px` or `10pt` is absolute: it keeps its size however the root changes, so the
// text-size setting did nothing on A Game of Thrones, whose stylesheet says
// `p.* { font-size: medium }` 42 times and `body { font-size: small }`.
//
// Each of those becomes the rem that is the same size at the browsers' own 16px
// root, so the page looks as it did at 100% and now scales. Nothing else changes:
// em, %, rem, ex, ch, vw, smaller, larger, inherit, calc(), the `font:` shorthand
// and every other property stay as written. The rewrite is a tokenising pass over
// the stylesheet rather than a pattern replace, so a comment, a string, a url() or
// a selector that merely contains the words is never mistaken for a declaration.

// fontSizeKeywords are the absolute-size keywords at the browsers' 16px scale
// (9px, 10px, 13px, 16px, 18px, 24px, 32px, 48px), as rem.
var fontSizeKeywords = map[string]string{
	"xx-small":  ".5625rem",
	"x-small":   ".625rem",
	"small":     ".8125rem",
	"medium":    "1rem",
	"large":     "1.125rem",
	"x-large":   "1.5rem",
	"xx-large":  "2rem",
	"xxx-large": "3rem",
}

// rewriteFontSizes returns css with its absolute font-size declarations as rem, and
// how many it changed. It returns the input itself, not a copy, when none changed.
// declarations says that css is a declaration list (a style attribute) rather than
// a stylesheet, so a declaration may begin at its first token.
func rewriteFontSizes(css []byte, declarations bool) ([]byte, int) {
	var out []byte
	written := 0 // css[:written] is already in out
	changed := 0
	depth, parens := 0, 0
	// The last token that was not space or a comment, as a class: '{', '}', ';',
	// '(', ')', 's' (a string), 'i' (a word) or 'o' (anything else). A declaration
	// begins after '{' or ';' and nowhere else, so `a:hover` in a selector, an
	// `@supports (font-size: medium)` and a `.font-size:hover` are never read as one.
	prev := byte(0)
	if declarations {
		prev = ';'
	}
	i, n := 0, len(css)
	for i < n {
		c := css[i]
		switch {
		case isCSSSpace(c):
			i++
		case c == '/' && i+1 < n && css[i+1] == '*':
			i = skipCSSComment(css, i)
		case c == '"' || c == '\'':
			i = skipCSSString(css, i)
			prev = 's'
		case c == '{':
			depth++
			prev = '{'
			i++
		case c == '}':
			if depth > 0 {
				depth--
			}
			parens = 0
			prev = '}'
			i++
		case c == ';':
			prev = ';'
			i++
		case c == '(':
			parens++
			prev = '('
			i++
		case c == ')':
			if parens > 0 {
				parens--
			}
			prev = ')'
			i++
		case c == '\\':
			i = min(i+2, n)
			prev = 'i'
		case isCSSIdentStart(c):
			start := i
			for i < n && isCSSIdentChar(css[i]) {
				i++
			}
			word := css[start:i]
			if i < n && css[i] == '(' && len(word) == 3 && strings.EqualFold(string(word), "url") {
				// An unquoted url( ... ) is one token: whatever is inside, however
				// it looks, is an address and not CSS. A quoted one is a string.
				if end, bare := skipBareURL(css, i); bare {
					i = end
					prev = 'i'
					continue
				}
			}
			if (prev == '{' || prev == ';') && parens == 0 && (declarations || depth > 0) &&
				len(word) == len("font-size") && strings.EqualFold(string(word), "font-size") {
				if from, to, replacement, ok := fontSizeValue(css, i); ok {
					if out == nil {
						out = make([]byte, 0, len(css)+16)
					}
					out = append(out, css[written:from]...)
					out = append(out, replacement...)
					written = to
					changed++
					i = to
				}
			}
			prev = 'i'
		default:
			prev = 'o'
			i++
		}
	}
	if changed == 0 {
		return css, 0
	}
	return append(out, css[written:]...), changed
}

// fontSizeValue reads the value of the font-size declaration whose name ends at
// css[i]: where the value is, and what replaces it. It answers false for a value
// that is not a lone absolute size, optionally followed by !important, and for one
// that is already relative.
func fontSizeValue(css []byte, i int) (from, to int, replacement string, ok bool) {
	n := len(css)
	j := skipCSSSpace(css, i)
	if j >= n || css[j] != ':' {
		return 0, 0, "", false
	}
	j = skipCSSSpace(css, j+1)
	from = j
	for j < n && !isCSSValueStop(css[j]) {
		j++
	}
	to = j
	if to == from {
		return 0, 0, "", false
	}
	// Only the value alone, then a declaration's end, or !important and then its
	// end. `font-size: medium foo` is not a font-size, and a value cut short by an
	// entity (`medium&#59;`) is left for whoever decodes it.
	k := skipCSSSpace(css, to)
	if k < n {
		switch css[k] {
		case ';', '}':
		case '!':
			k = skipCSSSpace(css, k+1)
			word := k
			for k < n && isCSSIdentChar(css[k]) {
				k++
			}
			if !strings.EqualFold(string(css[word:k]), "important") {
				return 0, 0, "", false
			}
			k = skipCSSSpace(css, k)
			if k < n && css[k] != ';' && css[k] != '}' {
				return 0, 0, "", false
			}
		default:
			return 0, 0, "", false
		}
	}
	replacement, ok = absoluteSizeAsRem(css[from:to])
	return from, to, replacement, ok
}

// absoluteSizeAsRem is the rem that an absolute size is, or false for a size that
// is not one: a keyword, or a number of px or pt. Zero is left as it is (it is the
// same size in any unit), and so is a negative size, which is no size at all.
func absoluteSizeAsRem(token []byte) (string, bool) {
	text := strings.ToLower(string(token))
	if rem, found := fontSizeKeywords[text]; found {
		return rem, true
	}
	i := 0
	if i < len(text) && text[i] == '+' {
		i++
	}
	start := i
	dots := 0
	for i < len(text) && (text[i] >= '0' && text[i] <= '9' || text[i] == '.') {
		if text[i] == '.' {
			dots++
		}
		i++
	}
	number, unit := text[start:i], text[i:]
	if number == "" || number == "." || dots > 1 {
		return "", false
	}
	var per float64
	switch unit {
	case "px":
		per = 16
	case "pt":
		per = 12
	default:
		return "", false
	}
	value, err := strconv.ParseFloat(number, 64)
	if err != nil || value <= 0 {
		return "", false
	}
	// Four places: 1px is 1/16 exactly, and a pt that does not divide (10pt) is
	// wrong by less than a thousandth of a pixel.
	rem := strconv.FormatFloat(value/per, 'f', 4, 64)
	rem = strings.TrimRight(strings.TrimRight(rem, "0"), ".")
	rem = strings.TrimPrefix(rem, "0")
	if rem == "" || rem == "." {
		// So small it rounds to nothing: leave it, rather than hide the text.
		return "", false
	}
	return rem + "rem", true
}

func isCSSSpace(c byte) bool { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' }

func isCSSIdentStart(c byte) bool {
	return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_' || c == '-' || c >= 0x80
}

func isCSSIdentChar(c byte) bool { return isCSSIdentStart(c) || c >= '0' && c <= '9' }

// isCSSValueStop ends a font-size value token: anything that could not be part of
// a keyword or a number with its unit.
func isCSSValueStop(c byte) bool {
	switch c {
	case ';', '}', '{', '!', '(', ')', '/', ',', '"', '\'', '\\':
		return true
	}
	return isCSSSpace(c)
}

// skipCSSSpace moves past space and comments.
func skipCSSSpace(css []byte, i int) int {
	for i < len(css) {
		switch {
		case isCSSSpace(css[i]):
			i++
		case css[i] == '/' && i+1 < len(css) && css[i+1] == '*':
			i = skipCSSComment(css, i)
		default:
			return i
		}
	}
	return i
}

func skipCSSComment(css []byte, i int) int {
	end := bytes.Index(css[i+2:], []byte("*/"))
	if end < 0 {
		return len(css)
	}
	return i + 2 + end + 2
}

// skipCSSString moves past the string that opens at css[i]. A newline ends a bad
// string, as it does in a browser.
func skipCSSString(css []byte, i int) int {
	quote := css[i]
	for i++; i < len(css); i++ {
		switch css[i] {
		case '\\':
			i++
		case quote:
			return i + 1
		case '\n', '\r', '\f':
			return i
		}
	}
	return len(css)
}

// skipBareURL moves past an unquoted url( ... ) whose "(" is at css[open]. bare is
// false when the address is quoted, which the caller then reads as a string.
func skipBareURL(css []byte, open int) (end int, bare bool) {
	j := skipCSSSpace(css, open+1)
	if j < len(css) && (css[j] == '"' || css[j] == '\'') {
		return open, false
	}
	for ; j < len(css); j++ {
		switch css[j] {
		case '\\':
			j++
		case ')':
			return j + 1, true
		}
	}
	return len(css), true
}
