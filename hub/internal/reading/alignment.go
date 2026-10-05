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
// and the slim EPUB, rest on it. It never reads the audio.

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

func badf(format string, args ...any) error {
	return fmt.Errorf("%w: "+format, append([]any{ErrBadAlignment}, args...)...)
}

// Alignment is what an edition says about its narration.
type Alignment struct {
	// Package is the zip path of the package document. Paths below are zip paths:
	// what the edition's own SMIL names after resolving it, which is also how the
	// app's reader names a text document in the locators it writes.
	Package string
	// Files are the audio files, in the order the narration first uses them.
	Files []AlignedFile

	finds    map[findKey]findLocation
	packages map[string]string // a text document's path inside the package folder -> its zip path
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
}

type findKey struct{ text, fragment string }

type findLocation struct {
	file int
	par  int
}

var chunkName = regexp.MustCompile(`^([0-9]{5})-([0-9]{5})\.[A-Za-z0-9]+$`)

// ReadAlignment reads the narration of an EPUB from its zip directory,
// container.xml, package document and SMIL files. The audio entries are named
// in the directory and never opened, so a 293 MB edition costs the 1.7 MB of
// text it holds.
//
// It mirrors how the app's own reader (ReadAlongPackage) takes the same file, so
// the two cannot disagree about what a sentence is: paths are resolved
// relative to the document that names them and must stay inside the
// archive, a sentence needs a fragment and both its resources must exist, a
// zero-length sentence is left out, and one that ends before it begins refuses
// the edition. Documents are read with a 4 MB cap and a DOCTYPE or entity
// declaration refuses them.
func ReadAlignment(file io.ReaderAt, size int64) (*Alignment, error) {
	archive, err := zip.NewReader(file, size)
	if err != nil {
		return nil, badf("the file is not a readable archive")
	}
	if len(archive.File) > maxEntries {
		return nil, badf("the archive holds too many entries")
	}
	entries := make(map[string]*zip.File, len(archive.File))
	for _, entry := range archive.File {
		entries[entry.Name] = entry
	}

	container, err := readXMLEntry(entries, "META-INF/container.xml")
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
	document, err := readXMLEntry(entries, packagePath)
	if err != nil {
		return nil, err
	}
	type item struct{ href, overlay string }
	items := map[string]item{}
	var spine []string
	if err := walkXML(document, func(start xml.StartElement, _ int) {
		switch start.Name.Local {
		case "item":
			items[attribute(start, "id")] = item{href: attribute(start, "href"), overlay: attribute(start, "media-overlay")}
		case "itemref":
			spine = append(spine, attribute(start, "idref"))
		}
	}); err != nil {
		return nil, err
	}

	alignment := &Alignment{Package: packagePath}
	fileIndex := map[string]int{}
	seenOverlay := map[string]bool{}
	pars := 0
	for _, reference := range spine {
		chapter, found := items[reference]
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
		smil, err := readXMLEntry(entries, smilPath)
		if err != nil {
			return nil, err
		}
		sentences, err := readSMIL(smil, smilPath, entries)
		if err != nil {
			return nil, err
		}
		for _, sentence := range sentences {
			pars++
			if pars > maxPars {
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
			if n := len(audio.Pars); n > 0 && sentence.begin < audio.Pars[n-1].BeginMs {
				return nil, badf("a file's sentences are not in order")
			}
			audio.Pars = append(audio.Pars, AlignedPar{Text: sentence.text, Fragment: sentence.fragment, BeginMs: sentence.begin, EndMs: sentence.end})
			audio.LengthMs = max(audio.LengthMs, sentence.end)
		}
	}
	if pars == 0 {
		return nil, ErrNoAlignment
	}
	alignment.index()
	return alignment, nil
}

type sentence struct {
	text, fragment, audio string
	begin, end            int64
}

// readSMIL takes the sentences of one overlay. A <par> is a sentence when it has
// a <text> and an <audio> among its own children.
func readSMIL(data []byte, smilPath string, entries map[string]*zip.File) ([]sentence, error) {
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
		if entries[textPath] == nil || entries[audioPath] == nil {
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
		switch {
		case end == begin:
			// Word-level alignment can emit a zero-length boundary for a word it
			// could not place. There is no audio to speak it.
		case end < begin:
			failure = badf("a sentence ends before it begins")
		default:
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

// AlignedSource is one of the book's files as the narration saw it: Storyteller
// numbers its files and cuts a long one into chunks, each of them an audio file
// in the edition.
type AlignedSource struct {
	Number int
	// Files are the indexes of its chunks in Alignment.Files, in chunk order, and
	// ChunkStartMs where each begins inside the source.
	Files        []int
	ChunkStartMs []int64
	LengthMs     int64
}

// Sources groups the audio files by the file of the book they are a piece of.
// A name that is not Storyteller's, a gap in the chunks, or a chunk with nothing
// narrated is not an alignment this can read.
func (a *Alignment) Sources() ([]AlignedSource, error) {
	if len(a.Files) == 0 {
		return nil, ErrNoAlignment
	}
	bySource := map[int][]int{}
	for index, file := range a.Files {
		// A name that is not Storyteller's leaves both zero. The chunks count from
		// one; the files may count from zero, as the chapters of an M4B do.
		if file.Chunk <= 0 {
			return nil, badf("an audio file is not named as the book's files are")
		}
		bySource[file.Source] = append(bySource[file.Source], index)
	}
	numbers := make([]int, 0, len(bySource))
	for number := range bySource {
		numbers = append(numbers, number)
	}
	sort.Ints(numbers)
	sources := make([]AlignedSource, 0, len(numbers))
	for _, number := range numbers {
		indexes := bySource[number]
		sort.Slice(indexes, func(i, j int) bool { return a.Files[indexes[i]].Chunk < a.Files[indexes[j]].Chunk })
		source := AlignedSource{Number: number, Files: indexes}
		for position, index := range indexes {
			file := a.Files[index]
			if file.Chunk != position+1 {
				return nil, badf("a file's chunks are not numbered one after another")
			}
			if file.LengthMs <= 0 {
				return nil, badf("a chunk narrates nothing")
			}
			source.ChunkStartMs = append(source.ChunkStartMs, source.LengthMs)
			source.LengthMs += file.LengthMs
		}
		sources = append(sources, source)
	}
	return sources, nil
}

// MatchSources pairs each narrated file with the file of the book it is, by
// length: a source's narrated length against a file's own, within 250 ms and 15
// ms for each chunk, since a chunk ends a few milliseconds short of the audio
// it was cut from (12 ms measured). The pairing must be complete and one to
// one, and it must be certain: two files whose lengths cannot tell the
// narration apart leave it ambiguous rather than guessed, because a wrong guess
// puts every sentence of the file in another one. The result is, for each source,
// the index of its file in durationsMs.
func MatchSources(sources []AlignedSource, durationsMs []int64) ([]int, error) {
	if len(sources) == 0 || len(sources) != len(durationsMs) {
		return nil, ErrAlignmentCount
	}
	candidates := make([][]int, len(sources))
	for i, source := range sources {
		tolerance := int64(250 + 15*len(source.Files))
		for j, duration := range durationsMs {
			difference := source.LengthMs - duration
			if difference < 0 {
				difference = -difference
			}
			if difference <= tolerance {
				candidates[i] = append(candidates[i], j)
			}
		}
		if len(candidates[i]) == 0 {
			return nil, ErrAlignmentMismatch
		}
	}
	assigned := make([]int, len(sources))
	for i := range assigned {
		assigned[i] = -1
	}
	taken := map[int]bool{}
	for changed := true; changed; {
		changed = false
		for i := range sources {
			if assigned[i] >= 0 {
				continue
			}
			remaining := candidates[i][:0:0]
			for _, j := range candidates[i] {
				if !taken[j] {
					remaining = append(remaining, j)
				}
			}
			candidates[i] = remaining
			switch len(remaining) {
			case 0:
				return nil, ErrAlignmentMismatch
			case 1:
				assigned[i], taken[remaining[0]] = remaining[0], true
				changed = true
			}
		}
		// A file that only one narration can be settles that narration, too.
		for j := range durationsMs {
			if taken[j] {
				continue
			}
			only, count := -1, 0
			for i := range sources {
				if assigned[i] >= 0 {
					continue
				}
				for _, candidate := range candidates[i] {
					if candidate == j {
						only, count = i, count+1
					}
				}
			}
			if count == 1 {
				assigned[only], taken[j] = j, true
				changed = true
			}
		}
	}
	for _, j := range assigned {
		if j < 0 {
			return nil, ErrAlignmentAmbiguous
		}
	}
	return assigned, nil
}
