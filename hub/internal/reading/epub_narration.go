package reading

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"regexp"
	"strings"
)

// The reading copy of a read-along edition says in its SMIL what ReadAlignment
// reads from it (endAudioAt): a sentence that Storyteller's aligner placed past the
// end of its audio file, and wrote with that file's length as its end, is given no
// length (its clipEnd is its clipBegin), and one that runs over the end of the file
// ends there. Both apps read the edition's SMIL themselves, leave out a sentence
// of no length and refuse one that ends before it begins (A Clash of Kings has 3 in
// 28,409), so without this they could not read the edition the hub has mapped, and
// with it they read the same sentences at the same times the hub does. Nothing
// else in the SMIL changes, byte for byte.

// narrationMend is what the copy changes: where each audio file ends, and which
// entries are the edition's overlays.
type narrationMend struct {
	ends     map[string]int64
	overlays map[string]bool
}

// planNarrationMend reads the edition's narration and answers what its SMIL must
// say differently, or nil when nothing (an edition that is not read-along, one
// that cannot be read, one with no sentence past the end of its audio).
func planNarrationMend(src io.ReaderAt, size int64) *narrationMend {
	alignment, err := ReadAlignment(src, size)
	if err != nil || len(alignment.audioEnds) == 0 {
		return nil
	}
	return &narrationMend{ends: alignment.audioEnds, overlays: alignment.overlays}
}

// write rewrites one overlay into the archive. It answers false, having written
// nothing, for an entry that is not an overlay or holds nothing to change.
func (m *narrationMend) write(writer *zip.Writer, entry *zip.File, report *CopyReport) (bool, error) {
	if !m.overlays[entry.Name] {
		return false, nil
	}
	data, err := readXMLEntry(map[string]*zip.File{entry.Name: entry}, entry.Name)
	if err != nil {
		return false, nil
	}
	mended, count := mendSMIL(data, entry.Name, m.ends)
	if count == 0 {
		return false, nil
	}
	if err := writeRewritten(writer, entry, zip.Deflate, mended); err != nil {
		return false, err
	}
	report.Mended += count
	report.Edited++
	return true, nil
}

// A clipEnd attribute in an <audio> tag, with any prefix, and its quoted value.
var clipEndAttribute = regexp.MustCompile(`(\s(?:[A-Za-z_][\w.-]*:)?clipEnd\s*=\s*)("[^"]*"|'[^']*')`)

// mendSMIL changes the clipEnd of each sentence of one overlay that lies past the
// end of its audio file, as ReadAlignment reads the sentence: the <audio> that is a
// child of a <par> and the first one in it, its src resolved against the overlay.
// It answers the document and how many sentences it changed.
func mendSMIL(data []byte, smilPath string, ends map[string]int64) ([]byte, int) {
	type edit struct {
		from, to int
		tag      []byte
	}
	var edits []edit
	type par struct {
		depth int
		audio bool
	}
	var stack []*par
	decoder := xml.NewDecoder(bytes.NewReader(data))
	depth := 0
	for {
		before := int(decoder.InputOffset())
		token, err := decoder.Token()
		if err != nil {
			break
		}
		switch t := token.(type) {
		case xml.StartElement:
			depth++
			switch t.Name.Local {
			case "par":
				stack = append(stack, &par{depth: depth})
			case "audio":
				n := len(stack)
				if n == 0 || depth != stack[n-1].depth+1 || stack[n-1].audio {
					continue
				}
				stack[n-1].audio = true
				after := int(decoder.InputOffset())
				if tag, changed := mendClip(t, data[before:after], smilPath, ends); changed {
					edits = append(edits, edit{before, after, tag})
				}
			}
		case xml.EndElement:
			if n := len(stack); n > 0 && t.Name.Local == "par" && stack[n-1].depth == depth {
				stack = stack[:n-1]
			}
			depth--
		}
	}
	if len(edits) == 0 {
		return data, 0
	}
	var out bytes.Buffer
	at := 0
	for _, e := range edits {
		out.Write(data[at:e.from])
		out.Write(e.tag)
		at = e.to
	}
	out.Write(data[at:])
	return out.Bytes(), len(edits)
}

// mendClip is one <audio> tag (raw is its text) with the end it should have, and
// whether that is another.
func mendClip(audio xml.StartElement, raw []byte, smilPath string, ends map[string]int64) ([]byte, bool) {
	audioPath, _, ok := resolveRef(smilPath, attribute(audio, "src"))
	if !ok {
		return nil, false
	}
	end, ended := ends[audioPath]
	if !ended {
		return nil, false
	}
	beginText := strings.TrimSpace(attribute(audio, "clipBegin"))
	if beginText == "" {
		beginText = "0s"
	}
	begin, beginErr := parseClock(beginText)
	clipEnd, endErr := parseClock(attribute(audio, "clipEnd"))
	if beginErr != nil || endErr != nil {
		return nil, false
	}
	var value string
	switch {
	case clipEnd < begin || begin >= end:
		// Nothing of it can be heard: no length, which every reader leaves out.
		if clipEnd == begin {
			return nil, false
		}
		value = beginText
	case clipEnd > end:
		value = fmt.Sprintf("%d.%03ds", end/1000, end%1000)
	default:
		return nil, false
	}
	match := clipEndAttribute.FindSubmatchIndex(raw)
	if match == nil {
		return nil, false
	}
	tag := make([]byte, 0, len(raw)+8)
	tag = append(tag, raw[:match[3]]...)
	tag = append(tag, '"')
	tag = append(tag, value...)
	tag = append(tag, '"')
	tag = append(tag, raw[match[5]:]...)
	return tag, true
}
