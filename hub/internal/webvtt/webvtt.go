// Package webvtt turns a subtitle file into WebVTT (#45).
//
// An Apple download keeps its subtitles as WebVTT files beside the MP4 so the app
// can draw them with its own renderer and fetch a better one when Bazarr finds it.
// Subtitle files in a library are SRT almost always, ASS now and then and WebVTT
// rarely, in whatever state a release group left them. This is the one place that
// reads them, so every app gets the same text for the same file.
//
// It is a pure function of the text: no clock, no files, no network. Jellyfin has
// already turned the file into UTF-8 (a Hebrew SRT is commonly Windows-1255), so
// what arrives here is text, and what leaves is WebVTT whose cues are the file's
// own words in the file's own order. Right-to-left text is kept in logical order
// exactly as written: nothing is added, removed or reversed, because deciding how
// it is laid out is the renderer's job and the track's language says how.
package webvtt

import (
	"errors"
	"fmt"
	"html"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
)

// Version names the rules below. It is part of the signature of a track the hub
// does not read to sign, so changing what the same input turns into means bumping
// it, and every app then fetches the track again.
const Version = 1

// ErrNoCues means the text is not blank but holds no subtitle the converter can
// read: a file that is not subtitles at all, or one in a form it does not know.
var ErrNoCues = errors.New("webvtt: the file holds no subtitles")

// Format names what the input was.
type Format string

const (
	SRT   Format = "srt"
	ASS   Format = "ass"
	VTT   Format = "vtt"
	Blank Format = "blank"
)

// Result is a subtitle file as WebVTT.
type Result struct {
	// Text is the whole file, starting WEBVTT and ending in a newline.
	Text string
	// Cues is how many subtitles it holds.
	Cues int
	// Format is what the input was, read from its text and not from its name.
	Format Format
}

type cue struct {
	start, end int64 // milliseconds
	text       string
}

// Convert reads a subtitle file in any of SRT, ASS/SSA or WebVTT and writes WebVTT.
func Convert(input []byte) (Result, error) {
	text := tidy(input)
	format := detect(text)
	switch format {
	case Blank:
		return Result{Text: "WEBVTT\n", Format: Blank}, nil
	case VTT:
		return passThrough(text), nil
	}
	var cues []cue
	if format == ASS {
		cues = parseASS(text)
	} else {
		cues = parseSRT(text)
	}
	if len(cues) == 0 {
		return Result{}, ErrNoCues
	}
	sort.SliceStable(cues, func(i, j int) bool { return cues[i].start < cues[j].start })
	var out strings.Builder
	out.WriteString("WEBVTT\n\n")
	for _, c := range cues {
		fmt.Fprintf(&out, "%s --> %s\n%s\n\n", stamp(c.start), stamp(c.end), c.text)
	}
	return Result{Text: out.String(), Cues: len(cues), Format: format}, nil
}

// IsRTL says whether a language (the three-letter code an MP4 stores) is written
// right to left, so that an app lays a cue out that way.
func IsRTL(language string) bool {
	switch strings.ToLower(strings.TrimSpace(language)) {
	case "heb", "ara", "fas", "per", "urd", "yid", "pus", "snd", "uig", "div", "syr", "ckb",
		"he", "ar", "fa", "ur", "yi", "ps", "sd", "ug", "dv":
		return true
	}
	return false
}

// tidy is the text with its byte-order mark, stray bytes and odd line endings gone.
func tidy(input []byte) string {
	text := strings.ToValidUTF8(string(input), "\uFFFD")
	text = strings.TrimPrefix(text, "\uFEFF")
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	return strings.Map(func(r rune) rune {
		if r == '\n' || r == '\t' || !unicode.IsControl(r) {
			return r
		}
		return -1
	}, text)
}

var eventsSection = regexp.MustCompile(`(?im)^\s*\[events\]\s*$`)

// detect reads the format from the text: WebVTT starts with its signature, ASS and
// SSA have an [Events] section, and anything else with words in it is taken as SRT.
func detect(text string) Format {
	trimmed := strings.TrimLeft(text, " \t\n")
	if strings.TrimSpace(trimmed) == "" {
		return Blank
	}
	if strings.HasPrefix(trimmed, "WEBVTT") {
		rest := trimmed[len("WEBVTT"):]
		if rest == "" || rest[0] == ' ' || rest[0] == '\t' || rest[0] == '\n' {
			return VTT
		}
	}
	if eventsSection.MatchString(text) {
		return ASS
	}
	return SRT
}

// passThrough keeps a WebVTT file as it is: its cues, settings, styles, regions
// and notes. Only its line endings and its last newline are made regular.
func passThrough(text string) Result {
	text = strings.TrimLeft(text, " \t\n")
	text = strings.TrimRight(text, " \t\n") + "\n"
	cues := 0
	for _, line := range strings.Split(text, "\n") {
		if strings.Contains(line, "-->") {
			cues++
		}
	}
	return Result{Text: text, Cues: cues, Format: VTT}
}

// stamp writes a time the way WebVTT does: HH:MM:SS.mmm, with as many hours as it takes.
func stamp(ms int64) string {
	if ms < 0 {
		ms = 0
	}
	return fmt.Sprintf("%02d:%02d:%02d.%03d", ms/3_600_000, ms/60_000%60, ms/1000%60, ms%1000)
}

// milliseconds is a clock reading in a subtitle file. The fraction is a fraction:
// "5" is half a second and "50" is too, which is what ASS's centiseconds need.
func milliseconds(hours, minutes, seconds, fraction string) int64 {
	number := func(text string) int64 {
		value, _ := strconv.ParseInt(text, 10, 64)
		return value
	}
	for len(fraction) < 3 {
		fraction += "0"
	}
	return number(hours)*3_600_000 + number(minutes)*60_000 + number(seconds)*1000 + number(fraction[:3])
}

// ---- SRT ----

var srtTiming = regexp.MustCompile(
	`^\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[,.](\d{1,9})\s*-->\s*(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[,.](\d{1,9})`)

var indexLine = regexp.MustCompile(`^\s*\d+\s*$`)

func parseSRT(text string) []cue {
	lines := strings.Split(text, "\n")
	var cues []cue
	for at := 0; at < len(lines); at++ {
		m := srtTiming.FindStringSubmatch(lines[at])
		if m == nil {
			continue
		}
		start := milliseconds(m[1], m[2], m[3], m[4])
		end := milliseconds(m[5], m[6], m[7], m[8])
		var body []string
		next := at + 1
		for ; next < len(lines); next++ {
			line := lines[next]
			if strings.TrimSpace(line) == "" || srtTiming.MatchString(line) {
				break
			}
			// A cue's index sits on the line above its timing, and some files have no
			// blank line to say where the one before it ended.
			if indexLine.MatchString(line) && next+1 < len(lines) && srtTiming.MatchString(lines[next+1]) {
				break
			}
			body = append(body, line)
		}
		at = next - 1
		if end <= start {
			continue
		}
		if words := markupText(strings.Join(body, "\n")); words != "" {
			cues = append(cues, cue{start: start, end: end, text: words})
		}
	}
	return cues
}

var (
	overrideBlock = regexp.MustCompile(`\{\\[^{}\n]*\}`)
	tagPattern    = regexp.MustCompile(`^<(/?)([A-Za-z][A-Za-z0-9]*)(?:\s[^<>\n]*)?/?>`)
	entity        = regexp.MustCompile(`&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});`)
)

// markupText is the words of an SRT cue: italic, bold and underline stay, every other
// tag and any ASS override block goes, entities are decoded, and what WebVTT reads as
// markup is escaped so a cue that says "a < b" stays what it said.
func markupText(raw string) string {
	raw = overrideBlock.ReplaceAllString(raw, "")
	var out strings.Builder
	rest := raw
	for rest != "" {
		open := strings.IndexByte(rest, '<')
		if open < 0 {
			out.WriteString(escape(decode(rest)))
			break
		}
		out.WriteString(escape(decode(rest[:open])))
		found := tagPattern.FindStringSubmatch(rest[open:])
		if found == nil {
			out.WriteString("&lt;")
			rest = rest[open+1:]
			continue
		}
		switch name := strings.ToLower(found[2]); name {
		case "i", "b", "u":
			if found[1] == "/" {
				out.WriteString("</" + name + ">")
			} else {
				out.WriteString("<" + name + ">")
			}
		case "br":
			out.WriteString("\n")
		}
		rest = rest[open+len(found[0]):]
	}
	return tidyLines(out.String())
}

func decode(text string) string {
	return entity.ReplaceAllStringFunc(text, html.UnescapeString)
}

func escape(text string) string {
	text = strings.ReplaceAll(text, "&", "&amp;")
	text = strings.ReplaceAll(text, "<", "&lt;")
	return strings.ReplaceAll(text, ">", "&gt;")
}

// tidyLines tidies a cue's text: no blank lines (one would end the cue) and no space
// at either end of a line. A ">" in the words is already escaped, so the words cannot
// hold the "-->" that would read as a timing.
func tidyLines(text string) string {
	var kept []string
	for _, line := range strings.Split(text, "\n") {
		if line = strings.Trim(line, " \t"); line != "" {
			kept = append(kept, line)
		}
	}
	return strings.ReplaceAll(strings.Join(kept, "\n"), "-->", "--&gt;")
}

// ---- ASS and SSA ----

var (
	assTime        = regexp.MustCompile(`^(\d{1,3}):(\d{1,2}):(\d{1,2})[.,](\d{1,3})$`)
	drawingTag     = regexp.MustCompile(`\\p(\d+)`)
	defaultColumns = []string{"layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text"}
)

func parseASS(text string) []cue {
	var cues []cue
	inEvents := false
	columns := defaultColumns
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			inEvents = strings.EqualFold(line, "[events]")
			continue
		}
		if !inEvents {
			continue
		}
		lower := strings.ToLower(line)
		switch {
		case strings.HasPrefix(lower, "format:"):
			columns = columns[:0:0]
			for _, name := range strings.Split(line[len("format:"):], ",") {
				columns = append(columns, strings.ToLower(strings.TrimSpace(name)))
			}
		case strings.HasPrefix(lower, "dialogue:"):
			if parsed, ok := dialogue(strings.TrimSpace(line[len("dialogue:"):]), columns); ok {
				cues = append(cues, parsed)
			}
		}
	}
	return cues
}

// dialogue is one event. The text is the last column and may hold commas, so the
// line is split into no more fields than there are columns.
func dialogue(line string, columns []string) (cue, bool) {
	startAt, endAt, textAt := -1, -1, -1
	for at, name := range columns {
		switch name {
		case "start":
			startAt = at
		case "end":
			endAt = at
		case "text":
			textAt = at
		}
	}
	if startAt < 0 || endAt < 0 || textAt < 0 {
		return cue{}, false
	}
	fields := strings.SplitN(line, ",", len(columns))
	if len(fields) != len(columns) {
		return cue{}, false
	}
	start, startOK := assClock(fields[startAt])
	end, endOK := assClock(fields[endAt])
	if !startOK || !endOK || end <= start {
		return cue{}, false
	}
	words := tidyLines(escape(assText(fields[textAt])))
	if words == "" {
		return cue{}, false
	}
	return cue{start: start, end: end, text: words}, true
}

func assClock(text string) (int64, bool) {
	m := assTime.FindStringSubmatch(strings.TrimSpace(text))
	if m == nil {
		return 0, false
	}
	return milliseconds(m[1], m[2], m[3], m[4]), true
}

// assText is an event's words with its styling taken off: override blocks go, a
// drawing (\p1 ... \p0, which is a shape and not words) goes, \N is a line break,
// \n a space and \h a no-break space.
func assText(raw string) string {
	var out strings.Builder
	drawing := false
	for at := 0; at < len(raw); {
		switch c := raw[at]; {
		case c == '{':
			end := strings.IndexByte(raw[at:], '}')
			if end < 0 {
				if !drawing {
					out.WriteByte(c)
				}
				at++
				continue
			}
			for _, found := range drawingTag.FindAllStringSubmatch(raw[at+1:at+end], -1) {
				drawing = strings.Trim(found[1], "0") != ""
			}
			at += end + 1
		case c == '\\' && at+1 < len(raw) && (raw[at+1] == 'N' || raw[at+1] == 'n' || raw[at+1] == 'h'):
			if !drawing {
				switch raw[at+1] {
				case 'N':
					out.WriteByte('\n')
				case 'n':
					out.WriteByte(' ')
				default:
					out.WriteString("\u00A0")
				}
			}
			at += 2
		default:
			if !drawing {
				out.WriteByte(c)
			}
			at++
		}
	}
	return out.String()
}
