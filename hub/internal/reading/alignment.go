package reading

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"math"
	"net/url"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// A read-along edition is an EPUB whose chapters carry SMIL media overlays: one
// <par> per sentence, naming the text (a document and the id of a span) and the
// audio (a file and the clip of it that speaks the sentence). That is the one
// exact account of where each sentence is in the audio, and everything here is
// reading it: the hub's conversions between a text place and an audio place,
// the slim EPUB and the book's own chapters (contents.go) rest on it. It never
// reads the audio.

var (
	// ErrBadAlignment is any reason the narration cannot be used: an archive that
	// is not one, a document that is not safe to read, a sentence that names
	// something that is not there, times that make no sense.
	ErrBadAlignment = errors.New("the edition's narration cannot be used")
	// ErrNoAlignment is the plainest of them: nothing in the edition is narrated.
	ErrNoAlignment = fmt.Errorf("%w: nothing in it is narrated", ErrBadAlignment)

	ErrAlignmentCount     = errors.New("the narrated files are not the book's files")
	ErrAlignmentMismatch  = errors.New("a narrated file is no length of any file of the book")
	ErrAlignmentAmbiguous = errors.New("narrated files cannot be told apart by their lengths")
)

// What the reader will hold. They are variables so a test can lower them.
var (
	// A SMIL or a package document larger than this is not one: the 293 MB
	// edition measured has 23 SMIL files of 75 KB.
	maxXMLBytes int64 = 4 << 20
	// The app caps its timeline at the same number.
	maxPars    = 200_000
	maxEntries = 100_000
)

// pastEndShare: more than one sentence in this many past the end of its audio is
// an edition whose times make no sense, not a few sentences at the end of a file
// that the aligner ran over (at most 12 in 23,737 in this library, The Will of the
// Many's).
const pastEndShare = 20

func badf(format string, args ...any) error {
	return fmt.Errorf("%w: "+format, append([]any{ErrBadAlignment}, args...)...)
}

// Alignment is what an edition says about its narration.
type Alignment struct {
	// Package is the zip path of the package document. Paths below are zip paths:
	// what the edition's own SMIL names after resolving it, which is also how the
	// app's reader names a text document in the locators it writes.
	Package string
	// Files are the audio files in the order they are narrated: by the number and
	// the chunk their names carry, and as the text first reaches them for names
	// that are not Storyteller's.
	Files []AlignedFile
	// Contents is the edition's table of contents, nested entries flattened in the
	// order it lists them. It is empty when the edition has none that can be read,
	// which is no reason to refuse its narration.
	Contents []ContentsEntry
	// PastEnd is how many sentences were left out, and CutAtEnd how many were ended
	// early, because they lie past the end of their audio file (endAudioAt).
	PastEnd, CutAtEnd int

	finds    map[findKey]findLocation
	packages map[string]string // a text document's path inside the package folder -> its zip path
	// spine is the package's text documents in reading order, narrated or not.
	spine []string
	// chapters are the contents' entries that are narrated, placed (contents.go).
	chapters []Chapter
	// audioEnds is where an audio file ends, by its zip path, as a sentence that
	// ends before it begins says (endAudioAt); overlays are the SMIL documents read.
	// The reading copy says the same in its SMIL (epub_narration.go).
	audioEnds map[string]int64
	overlays  map[string]bool
}

type AlignedFile struct {
	Entry string // zip path of the audio file
	// Source and Chunk are the NNNNN and CCCCC of Storyteller's NNNNN-CCCCC.ext
	// name: which of the book's files this is a piece of, and which piece. Zero
	// when the name is not that.
	Source, Chunk int
	// LengthMs is where its narration ends: its last clipEnd.
	LengthMs int64
	// Pars are its sentences, in narration order, which is begin order.
	Pars []AlignedPar
}

type AlignedPar struct {
	Text     string // zip path of the text document
	Fragment string // the id of the span in it
	BeginMs  int64
	EndMs    int64
	// seq is where the overlays list the sentence: the order of the text, which is
	// not always the order it is spoken in.
	seq int
}

type findKey struct{ text, fragment string }

type findLocation struct {
	file int
	par  int
}

var chunkName = regexp.MustCompile(`^([0-9]{5})-([0-9]{5})\.[A-Za-z0-9]+$`)

// packageItem is one item of the package's manifest: where it is, the overlay that
// narrates it, what it is and what the package says it is for (EPUB 3's "nav").
type packageItem struct{ href, overlay, mediaType, properties string }

// ReadAlignment reads the narration of an EPUB from its zip directory,
// container.xml, package document and SMIL files. The audio entries are named
// in the directory and never opened, so a 293 MB edition costs the 1.7 MB of
// text it holds.
//
// It mirrors how the app's own reader (ReadAlongPackage) takes the same file, so
// the two cannot disagree about what a sentence is: paths are resolved
// relative to the document that names them and must stay inside the
// archive, a sentence needs a fragment and both its resources must exist, and a
// zero-length sentence is left out. A sentence that ends before it begins marks
// where its audio file ends, and what lies past that is left out or ended there
// (endAudioAt); the hub's slim edition says the same in its SMIL
// (MendNarration), so the apps read the same sentences. Documents are read with
// a 4 MB cap and a DOCTYPE or entity
// declaration refuses them. Its overlays are listed in the order of the text, and
// the narration need not follow that order, so the sentences of each audio file
// are taken in the order they are spoken.
//
// It also reads the edition's table of contents, from its navigation document or
// its NCX, and places each entry on the narration (contents.go). That is an extra,
// never a condition: contents that are missing, unreadable or over a cap leave the
// narration as it is and the edition without chapters.
func ReadAlignment(file io.ReaderAt, size int64) (*Alignment, error) {
	return ReadOverlaidAlignment(file, size, nil)
}

// ReadOverlaidAlignment is ReadAlignment of the edition as a read-along pack's set rewrites it
// (#66): each document the overlay holds is read from it in place of the edition's, everything else
// from the edition. A word set narrates a <par> per word, so it may hold up to maxWordPars of them.
// An overlay that names a file the edition does not hold is refused (ErrOverlayMismatch).
func ReadOverlaidAlignment(file io.ReaderAt, size int64, overlay *Overlay) (*Alignment, error) {
	zipped, err := zip.NewReader(file, size)
	if err != nil {
		return nil, badf("the file is not a readable archive")
	}
	if len(zipped.File) > maxEntries {
		return nil, badf("the archive holds too many entries")
	}
	entries := archive{files: make(map[string]*zip.File, len(zipped.File)), overlay: overlay}
	for _, entry := range zipped.File {
		entries.files[entry.Name] = entry
	}
	limit := maxPars
	if overlay != nil {
		if err := overlay.fits(entries.has); err != nil {
			return nil, fmt.Errorf("%w: %w", ErrBadAlignment, err)
		}
		if overlay.Granularity == GranularityWord {
			limit = maxWordPars
		}
	}

	container, err := entries.xml("META-INF/container.xml")
	if err != nil {
		return nil, err
	}
	fullPath := ""
	if err := walkXML(container, func(start xml.StartElement, _ int) {
		if fullPath == "" && start.Name.Local == "rootfile" {
			fullPath = attribute(start, "full-path")
		}
	}); err != nil {
		return nil, err
	}
	packagePath, _, ok := resolveRef("", fullPath)
	if !ok {
		return nil, badf("the archive names no package")
	}
	document, err := entries.xml(packagePath)
	if err != nil {
		return nil, err
	}
	items := map[string]packageItem{}
	// The manifest in the order it lists its items, and the id of the NCX the spine
	// names: which of several navigation documents is the first, and which NCX is
	// the contents, must not depend on how a map happens to be walked.
	var manifestOrder []string
	var spine []string
	var ncxID string
	if err := walkXML(document, func(start xml.StartElement, _ int) {
		switch start.Name.Local {
		case "item":
			id := attribute(start, "id")
			items[id] = packageItem{
				href: attribute(start, "href"), overlay: attribute(start, "media-overlay"),
				mediaType: attribute(start, "media-type"), properties: attribute(start, "properties"),
			}
			manifestOrder = append(manifestOrder, id)
		case "spine":
			ncxID = attribute(start, "toc")
		case "itemref":
			spine = append(spine, attribute(start, "idref"))
		}
	}); err != nil {
		return nil, err
	}

	alignment := &Alignment{Package: packagePath}
	fileIndex := map[string]int{}
	seenOverlay := map[string]bool{}
	// audioEnds is where an audio file ends, as a sentence that ends before it
	// begins says (endAudioAt).
	audioEnds := map[string]int64{}
	pars := 0
	for _, reference := range spine {
		chapter, found := items[reference]
		if found {
			if text, _, ok := resolveRef(packagePath, chapter.href); ok {
				alignment.spine = append(alignment.spine, text)
			}
		}
		if !found || chapter.overlay == "" {
			continue
		}
		overlay, found := items[chapter.overlay]
		if !found {
			return nil, badf("a chapter names a media overlay the package does not list")
		}
		smilPath, _, ok := resolveRef(packagePath, overlay.href)
		if !ok {
			return nil, badf("a media overlay is not inside the archive")
		}
		if seenOverlay[smilPath] {
			continue
		}
		seenOverlay[smilPath] = true
		smil, err := entries.xml(smilPath)
		if err != nil {
			return nil, err
		}
		sentences, err := readSMIL(smil, smilPath, entries)
		if err != nil {
			return nil, err
		}
		for _, sentence := range sentences {
			if sentence.end < sentence.begin {
				// Where its audio ends; the earliest, should a file have two.
				if end, seen := audioEnds[sentence.audio]; !seen || sentence.end < end {
					audioEnds[sentence.audio] = sentence.end
				}
				alignment.PastEnd++
				continue
			}
			pars++
			if pars > limit {
				return nil, badf("the narration holds too many sentences")
			}
			index, known := fileIndex[sentence.audio]
			if !known {
				index = len(alignment.Files)
				fileIndex[sentence.audio] = index
				audioFile := AlignedFile{Entry: sentence.audio}
				if match := chunkName.FindStringSubmatch(path.Base(sentence.audio)); match != nil {
					audioFile.Source, _ = strconv.Atoi(match[1])
					audioFile.Chunk, _ = strconv.Atoi(match[2])
				}
				alignment.Files = append(alignment.Files, audioFile)
			}
			audio := &alignment.Files[index]
			audio.Pars = append(audio.Pars, AlignedPar{Text: sentence.text, Fragment: sentence.fragment, BeginMs: sentence.begin, EndMs: sentence.end, seq: pars})
			audio.LengthMs = max(audio.LengthMs, sentence.end)
		}
	}
	alignment.audioEnds, alignment.overlays = audioEnds, seenOverlay
	pars -= alignment.endAudioAt(audioEnds)
	if pars <= 0 {
		return nil, ErrNoAlignment
	}
	if (alignment.PastEnd+alignment.CutAtEnd)*pastEndShare > pars+alignment.PastEnd {
		return nil, badf("sentences end before they begin")
	}
	// The overlays are listed in the order of the text, and the narration is in the
	// order it is spoken. They usually agree and need not: Mistborn's lists a short
	// chapter among its front matter and speaks it in the middle of the second audio
	// file. So each file's sentences are put in the order they are spoken, which
	// their own times say, and the files in the order they are narrated.
	for i := range alignment.Files {
		pars := alignment.Files[i].Pars
		sort.SliceStable(pars, func(a, b int) bool { return pars[a].BeginMs < pars[b].BeginMs })
	}
	sort.SliceStable(alignment.Files, func(a, b int) bool {
		left, right := alignment.Files[a], alignment.Files[b]
		if left.Source != right.Source {
			return left.Source < right.Source
		}
		return left.Chunk < right.Chunk
	})
	alignment.index()
	// The book's own chapters are an addition to the narration, never a condition of
	// it: an edition whose contents cannot be read is still a read-along edition.
	alignment.Contents = readContents(entries, packagePath, items, manifestOrder, ncxID)
	alignment.chapters = alignment.placeChapters(entries)
	return alignment, nil
}

// endAudioAt ends each audio file where a sentence that ends before it begins says
// it ends. Storyteller's aligner can run past the end of a file at the end of a
// chapter, and it then gives what lies past the end the file's own length as its
// end: the last sentence of that run begins after it, and so ends before it
// begins. Measured on all 14 such sentences in this library (8 books, A Clash of
// Kings and The Will of the Many among them), each one's end is the length of its
// audio, to the millisecond on the MP4 files and within 41 ms on the MP3s (as
// ffprobe estimates them), and the one to four sentences before it run up to 18.5 s
// past that. Nothing past the end can be heard: a sentence that begins there is
// left out and one that runs over it ends there, so a file's narrated length is its
// length and no sentence is placed in the next file's audio. It answers how many
// sentences it left out.
func (a *Alignment) endAudioAt(ends map[string]int64) int {
	left := 0
	kept := a.Files[:0]
	for _, file := range a.Files {
		end, ended := ends[file.Entry]
		if !ended {
			kept = append(kept, file)
			continue
		}
		pars := file.Pars[:0]
		file.LengthMs = 0
		for _, par := range file.Pars {
			if par.BeginMs >= end {
				a.PastEnd++
				left++
				continue
			}
			if par.EndMs > end {
				par.EndMs = end
				a.CutAtEnd++
			}
			pars = append(pars, par)
			file.LengthMs = max(file.LengthMs, par.EndMs)
		}
		file.Pars = pars
		if len(pars) > 0 {
			kept = append(kept, file)
		}
	}
	a.Files = kept
	return left
}

type sentence struct {
	text, fragment, audio string
	begin, end            int64
}

// readSMIL takes the sentences of one overlay. A <par> is a sentence when it has
// a <text> and an <audio> among its own children.
func readSMIL(data []byte, smilPath string, entries archive) ([]sentence, error) {
	type par struct {
		depth       int
		text, audio *xml.StartElement
	}
	var out []sentence
	var stack []*par
	var failure error
	finish := func(p *par) {
		if failure != nil || p.text == nil || p.audio == nil {
			return
		}
		textPath, fragment, ok := resolveRef(smilPath, attribute(*p.text, "src"))
		audioPath, _, audioOK := resolveRef(smilPath, attribute(*p.audio, "src"))
		if !ok || !audioOK {
			failure = badf("a sentence names a resource outside the archive")
			return
		}
		if fragment == "" {
			failure = badf("a sentence names no place in its text")
			return
		}
		if !entries.has(textPath) || !entries.has(audioPath) {
			failure = badf("a sentence names a resource the archive does not hold")
			return
		}
		beginText := attribute(*p.audio, "clipBegin")
		if strings.TrimSpace(beginText) == "" {
			beginText = "0s"
		}
		begin, err := parseClock(beginText)
		if err != nil {
			failure = badf("a sentence begins at a time that is not one")
			return
		}
		end, err := parseClock(attribute(*p.audio, "clipEnd"))
		if err != nil {
			failure = badf("a sentence ends at a time that is not one")
			return
		}
		// Word-level alignment can emit a zero-length boundary for a word it could
		// not place. There is no audio to speak it. One that ends before it begins
		// is kept here and set aside by ReadAlignment: its end says where its audio
		// file ends (endAudioAt).
		if end != begin {
			out = append(out, sentence{text: textPath, fragment: fragment, audio: audioPath, begin: begin, end: end})
		}
	}
	err := walkXMLTokens(data, func(token xml.Token, depth int) {
		switch t := token.(type) {
		case xml.StartElement:
			switch t.Name.Local {
			case "par":
				stack = append(stack, &par{depth: depth})
			case "text", "audio":
				if n := len(stack); n > 0 && depth == stack[n-1].depth+1 {
					top := stack[n-1]
					copied := t.Copy()
					if t.Name.Local == "text" && top.text == nil {
						top.text = &copied
					}
					if t.Name.Local == "audio" && top.audio == nil {
						top.audio = &copied
					}
				}
			}
		case xml.EndElement:
			if n := len(stack); n > 0 && t.Name.Local == "par" && stack[n-1].depth == depth {
				finish(stack[n-1])
				stack = stack[:n-1]
			}
		}
	})
	if err != nil {
		return nil, err
	}
	return out, failure
}

func attribute(element xml.StartElement, name string) string {
	for _, attr := range element.Attr {
		if attr.Name.Local == name {
			return attr.Value
		}
	}
	return ""
}

// archive is an edition's entries by name, with what a read-along pack's set puts in place of some
// of them (#66): the one view of the edition everything that reads its narration goes through.
type archive struct {
	files   map[string]*zip.File
	overlay *Overlay
}

func (a archive) has(name string) bool { return a.files[name] != nil }

// size is what the entry holds unpacked, the overlay's when it replaces it.
func (a archive) size(name string) int64 {
	if a.overlay.Has(name) {
		return a.overlay.size(name)
	}
	if file := a.files[name]; file != nil {
		return int64(file.UncompressedSize64)
	}
	return 0
}

// xml reads one document, from the overlay when it replaces it, within the cap.
func (a archive) xml(name string) ([]byte, error) {
	if a.overlay.Has(name) && a.has(name) {
		return a.overlay.read(name)
	}
	return readXMLEntry(a.files, name)
}

// readXMLEntry reads one document of the archive, within the cap.
func readXMLEntry(entries map[string]*zip.File, name string) ([]byte, error) {
	entry := entries[name]
	if entry == nil {
		return nil, badf("a document the edition needs is not in it")
	}
	if entry.UncompressedSize64 > uint64(maxXMLBytes) {
		return nil, badf("a document is larger than a narration document can be")
	}
	stream, err := entry.Open()
	if err != nil {
		return nil, badf("a document cannot be opened")
	}
	defer stream.Close()
	data, err := io.ReadAll(io.LimitReader(stream, maxXMLBytes+1))
	if err != nil || int64(len(data)) > maxXMLBytes {
		return nil, badf("a document cannot be read within its size")
	}
	return data, nil
}

// walkXML visits each start element with its depth. A DOCTYPE or an entity
// declaration is refused outright: none of these documents has a use for one,
// and a document that declares entities is trying something.
func walkXML(data []byte, visit func(start xml.StartElement, depth int)) error {
	return walkXMLTokens(data, func(token xml.Token, depth int) {
		if start, ok := token.(xml.StartElement); ok {
			visit(start, depth)
		}
	})
}

func walkXMLTokens(data []byte, visit func(token xml.Token, depth int)) error {
	decoder := xml.NewDecoder(bytes.NewReader(data))
	// No Entity map and no CharsetReader: only the five predefined entities, and
	// only UTF-8 (or US-ASCII), as these documents are.
	depth := 0
	for {
		token, err := decoder.Token()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return badf("a document is not well-formed XML")
		}
		switch token.(type) {
		case xml.Directive:
			return badf("a document declares a DTD or an entity")
		case xml.StartElement:
			depth++
			visit(token, depth)
		case xml.EndElement:
			visit(token, depth)
			depth--
		default:
			visit(token, depth)
		}
	}
}

// resolveRef resolves a reference against the archive path of the document that
// holds it, as a URL reference: it must stay a plain path inside the archive,
// with no address, no query and no way up and out. The fragment comes back
// separately.
func resolveRef(base, reference string) (resolved, fragment string, ok bool) {
	if strings.TrimSpace(reference) == "" || strings.Contains(reference, "\\") {
		return "", "", false
	}
	parsed, err := url.Parse(strings.ReplaceAll(reference, " ", "%20"))
	if err != nil || parsed.IsAbs() || parsed.Host != "" || parsed.RawQuery != "" || parsed.ForceQuery || strings.HasPrefix(parsed.Path, "/") {
		return "", "", false
	}
	joined := path.Join(path.Dir(base), parsed.Path)
	if joined == "." || joined == ".." || strings.HasPrefix(joined, "../") || strings.HasPrefix(joined, "/") || strings.Contains(joined, "\\") {
		return "", "", false
	}
	return joined, parsed.Fragment, true
}

// parseClock reads a SMIL clock value: 12.5s, 12.5, 1500ms, 1.5min, 0.5h, 0:12.5,
// 1:02:03.5, each as written in a timecount or a clock, and an npt= prefix.
func parseClock(raw string) (int64, error) {
	text := strings.TrimPrefix(strings.TrimSpace(raw), "npt=")
	number := func(value string) (float64, error) {
		parsed, err := strconv.ParseFloat(value, 64)
		if err != nil || math.IsNaN(parsed) || math.IsInf(parsed, 0) || parsed < 0 {
			return 0, errors.New("not a time")
		}
		return parsed, nil
	}
	var seconds float64
	var err error
	switch {
	case strings.Contains(text, ":"):
		parts := strings.Split(text, ":")
		if len(parts) < 2 || len(parts) > 3 {
			return 0, errors.New("not a time")
		}
		for _, part := range parts {
			value, partErr := number(part)
			if partErr != nil {
				return 0, partErr
			}
			seconds = seconds*60 + value
		}
	case strings.HasSuffix(text, "ms"):
		seconds, err = number(strings.TrimSuffix(text, "ms"))
		seconds /= 1000
	case strings.HasSuffix(text, "min"):
		seconds, err = number(strings.TrimSuffix(text, "min"))
		seconds *= 60
	case strings.HasSuffix(text, "h"):
		seconds, err = number(strings.TrimSuffix(text, "h"))
		seconds *= 3600
	default:
		seconds, err = number(strings.TrimSuffix(text, "s"))
	}
	if err != nil || seconds >= 365*24*3600 {
		return 0, errors.New("not a time")
	}
	return int64(math.Round(seconds * 1000)), nil
}

// SamePieces says whether b narrates the same audio pieces as a, each ending (its last clipEnd,
// which is what the hub measures a piece by) at the same moment: what lets a read-along pack's set
// stand on the mapping of the edition's pieces onto the tracks (#66).
func (a *Alignment) SamePieces(b *Alignment) bool {
	if a == nil || b == nil || len(a.Files) != len(b.Files) {
		return false
	}
	lengths := make(map[string]int64, len(a.Files))
	for _, file := range a.Files {
		lengths[file.Entry] = file.LengthMs
	}
	for _, file := range b.Files {
		if length, found := lengths[file.Entry]; !found || length != file.LengthMs {
			return false
		}
	}
	return true
}

// index prepares the lookups from a place in the text.
func (a *Alignment) index() {
	a.finds = map[findKey]findLocation{}
	a.packages = map[string]string{}
	dir := path.Dir(a.Package)
	for fileIndex, file := range a.Files {
		for parIndex, par := range file.Pars {
			key := findKey{par.Text, par.Fragment}
			if _, taken := a.finds[key]; !taken {
				a.finds[key] = findLocation{fileIndex, parIndex}
			}
			if dir != "." {
				if relative, found := strings.CutPrefix(par.Text, dir+"/"); found {
					a.packages[relative] = par.Text
				}
			} else {
				a.packages[par.Text] = par.Text
			}
		}
	}
}

// Sentence is the sentence being spoken at a time in an audio file: the last one
// that has begun. Before the first it is the first, and after the last, the last.
func (a *Alignment) Sentence(file int, ms int64) (AlignedPar, bool) {
	if file < 0 || file >= len(a.Files) || len(a.Files[file].Pars) == 0 {
		return AlignedPar{}, false
	}
	pars := a.Files[file].Pars
	begun := sort.Search(len(pars), func(i int) bool { return pars[i].BeginMs > ms })
	if begun == 0 {
		return pars[0], true
	}
	return pars[begun-1], true
}

// Find is where a sentence of the text is spoken. The href is a text document as a
// locator spells it: a zip path (as the app's reader writes it), the same with a
// leading slash, or a path relative to the package (as Storyteller's own apps
// write it), percent-encoded or not, and the fragment may ride on it.
func (a *Alignment) Find(href, fragment string) (file int, par AlignedPar, ok bool) {
	href, inline := splitHref(href)
	if fragment == "" {
		fragment = inline
	}
	if href == "" || fragment == "" {
		return 0, AlignedPar{}, false
	}
	location, found := a.finds[findKey{href, fragment}]
	if !found {
		if zipPath, relative := a.packages[href]; relative {
			location, found = a.finds[findKey{zipPath, fragment}]
		}
	}
	if !found {
		return 0, AlignedPar{}, false
	}
	return location.file, a.Files[location.file].Pars[location.par], true
}

func splitHref(href string) (document, fragment string) {
	document, fragment, _ = strings.Cut(strings.TrimSpace(href), "#")
	if decoded, err := url.PathUnescape(document); err == nil {
		document = decoded
	}
	if decoded, err := url.PathUnescape(fragment); err == nil {
		fragment = decoded
	}
	document = strings.TrimLeft(document, "/")
	if document == "" {
		return "", fragment
	}
	document = path.Clean(document)
	if document == "." || document == ".." || strings.HasPrefix(document, "../") {
		return "", fragment
	}
	return document, fragment
}
