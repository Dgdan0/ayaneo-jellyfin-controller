package reading

// The book's own chapters. A read-along edition's table of contents names its
// chapters and its narration says where each sentence is spoken, so the two say
// where each chapter is spoken, which is more than the audiobook's own files do:
// Dark Matter is eight parts of about 75 minutes and The Final Empire two of
// 12 h 20 min, none with a chapter mark inside. This file reads the contents
// (the EPUB 3 navigation document, else the NCX), finds the first narrated
// sentence of each entry, and leaves the audio alone. Which track and moment
// that sentence is in is the hub's to say (internal/api), since only it knows
// how the narrated files lie among the book's.
//
// What it reads besides the narration's own documents: the navigation document
// or the NCX, and, for an entry that is not the first into its document and
// points at an anchor no sentence carries (a heading's id), the one text document
// the anchor is in, only to learn where the anchor stands among the sentences.
// Each is read within the same size cap, and a document that is not well-formed
// or declares a DTD gives no contents.

import (
	"bytes"
	"encoding/xml"
	"sort"
	"strings"
)

// What the reader will hold of a table of contents. They are variables so a test
// can lower them.
var (
	// Real books list a few hundred entries.
	maxContents = 5_000
	// A title is a line of a contents; one longer than this is a paragraph.
	maxTitleRunes = 200
	// The text documents read to place anchors, all together: a contents that points
	// into thousands of large documents is not one to follow to the end. Mistborn's
	// forty chapters are 2 MB.
	maxTextBytes int64 = 64 << 20
)

// ContentsEntry is one line of an edition's table of contents.
type ContentsEntry struct {
	Title string
	// Document is the zip path of the text document the entry points into, and
	// Fragment the id of the place in it, or "" for the document as a whole.
	Document string
	Fragment string
}

// Chapter is a line of the contents that is narrated: the sentence its narration
// begins with and the audio file it is spoken in.
type Chapter struct {
	// Entry is the line's place in Alignment.Contents.
	Entry int
	Title string
	// File is the audio file, as an index of Alignment.Files, and Par the sentence
	// in it; Par.BeginMs is where in the file the chapter begins.
	File int
	Par  AlignedPar
}

// Chapters are the entries of the contents that are narrated, in the order the
// contents lists them. Entries with nothing narrated (a cover, a copyright page)
// are not among them. They are not put in the order they are heard in either:
// that is the order of the tracks the audio files are matched to, which this
// reader does not know. The slice is shared; do not change it.
//
// An entry begins where a narrated sentence of its own document is spoken. The
// first entry into a document has all of it from the top, wherever its anchor is:
// what is spoken ahead of its heading, such as Mistborn's epigraph, is its
// chapter's. The entries after it in the same document begin at the first narrated
// sentence at or after the id they name (an anchor, or a sentence itself), since
// what lies between two anchors belongs to the earlier. When the entry's own
// document has nothing narrated the next documents are looked through, up to the
// next one another entry points at, because a chapter's heading may be a picture
// in a document of its own with the words in the document after it (Dark Matter's
// chapters ten and eleven, whose headings are documents of 805 and 811 bytes
// holding one picture).
func (a *Alignment) Chapters() []Chapter { return a.chapters }

// packageNavProperty is how an EPUB 3 package marks its navigation document.
const packageNavProperty = "nav"

// ncxMediaType is what an EPUB 2 package calls its NCX.
const ncxMediaType = "application/x-dtbncx+xml"

// readContents reads the table of contents the package names: its EPUB 3
// navigation document, else its NCX. An edition that has neither, or whose
// contents cannot be read, has none: that is not an error of its narration.
func readContents(entries archive, packagePath string, items map[string]packageItem, order []string, ncxID string) []ContentsEntry {
	read := func(item packageItem, parse func([]byte, string) ([]ContentsEntry, error)) []ContentsEntry {
		documentPath, _, ok := resolveRef(packagePath, item.href)
		if !ok {
			return nil
		}
		data, err := entries.xml(documentPath)
		if err != nil {
			return nil
		}
		contents, err := parse(data, documentPath)
		if err != nil {
			return nil
		}
		return contents
	}
	for _, id := range order {
		if item := items[id]; hasToken(item.properties, packageNavProperty) {
			if contents := read(item, parseNav); len(contents) > 0 {
				return contents
			}
			break
		}
	}
	ncx, found := items[ncxID]
	if ncxID == "" || !found {
		found = false
		for _, id := range order {
			if items[id].mediaType == ncxMediaType {
				ncx, found = items[id], true
				break
			}
		}
	}
	if !found {
		return nil
	}
	return read(ncx, parseNCX)
}

// hasToken says whether a space-separated attribute value (epub:type, properties)
// holds a word.
func hasToken(list, word string) bool {
	for _, token := range strings.Fields(list) {
		if token == word {
			return true
		}
	}
	return false
}

// parseNav reads the navigation document's table of contents: the nav whose
// epub:type holds "toc", each of its links in the order they are written, so a
// nested entry follows the one it is under. Its label is all the text inside the
// link (and the alt text of a picture, for a book that names its chapters so).
// An entry with no link, a heading that only groups others, has no place to
// point at and is not an entry.
func parseNav(data []byte, navPath string) ([]ContentsEntry, error) {
	type link struct {
		href  string
		depth int
		label strings.Builder
	}
	var (
		entries  []ContentsEntry
		inside   bool
		finished bool
		navDepth int
		current  *link
		tooMany  bool
	)
	err := walkXMLTokens(data, func(token xml.Token, depth int) {
		if finished || tooMany {
			return
		}
		switch t := token.(type) {
		case xml.StartElement:
			switch t.Name.Local {
			case "nav":
				if !inside && hasToken(attribute(t, "type"), "toc") {
					inside, navDepth = true, depth
				}
			case "a":
				if inside && current == nil {
					current = &link{href: attribute(t, "href"), depth: depth}
				}
			case "img":
				if current != nil {
					current.label.WriteString(" " + attribute(t, "alt") + " ")
				}
			}
		case xml.CharData:
			if current != nil {
				current.label.Write(t)
			}
		case xml.EndElement:
			switch {
			case current != nil && t.Name.Local == "a" && depth == current.depth:
				if document, fragment, ok := resolveRef(navPath, current.href); ok {
					if len(entries) >= maxContents {
						tooMany = true
						return
					}
					entries = append(entries, ContentsEntry{Title: cleanTitle(current.label.String()), Document: document, Fragment: fragment})
				}
				current = nil
			case inside && t.Name.Local == "nav" && depth == navDepth:
				finished = true
			}
		}
	})
	if err != nil {
		return nil, err
	}
	if tooMany {
		return nil, badf("the contents hold too many entries")
	}
	return entries, nil
}

// parseNCX reads an NCX's navMap: each navPoint, parent before the points inside
// it, with the first label it carries and the place its content names. A point
// with no place is left out, and its children are not.
func parseNCX(data []byte, ncxPath string) ([]ContentsEntry, error) {
	type point struct {
		index int
		title strings.Builder
		src   string
		// labelled is set once the point's first label has been read: later ones are
		// the same title in another language.
		labelled bool
	}
	var (
		entries  []ContentsEntry
		valid    []bool
		stack    []*point
		inMap    bool
		inLabel  bool
		inText   bool
		finished bool
		tooMany  bool
	)
	err := walkXMLTokens(data, func(token xml.Token, depth int) {
		if finished || tooMany {
			return
		}
		switch t := token.(type) {
		case xml.StartElement:
			switch t.Name.Local {
			case "navMap":
				inMap = true
			case "navPoint":
				if !inMap {
					return
				}
				if len(entries) >= maxContents {
					tooMany = true
					return
				}
				stack = append(stack, &point{index: len(entries)})
				entries = append(entries, ContentsEntry{})
				valid = append(valid, false)
			case "navLabel":
				if n := len(stack); n > 0 && !stack[n-1].labelled {
					inLabel = true
				}
			case "text":
				if inLabel {
					inText = true
				}
			case "content":
				if n := len(stack); n > 0 && stack[n-1].src == "" {
					stack[n-1].src = attribute(t, "src")
				}
			}
		case xml.CharData:
			if inText {
				stack[len(stack)-1].title.Write(t)
			}
		case xml.EndElement:
			switch t.Name.Local {
			case "text":
				inText = false
			case "navLabel":
				if inLabel {
					inLabel = false
					stack[len(stack)-1].labelled = true
				}
			case "navPoint":
				if n := len(stack); inMap && n > 0 {
					top := stack[n-1]
					stack = stack[:n-1]
					if document, fragment, ok := resolveRef(ncxPath, top.src); ok {
						entries[top.index] = ContentsEntry{Title: cleanTitle(top.title.String()), Document: document, Fragment: fragment}
						valid[top.index] = true
					}
				}
			case "navMap":
				finished = true
			}
		}
	})
	if err != nil {
		return nil, err
	}
	if tooMany {
		return nil, badf("the contents hold too many entries")
	}
	kept := entries[:0]
	for i, entry := range entries {
		if valid[i] {
			kept = append(kept, entry)
		}
	}
	return kept, nil
}

// cleanTitle is a label as one line: its white space collapsed, and cut where it
// stops being a title.
func cleanTitle(raw string) string {
	title := strings.Join(strings.Fields(raw), " ")
	if runes := []rune(title); len(runes) > maxTitleRunes {
		title = string(runes[:maxTitleRunes])
	}
	return title
}

// placeChapters finds where each entry of the contents begins (see Chapters): the
// top of its document when it is the first entry into one that is narrated, else
// the first narrated sentence at or after the id it names, and when its own
// document has nothing to begin with, the first narrated sentence of the documents
// after it that no entry points at. An entry with none is not narrated and has no
// chapter.
func (a *Alignment) placeChapters(entries archive) []Chapter {
	if len(a.Contents) == 0 {
		return nil
	}
	// The documents entries point into, which entry is the first into each, and the
	// anchors the entries after it name: the first begins at the top of its document
	// and needs none of them placed.
	targets := map[string]bool{}
	firstInto := map[string]int{}
	anchors := map[string][]string{}
	for index, entry := range a.Contents {
		targets[entry.Document] = true
		if _, seen := firstInto[entry.Document]; !seen {
			firstInto[entry.Document] = index
		} else if entry.Fragment != "" {
			anchors[entry.Document] = append(anchors[entry.Document], entry.Fragment)
		}
	}
	spineAt := make(map[string]int, len(a.spine))
	for i, document := range a.spine {
		if _, seen := spineAt[document]; !seen {
			spineAt[document] = i
		}
	}
	// Each document's sentences in the order of the text, which is the order the
	// overlays list them in and not always the order they are spoken in.
	sentences := map[string][]findLocation{}
	for fileIndex, file := range a.Files {
		for parIndex, par := range file.Pars {
			sentences[par.Text] = append(sentences[par.Text], findLocation{fileIndex, parIndex})
		}
	}
	for _, list := range sentences {
		sort.SliceStable(list, func(i, j int) bool { return a.par(list[i]).seq < a.par(list[j]).seq })
	}

	// For each place in the reading order, the first narrated sentence of the
	// documents after it, up to the next that an entry points at: where an entry
	// begins when nothing in its own document is narrated. One pass, so an edition
	// with thousands of entries and documents is no slower than one with a few.
	following := make([]findLocation, len(a.spine))
	hasFollowing := make([]bool, len(a.spine))
	for i := len(a.spine) - 2; i >= 0; i-- {
		next := a.spine[i+1]
		switch {
		case targets[next]:
		case len(sentences[next]) > 0:
			following[i], hasFollowing[i] = sentences[next][0], true
		default:
			following[i], hasFollowing[i] = following[i+1], hasFollowing[i+1]
		}
	}

	// Where the anchors and the sentences of a document stand among its elements,
	// each document read at most once and only so much text in all. The sentences
	// are kept in the order they stand in, so the first at or after an anchor is a
	// search and not a walk: a book in one document has an anchor for each chapter.
	type standing struct {
		at  int
		loc findLocation
	}
	type text struct {
		anchors   map[string]int
		sentences []standing
	}
	scanned := map[string]*text{}
	budget := maxTextBytes
	positions := func(document string) *text {
		if found, done := scanned[document]; done {
			return found
		}
		scanned[document] = nil
		if !entries.has(document) || entries.size(document) > budget {
			return nil
		}
		data, err := entries.xml(document)
		if err != nil {
			return nil
		}
		budget -= int64(len(data))
		want := map[string]struct{}{}
		for _, fragment := range anchors[document] {
			want[fragment] = struct{}{}
		}
		for _, loc := range sentences[document] {
			want[a.par(loc).Fragment] = struct{}{}
		}
		stands := scanIDs(data, want)
		placed := &text{anchors: map[string]int{}}
		for _, fragment := range anchors[document] {
			if at, found := stands[fragment]; found {
				placed.anchors[fragment] = at
			}
		}
		for _, loc := range sentences[document] {
			if at, found := stands[a.par(loc).Fragment]; found {
				placed.sentences = append(placed.sentences, standing{at, loc})
			}
		}
		sort.SliceStable(placed.sentences, func(i, j int) bool { return placed.sentences[i].at < placed.sentences[j].at })
		scanned[document] = placed
		return placed
	}

	begin := func(index int, entry ContentsEntry) (findLocation, bool) {
		own := sentences[entry.Document]
		if len(own) > 0 {
			// The first entry into a document has it from the top, wherever its anchor is:
			// what is spoken ahead of the heading (Mistborn's epigraph, which comes before
			// the "2") is its chapter's. So is an entry that names no place.
			if entry.Fragment == "" || firstInto[entry.Document] == index {
				return own[0], true
			}
			// The entries after it begin where they point, since what lies between two
			// anchors belongs to the earlier. One that names a sentence begins there.
			if loc, spoken := a.finds[findKey{entry.Document, entry.Fragment}]; spoken {
				return loc, true
			}
			// Otherwise it names an anchor, a heading's id, that no sentence carries; the
			// sentence to begin with is the first at or after it in the text.
			where := positions(entry.Document)
			if where == nil || len(where.sentences) == 0 {
				// The text could not be read, or none of its sentences is in it: the top of
				// the document, which is no worse than a link would do.
				return own[0], true
			}
			anchor, found := where.anchors[entry.Fragment]
			if !found {
				// No such anchor: a link to an id that is not there goes to the top as well.
				return own[0], true
			}
			if i := sort.Search(len(where.sentences), func(i int) bool { return where.sentences[i].at >= anchor }); i < len(where.sentences) {
				return where.sentences[i].loc, true
			}
		}
		// Nothing narrated in the entry's own document at or after it. Its words may be
		// in the documents that follow, up to the next that an entry points at.
		if at, listed := spineAt[entry.Document]; listed && hasFollowing[at] {
			return following[at], true
		}
		return findLocation{}, false
	}

	chapters := make([]Chapter, 0, len(a.Contents))
	for index, entry := range a.Contents {
		if loc, narrated := begin(index, entry); narrated {
			chapters = append(chapters, Chapter{Entry: index, Title: entry.Title, File: loc.file, Par: a.par(loc)})
		}
	}
	return chapters
}

func (a *Alignment) par(loc findLocation) AlignedPar { return a.Files[loc.file].Pars[loc.par] }

// scanIDs finds where the ids in want stand in a text document: for each, the
// number of the element that carries it, counting start tags from the top of the
// document. An a element's name counts as an id too, as in the XHTML of older
// books. It reads markup as a tolerant scanner does, skipping comments,
// processing instructions and declarations without acting on any of them, so a
// document that is not well-formed, or declares a DTD, still has its places and
// nothing in it can make the scan do more than walk its bytes.
func scanIDs(data []byte, want map[string]struct{}) map[string]int {
	found := map[string]int{}
	element := 0
	i := 0
	for i < len(data) && len(found) < len(want) {
		open := bytes.IndexByte(data[i:], '<')
		if open < 0 {
			break
		}
		i += open + 1
		if i >= len(data) {
			break
		}
		switch data[i] {
		case '!':
			switch {
			case bytes.HasPrefix(data[i:], []byte("!--")):
				i = skipPast(data, i+3, "-->")
			case bytes.HasPrefix(data[i:], []byte("![CDATA[")):
				i = skipPast(data, i+8, "]]>")
			default:
				i = skipPast(data, i+1, ">")
			}
			continue
		case '?':
			i = skipPast(data, i+1, "?>")
			continue
		case '/':
			i = skipPast(data, i+1, ">")
			continue
		}
		element++
		nameEnd := i
		for nameEnd < len(data) && !isMarkupSpace(data[nameEnd]) && data[nameEnd] != '/' && data[nameEnd] != '>' {
			nameEnd++
		}
		isAnchor := nameEnd-i == 1 && (data[i] == 'a' || data[i] == 'A')
		i = nameEnd
		for i < len(data) {
			for i < len(data) && (isMarkupSpace(data[i]) || data[i] == '/') {
				i++
			}
			if i >= len(data) {
				break
			}
			if data[i] == '>' {
				i++
				break
			}
			nameStart := i
			for i < len(data) && !isMarkupSpace(data[i]) && data[i] != '=' && data[i] != '>' && data[i] != '/' {
				i++
			}
			attrName := data[nameStart:i]
			for i < len(data) && isMarkupSpace(data[i]) {
				i++
			}
			var value []byte
			if i < len(data) && data[i] == '=' {
				i++
				for i < len(data) && isMarkupSpace(data[i]) {
					i++
				}
				if i < len(data) && (data[i] == '"' || data[i] == '\'') {
					end := bytes.IndexByte(data[i+1:], data[i])
					if end < 0 {
						return found
					}
					value = data[i+1 : i+1+end]
					i += end + 2
				} else {
					start := i
					for i < len(data) && !isMarkupSpace(data[i]) && data[i] != '>' {
						i++
					}
					value = data[start:i]
				}
			}
			if bytes.EqualFold(attrName, []byte("id")) || (isAnchor && bytes.EqualFold(attrName, []byte("name"))) {
				if _, wanted := want[string(value)]; wanted {
					if _, seen := found[string(value)]; !seen {
						found[string(value)] = element
					}
				}
			}
		}
	}
	return found
}

// skipPast is the place after the next marker at or after from, or the end of the
// data when there is none.
func skipPast(data []byte, from int, marker string) int {
	if from >= len(data) {
		return len(data)
	}
	at := bytes.Index(data[from:], []byte(marker))
	if at < 0 {
		return len(data)
	}
	return from + at + len(marker)
}

func isMarkupSpace(b byte) bool {
	return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f'
}
