package reading

import (
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"
)

func TestParseNavFlattensNestedEntriesInReadingOrderAndKeepsTheirTitles(t *testing.T) {
	// A landmarks nav ahead of the contents; a group heading with no link; a title
	// with markup, a picture's alt text and runs of white space; hrefs that climb
	// out of the navigation document's folder, are percent-encoded or hold spaces;
	// and an address, which no document of the edition is.
	const nav = `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Navigation</title></head>
<body>
<nav epub:type="landmarks" hidden=""><ol><li><a epub:type="cover" href="titlepage.xhtml">Cover</a></li></ol></nav>
<nav epub:type="toc" id="toc"><h1>Table of Contents</h1>
<ol>
  <li><a href="Text/front.xhtml">Front <em>matter</em>
     &amp; Co</a></li>
  <li><span>Part One</span>
    <ol>
      <li><a href="Text/ch1.xhtml#start">Chapter 1</a></li>
      <li><a href="Text/ch%202.xhtml">Chapter 2 <img src="x.png" alt="two"/></a>
        <ol><li><a href="../Other/ch3.xhtml#a%20b">Chapter 3</a></li></ol>
      </li>
    </ol>
  </li>
  <li><a href="http://example.test/x">Away</a></li>
  <li><a href="Text/ch4 with space.xhtml">  Chapter   4  </a></li>
</ol></nav>
<nav epub:type="page-list"><ol><li><a href="Text/ch1.xhtml#page1">1</a></li></ol></nav>
</body></html>`
	got, err := parseNav([]byte(nav), "OEBPS/Nav/nav.xhtml")
	if err != nil {
		t.Fatal(err)
	}
	want := []ContentsEntry{
		{"Front matter & Co", "OEBPS/Nav/Text/front.xhtml", ""},
		{"Chapter 1", "OEBPS/Nav/Text/ch1.xhtml", "start"},
		{"Chapter 2 two", "OEBPS/Nav/Text/ch 2.xhtml", ""},
		{"Chapter 3", "OEBPS/Other/ch3.xhtml", "a b"},
		{"Chapter 4", "OEBPS/Nav/Text/ch4 with space.xhtml", ""},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("entries = %+v\nwant %+v", got, want)
	}
}

func TestParseNavTakesTheNavOfTypeTocWhateverElseTheTypeSays(t *testing.T) {
	const nav = `<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
<nav epub:type="loi"><ol><li><a href="a.xhtml">List of illustrations</a></li></ol></nav>
<nav epub:type="frontmatter toc"><ol><li><a href="b.xhtml">The contents</a></li></ol></nav>
<nav epub:type="toc"><ol><li><a href="c.xhtml">A second contents</a></li></ol></nav>
</body></html>`
	got, err := parseNav([]byte(nav), "nav.xhtml")
	if err != nil || len(got) != 1 || got[0].Title != "The contents" || got[0].Document != "b.xhtml" {
		t.Fatalf("entries = %+v, %v: the first nav that holds the word toc is the contents, and only it", got, err)
	}
	if got, err := parseNav([]byte(`<html xmlns="http://www.w3.org/1999/xhtml"><body><nav><ol><li><a href="a.xhtml">x</a></li></ol></nav></body></html>`), "nav.xhtml"); err != nil || len(got) != 0 {
		t.Fatalf("a nav of no type is not the contents: %+v, %v", got, err)
	}
}

func TestParseNCXFlattensNestedPointsParentFirstWithTheirFirstLabel(t *testing.T) {
	const ncx = `<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="en">
<head><meta name="dtb:uid" content="x"/></head>
<docTitle><text>The Book</text></docTitle>
<navMap>
  <navPoint id="p1" playOrder="1"><navLabel><text>Prologue</text></navLabel><content src="text/prologue.xhtml"/></navPoint>
  <navPoint id="p2" playOrder="2"><navLabel><text>Part One</text></navLabel><navLabel xml:lang="fr"><text>Partie un</text></navLabel><content src="text/part1.xhtml"/>
    <navPoint id="p3" playOrder="3"><navLabel><text>Chapter 1</text></navLabel><content src="text/ch1.xhtml#c1"/></navPoint>
    <navPoint id="p4" playOrder="4"><navLabel><text>No place</text></navLabel></navPoint>
    <navPoint id="p5" playOrder="5"><navLabel><text>  Chapter
       2 </text></navLabel><content src="text/ch2.xhtml"/></navPoint>
  </navPoint>
  <navPoint id="p6" playOrder="6"><navLabel><text>Epilogue</text></navLabel><content src="http://example.test/e"/></navPoint>
  <navPoint id="p7" playOrder="7"><navLabel><text>Afterword</text></navLabel><content src="text/after word.xhtml"/></navPoint>
</navMap>
<pageList><pageTarget id="pg1" value="1" type="normal" playOrder="8"><navLabel><text>1</text></navLabel><content src="text/ch1.xhtml#page1"/></pageTarget></pageList>
</ncx>`
	got, err := parseNCX([]byte(ncx), "OEBPS/toc.ncx")
	if err != nil {
		t.Fatal(err)
	}
	want := []ContentsEntry{
		{"Prologue", "OEBPS/text/prologue.xhtml", ""},
		{"Part One", "OEBPS/text/part1.xhtml", ""},
		{"Chapter 1", "OEBPS/text/ch1.xhtml", "c1"},
		{"Chapter 2", "OEBPS/text/ch2.xhtml", ""},
		{"Afterword", "OEBPS/text/after word.xhtml", ""},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("entries = %+v\nwant %+v", got, want)
	}
}

func TestParseContentsRefuseWhatCouldHarmOrMislead(t *testing.T) {
	const nav = `<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="a.xhtml">A</a></li><li><a href="b.xhtml">B</a></li></ol></nav></body></html>`
	const ncx = `<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint><navLabel><text>A</text></navLabel><content src="a.xhtml"/></navPoint><navPoint><navLabel><text>B</text></navLabel><content src="b.xhtml"/></navPoint></navMap></ncx>`
	doctype := `<!DOCTYPE x [<!ENTITY boom "boom">]>`
	for name, test := range map[string]struct {
		parse func([]byte, string) ([]ContentsEntry, error)
		data  string
	}{
		"a DOCTYPE in a navigation document":    {parseNav, doctype + nav},
		"a DOCTYPE in an NCX":                   {parseNCX, doctype + ncx},
		"a navigation document that is not XML": {parseNav, strings.Replace(nav, "</ol>", "</ul>", 1)},
		"an NCX that is not XML":                {parseNCX, strings.Replace(ncx, "</navMap>", "", 1)},
	} {
		t.Run(name, func(t *testing.T) {
			got, err := test.parse([]byte(test.data), "toc")
			if err == nil || !errors.Is(err, ErrBadAlignment) {
				t.Fatalf("accepted: %+v, %v", got, err)
			}
			if strings.Contains(err.Error(), "boom") {
				t.Fatalf("the error repeats what it was given: %v", err)
			}
		})
	}

	original := maxContents
	t.Cleanup(func() { maxContents = original })
	maxContents = 1
	for name, test := range map[string]struct {
		parse func([]byte, string) ([]ContentsEntry, error)
		data  string
	}{"a navigation document": {parseNav, nav}, "an NCX": {parseNCX, ncx}} {
		if got, err := test.parse([]byte(test.data), "toc"); !errors.Is(err, ErrBadAlignment) {
			t.Errorf("%s of more entries than the cap: %+v, %v", name, got, err)
		}
	}
	maxContents = original
	if got, err := parseNav([]byte(nav), "toc"); err != nil || len(got) != 2 {
		t.Fatalf("with the cap restored: %+v, %v", got, err)
	}
}

func TestParseContentsCutALongTitle(t *testing.T) {
	long := strings.Repeat("é", maxTitleRunes+50)
	got, err := parseNav([]byte(`<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="a.xhtml">`+long+`</a></li></ol></nav></body></html>`), "nav.xhtml")
	if err != nil || len(got) != 1 {
		t.Fatalf("entries = %+v, %v", got, err)
	}
	if runes := len([]rune(got[0].Title)); runes != maxTitleRunes {
		t.Fatalf("title of %d runes, want it cut to %d", runes, maxTitleRunes)
	}
}

func TestScanIDsPlacesIdsAmongTheElementsWhateverMarkupSurroundsThem(t *testing.T) {
	const document = `<?xml version="1.0"?><!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html><head><title>t</title></head>
<!-- <p id="ghost"> -->
<body class='x' ID=top><h1 id = "h1" data-id="no">Title</h1>
<p>text &nbsp; <a name="old"/><a href="#h1" title="a>b">link</a><![CDATA[ <p id="cdata"> ]]></p>
<span id='s1' title="a > b">x</span><br/><div id=s2 /><?pi <p id="pi"> ?><P ID="late"></P></body></html>`
	want := map[string]struct{}{}
	for _, id := range []string{"ghost", "top", "h1", "no", "old", "cdata", "s1", "s2", "pi", "late", "absent"} {
		want[id] = struct{}{}
	}
	got := scanIDs([]byte(document), want)
	for _, id := range []string{"ghost", "no", "cdata", "pi", "absent"} {
		if at, found := got[id]; found {
			t.Errorf("%q is placed at %d: it is a comment, an attribute's value or no id at all", id, at)
		}
	}
	order := []string{"top", "h1", "old", "s1", "s2", "late"}
	for i, id := range order {
		if _, found := got[id]; !found {
			t.Fatalf("%q is not placed: %v", id, got)
		}
		if i > 0 && got[id] <= got[order[i-1]] {
			t.Errorf("%q is at %d, not after %q at %d", id, got[id], order[i-1], got[order[i-1]])
		}
	}
	// html, head, title, body, h1, p, a, a, span, br, div, P: the body is the fourth
	// element and the heading, the fifth.
	if got["top"] != 4 || got["h1"] != 5 {
		t.Errorf("top at %d and h1 at %d, want 4 and 5", got["top"], got["h1"])
	}
	// A heading's id and the sentence inside it are two elements, the id first.
	inner := scanIDs([]byte(`<h3 id="calibre_toc_2"><a name="x"/><span id="s0">2</span></h3>`), map[string]struct{}{"calibre_toc_2": {}, "s0": {}})
	if inner["calibre_toc_2"] != 1 || inner["s0"] != 3 {
		t.Errorf("a heading and its sentence: %v", inner)
	}
	// An unterminated attribute ends the scan with what it has found.
	if cut := scanIDs([]byte(`<p id="a">x</p><p id="b`), map[string]struct{}{"a": {}, "b": {}}); len(cut) != 1 || cut["a"] != 1 {
		t.Errorf("an unterminated value: %v", cut)
	}
}

// contentsEdition is a small book of four chapters narrated in two sources, in
// which: chapter 2 begins in the first chunk of the first source and ends in the
// second; nothing narrates a cover, a copyright page or the picture that heads
// chapter 3; and the package lists them in that order.
func contentsEdition() AlignedEPUBOptions {
	return AlignedEPUBOptions{
		PackageDir: "OEBPS",
		AudioBytes: 1024,
		Narrations: []FixtureNarration{
			// Chapter 1 is the first six sentences of the first chunk, chapter 2 the last two
			// and the four of the second chunk.
			{ChunkMs: []int64{120_000, 60_000}, Sentences: []int{8, 4}, Chapters: []int{6, 6}},
			{ChunkMs: []int64{90_000}, Sentences: []int{6}, Chapters: []int{3, 3}},
		},
		Documents: []FixtureDocument{
			{Name: "cover.xhtml", Before: 0},
			{Name: "copyright.xhtml", Before: 0},
			{Name: "heading3.xhtml", Before: 3},
		},
		Anchors: []FixtureAnchor{
			{ID: "begin1", Chapter: 1, Before: 0},
			{ID: "mid2", Chapter: 2, Before: 3},
		},
		Contents: []FixtureContent{
			{Title: "Cover", Document: "cover.xhtml"},
			{Title: "Copyright", Document: "copyright.xhtml"},
			{Title: "Part One", Chapter: 1, Children: []FixtureContent{
				{Title: "Chapter 1", Chapter: 1, Fragment: "begin1"},
				{Title: "Chapter 2", Chapter: 2},
				{Title: "Midway", Chapter: 2, Fragment: "mid2"},
			}},
			{Title: "Chapter 3", Document: "heading3.xhtml"},
			{Title: "Chapter 4", Chapter: 4, Fragment: "id4-s1"},
		},
	}
}

// chapterAt says which sentence of the fixture a chapter begins with, as a list of
// "title: sentence" lines to compare.
func chapterLines(t *testing.T, alignment *Alignment, fixture AlignedEPUBFixture) []string {
	t.Helper()
	byFragment := map[string]FixturePar{}
	for _, par := range fixture.Pars {
		byFragment[par.Fragment] = par
	}
	var lines []string
	for _, chapter := range alignment.Chapters() {
		truth := byFragment[chapter.Par.Fragment]
		if truth.Fragment == "" || chapter.Par.Text != truth.Text || alignment.Files[chapter.File].Entry != truth.Audio || chapter.Par.BeginMs != truth.BeginMs {
			t.Fatalf("%q begins with %+v in file %d, which is not a sentence of the edition", chapter.Title, chapter.Par, chapter.File)
		}
		lines = append(lines, fmt.Sprintf("%s: %s", chapter.Title, chapter.Par.Fragment))
	}
	return lines
}

func TestAlignmentChaptersBeginWhereTheirEntryIsFirstSpoken(t *testing.T) {
	fixture := generateAligned(t, contentsEdition())
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	titles := make([]string, len(alignment.Contents))
	for i, entry := range alignment.Contents {
		titles[i] = entry.Title
	}
	// The contents are flattened in the order they list: a part ahead of the chapters
	// inside it.
	if want := []string{"Cover", "Copyright", "Part One", "Chapter 1", "Chapter 2", "Midway", "Chapter 3", "Chapter 4"}; !reflect.DeepEqual(titles, want) {
		t.Fatalf("contents = %v, want %v", titles, want)
	}
	got := chapterLines(t, alignment, fixture)
	want := []string{
		// The cover and the copyright page have nothing narrated, and the document after
		// each is claimed by an entry of its own.
		"Part One: id1-s0",
		// An anchor ahead of the first sentence of its document begins there too.
		"Chapter 1: id1-s0",
		"Chapter 2: id2-s0",
		// An anchor between sentences begins with the next one, and that is in the
		// second chunk of the file the chapter began in.
		"Midway: id2-s3",
		// Its heading is a picture in a document nothing narrates; the words are in the
		// document after it, which no entry points at.
		"Chapter 3: id3-s0",
		// The only entry into its document begins at its top, though it names the
		// second sentence: it is the first, and has the document from there.
		"Chapter 4: id4-s0",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q\nwant %q", got, want)
	}
	// The chunks the chapters are in.
	chapters := alignment.Chapters()
	if file := alignment.Files[chapters[3].File]; file.Entry != "OEBPS/Audio/00001-00002.mp3" || chapters[3].Par.BeginMs != 15_000 {
		t.Errorf("Midway is in %s at %d ms", file.Entry, chapters[3].Par.BeginMs)
	}
}

func TestAlignmentChaptersOfEveryChapterByDefault(t *testing.T) {
	// An edition that says nothing of its contents lists each chapter once as "Part N".
	options := contentsEdition()
	options.Contents, options.Anchors, options.Documents = nil, nil, nil
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Part 1: id1-s0", "Part 2: id2-s0", "Part 3: id3-s0", "Part 4: id4-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}
}

func TestAlignmentChaptersAreSpokenWhereTheNarrationSaysNotWhereTheTextListsThem(t *testing.T) {
	// Chapter 3, spoken after chapter 2, is listed ahead of everything, as Mistborn's
	// two-sentence chapter is. The contents list it in the order of the text, so its
	// chapter is first, and it begins where it is spoken.
	options := contentsEdition()
	options.SpineOrder = []int{2, 0, 1, 3}
	options.Documents = nil
	options.Anchors = nil
	options.Contents = []FixtureContent{
		{Title: "Third", Chapter: 3},
		{Title: "First", Chapter: 1},
		{Title: "Second", Chapter: 2},
		{Title: "Fourth", Chapter: 4},
	}
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Third: id3-s0", "First: id1-s0", "Second: id2-s0", "Fourth: id4-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}
	// What is listed first is narrated after what is listed second: placing the entries
	// is not reordering them, and the order they are heard in is for the caller.
	chapters := alignment.Chapters()
	if chapters[0].File <= chapters[2].File {
		t.Fatalf("the first entry is in audio file %d and the third in %d: chapter 3 is spoken after chapter 2", chapters[0].File, chapters[2].File)
	}
}

// Nothing narrated in a listed document at or after its anchor, and nothing narrated
// in the documents after it that no entry claims, is no chapter; an unlisted
// document that is narrated is claimed by the entry ahead of it.
func TestAlignmentChaptersLeaveOutWhatNothingNarratesAndClaimWhatNoEntryDoes(t *testing.T) {
	options := contentsEdition()
	options.Anchors = nil
	options.Contents = []FixtureContent{
		// Cover is followed by the copyright page, which an entry points at.
		{Title: "Cover", Document: "cover.xhtml"},
		// Copyright is followed by chapter 1, which no entry points at: it is claimed.
		{Title: "Copyright", Document: "copyright.xhtml"},
		{Title: "Chapter 2", Chapter: 2},
		// Chapter 3's heading picture is followed by chapter 3, which is claimed by it.
		{Title: "Heading", Document: "heading3.xhtml"},
		// Chapter 4 is the last of the book and has its own sentences.
		{Title: "Chapter 4", Chapter: 4},
	}
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Copyright: id1-s0", "Chapter 2: id2-s0", "Heading: id3-s0", "Chapter 4: id4-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}

	// A document no spine lists has no documents after it to look through.
	options.Documents = append(options.Documents, FixtureDocument{Name: "appendix.xhtml", Before: 99})
	options.Contents = []FixtureContent{{Title: "Chapter 1", Chapter: 1}, {Title: "Appendix", Document: "appendix.xhtml"}}
	fixture = generateAligned(t, options)
	alignment, err = readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Chapter 1: id1-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q: an entry that is not in the reading order has nothing after it", got, want)
	}
}

func TestAlignmentChaptersOfAnAnchorThatIsNotThereBeginAtTheTopOfItsDocument(t *testing.T) {
	options := contentsEdition()
	options.Contents = []FixtureContent{
		{Title: "Chapter 1", Chapter: 1},
		{Title: "Chapter 2", Chapter: 2, Fragment: "nowhere"},
		{Title: "Midway", Chapter: 2, Fragment: "mid2"},
	}
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Chapter 1: id1-s0", "Chapter 2: id2-s0", "Midway: id2-s3"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}

	// With no text to look through, an anchor cannot be placed either, and its entry
	// begins at the top of its document, which is no worse than a link would do.
	original := maxTextBytes
	t.Cleanup(func() { maxTextBytes = original })
	maxTextBytes = 1
	alignment, err = readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Chapter 1: id1-s0", "Chapter 2: id2-s0", "Midway: id2-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("with no text read, chapters = %q, want %q", got, want)
	}
}

// A text that does not hold the ids the overlay names cannot say where an anchor stands
// among its sentences, and the entry begins at the top rather than in the documents
// after it.
func TestAlignmentChaptersOfATextWithNoneOfItsSentencesBeginAtTheTopOfItsDocument(t *testing.T) {
	fixture := generateAligned(t, contentsEdition())
	changed := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, _ *[]string) {
		files["OEBPS/text/part0002.xhtml"] = []byte(strings.ReplaceAll(string(files["OEBPS/text/part0002.xhtml"]), `<span id="id2-`, `<span id="gone2-`))
	})
	alignment, err := readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	var midway *Chapter
	for _, chapter := range alignment.Chapters() {
		if chapter.Title == "Midway" {
			midway = &chapter
		}
	}
	if midway == nil || midway.Par.Fragment != "id2-s0" {
		t.Fatalf("Midway = %+v", midway)
	}
}

// Mistborn's chapters open with an epigraph, spoken ahead of the heading the contents
// point at. The first entry into a document has the document from its top, so the
// epigraph is its chapter's and not the end of the chapter before.
func TestAlignmentChaptersTheFirstEntryIntoADocumentBeginsAtItsTopWhateverItNames(t *testing.T) {
	options := contentsEdition()
	options.Documents = nil
	options.Anchors = []FixtureAnchor{
		{ID: "heading1", Chapter: 1, Before: 2}, // two sentences of epigraph, then the heading
		{ID: "heading2", Chapter: 2, Before: 3},
	}
	options.Contents = []FixtureContent{
		{Title: "One", Chapter: 1, Fragment: "heading1"},
		// Naming a sentence, which is no more a reason to begin there.
		{Title: "Two", Chapter: 2, Fragment: "id2-s3"},
		{Title: "Three", Chapter: 3},
	}
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"One: id1-s0", "Two: id2-s0", "Three: id3-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}

	// With no text to look through nothing changes: the first entry never needed it.
	original := maxTextBytes
	t.Cleanup(func() { maxTextBytes = original })
	maxTextBytes = 1
	alignment, err = readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"One: id1-s0", "Two: id2-s0", "Three: id3-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("with no text read, chapters = %q, want %q", got, want)
	}
}

// Where several entries share a document the first has it from the top and each of the
// others begins where it points, since what lies between two anchors is the earlier's.
func TestAlignmentChaptersEntriesThatShareADocumentBeginAtTheirAnchorsAfterTheFirst(t *testing.T) {
	options := contentsEdition()
	options.Documents = nil
	options.Anchors = []FixtureAnchor{
		{ID: "a1", Chapter: 2, Before: 2},
		{ID: "b1", Chapter: 2, Before: 4},
	}
	options.Contents = []FixtureContent{
		{Title: "A", Chapter: 2, Fragment: "a1"},
		{Title: "B", Chapter: 2, Fragment: "b1"},
		// A sentence named by an entry after the first begins there.
		{Title: "C", Chapter: 2, Fragment: "id2-s5"},
		// No place named: the top of the document, where the first entry already is.
		{Title: "D", Chapter: 2},
	}
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"A: id2-s0", "B: id2-s4", "C: id2-s5", "D: id2-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}
}

// An anchor after every narrated sentence of its document, as a heading left at the
// end of one document is when the next begins its chapter: the words are in the
// documents that follow, up to the next that an entry points at.
func TestAlignmentChaptersOfAnAnchorAfterTheLastSentenceBeginInTheDocumentsThatFollow(t *testing.T) {
	options := contentsEdition()
	options.Anchors = nil
	options.Contents = []FixtureContent{
		{Title: "Chapter 1", Chapter: 1},
		{Title: "Chapter 2", Chapter: 2},
		{Title: "End of two", Chapter: 2, Fragment: "end2"},
		{Title: "Chapter 4", Chapter: 4},
	}
	fixture := generateAligned(t, options)
	// The anchor is the last element of chapter 2's text, after its last sentence.
	changed := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, _ *[]string) {
		replaceIn(files, "OEBPS/text/part0002.xhtml", "</p>", `<a id="end2"/></p>`)
	})
	alignment, err := readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	// Chapter 3's heading picture has no sentence, and its document, the next after it,
	// has: the entry begins with it.
	if got, want := chapterLines(t, alignment, fixture), []string{"Chapter 1: id1-s0", "Chapter 2: id2-s0", "End of two: id3-s0", "Chapter 4: id4-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}

	// Were the next document one an entry points at, there would be nothing to begin with.
	options.Contents = append(options.Contents[:3:3], FixtureContent{Title: "Chapter 3", Chapter: 3}, options.Contents[3])
	fixture = generateAligned(t, options)
	changed = rewriteEPUB(t, fixture.Path, func(files map[string][]byte, _ *[]string) {
		replaceIn(files, "OEBPS/text/part0002.xhtml", "</p>", `<a id="end2"/></p>`)
	})
	alignment, err = readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Chapter 1: id1-s0", "Chapter 2: id2-s0", "Chapter 3: id3-s0", "Chapter 4: id4-s0"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q: the heading's own document is claimed by the next entry", got, want)
	}
}

// The book's text is in the order of its sentences and the narration in the order they
// are spoken, which need not be the same: a chapter begins with the first sentence of
// its text, wherever that is spoken.
func TestAlignmentChaptersBeginWithTheFirstSentenceOfTheTextNotTheFirstHeard(t *testing.T) {
	options := contentsEdition()
	options.Contents, options.Anchors, options.Documents = nil, nil, nil
	fixture := generateAligned(t, options)
	changed := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, _ *[]string) {
		// The first sentence of chapter 1 is spoken after the second begins.
		replaceIn(files, "OEBPS/smil/part0001.smil", `clipBegin="0.000s" clipEnd="15000ms"`, `clipBegin="14000ms" clipEnd="15000ms"`)
		replaceIn(files, "OEBPS/smil/part0001.smil", `clipBegin="0:00:15.000"`, `clipBegin="5000ms"`)
	})
	alignment, err := readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	first := alignment.Files[0].Pars[0]
	if first.Fragment != "id1-s1" || first.BeginMs != 5000 {
		t.Fatalf("the first sentence heard is %+v", first)
	}
	chapters := alignment.Chapters()
	if chapters[0].Title != "Part 1" || chapters[0].Par.Fragment != "id1-s0" || chapters[0].Par.BeginMs != 14_000 {
		t.Fatalf("the first chapter begins with %+v", chapters[0])
	}
}

// An anchor and a sentence can be one element, as an old-style a element's name and
// id are: the chapter begins with that sentence and not the one after it.
func TestAlignmentChaptersAnAnchorOnTheElementOfASentenceBeginsWithIt(t *testing.T) {
	options := contentsEdition()
	options.Anchors, options.Documents = nil, nil
	options.Contents = []FixtureContent{{Title: "Chapter 1", Chapter: 1}, {Title: "Chapter 2", Chapter: 2}, {Title: "Seam", Chapter: 2, Fragment: "seam"}}
	fixture := generateAligned(t, options)
	changed := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, _ *[]string) {
		replaceIn(files, "OEBPS/text/part0002.xhtml", `<span id="id2-s3">Sentence 4 of part 2.</span>`, `<a id="id2-s3" name="seam">Sentence 4 of part 2.</a>`)
	})
	alignment, err := readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := chapterLines(t, alignment, fixture), []string{"Chapter 1: id1-s0", "Chapter 2: id2-s0", "Seam: id2-s3"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %q, want %q", got, want)
	}
}

func TestReadAlignmentReadsTheContentsFromTheNavigationDocumentThenTheNCX(t *testing.T) {
	titlesOf := func(alignment *Alignment) []string {
		var titles []string
		for _, entry := range alignment.Contents {
			titles = append(titles, entry.Title)
		}
		return titles
	}
	options := contentsEdition()
	for name, test := range map[string]struct {
		in   FixtureContentsIn
		want []string
	}{
		"navigation document":      {ContentsInNav, []string{"Cover", "Copyright", "Part One", "Chapter 1", "Chapter 2", "Midway", "Chapter 3", "Chapter 4"}},
		"NCX of an EPUB 2 book":    {ContentsInNCX, []string{"Cover", "Copyright", "Part One", "Chapter 1", "Chapter 2", "Midway", "Chapter 3", "Chapter 4"}},
		"both, the navigation one": {ContentsInBoth, []string{"Cover", "Copyright", "Part One", "Chapter 1", "Chapter 2", "Midway", "Chapter 3", "Chapter 4"}},
	} {
		t.Run(name, func(t *testing.T) {
			options.ContentsIn = test.in
			fixture := generateAligned(t, options)
			alignment, err := readAlignmentOf(t, fixture.Path)
			if err != nil {
				t.Fatal(err)
			}
			if got := titlesOf(alignment); !reflect.DeepEqual(got, test.want) {
				t.Fatalf("contents = %v, want %v", got, test.want)
			}
			if got, want := chapterLines(t, alignment, fixture)[0], "Part One: id1-s0"; got != want {
				t.Fatalf("first chapter = %q, want %q", got, want)
			}
			// The same documents either way.
			if alignment.Contents[3].Document != "OEBPS/text/part0001.xhtml" || alignment.Contents[3].Fragment != "begin1" {
				t.Fatalf("entry 3 = %+v", alignment.Contents[3])
			}
		})
	}

	// A navigation document with no contents in it is no reason to go without the NCX.
	options.ContentsIn = ContentsInBoth
	fixture := generateAligned(t, options)
	changed := rewriteEPUB(t, fixture.Path, func(files map[string][]byte, _ *[]string) {
		files["OEBPS/nav.xhtml"] = []byte(`<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"/></body></html>`)
	})
	alignment, err := readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	if got := titlesOf(alignment); len(got) != 8 || got[0] != "NCX Cover" || got[5] != "NCX Midway" {
		t.Fatalf("contents = %v: an empty navigation document should leave the NCX", got)
	}

	// An NCX found by what it is, when the spine names none.
	options.ContentsIn = ContentsInNCX
	fixture = generateAligned(t, options)
	changed = rewriteEPUB(t, fixture.Path, func(files map[string][]byte, _ *[]string) {
		replaceIn(files, "OEBPS/content.opf", ` toc="ncx"`, "")
	})
	alignment, err = readAlignmentOf(t, changed)
	if err != nil {
		t.Fatal(err)
	}
	if got := titlesOf(alignment); len(got) != 8 || got[2] != "Part One" {
		t.Fatalf("contents = %v: an NCX is an NCX by its media type", got)
	}
}

// An edition whose contents cannot be read is still a read-along edition: what is
// refused of its narration (a DOCTYPE in a SMIL) is not refused of its contents.
func TestReadAlignmentWithoutUsableContentsIsStillAnAlignmentWithNoChapters(t *testing.T) {
	fixture := generateAligned(t, contentsEdition())
	for name, change := range map[string]func(files map[string][]byte, order *[]string){
		"no navigation document": func(files map[string][]byte, order *[]string) {
			delete(files, "OEBPS/nav.xhtml")
		},
		"a navigation document that is not XML": func(files map[string][]byte, _ *[]string) {
			files["OEBPS/nav.xhtml"] = []byte("<html><nav>")
		},
		"a DOCTYPE in the navigation document": func(files map[string][]byte, _ *[]string) {
			replaceIn(files, "OEBPS/nav.xhtml", "<html ", `<!DOCTYPE html [<!ENTITY boom "boom">]><html `)
		},
		"contents that point nowhere": func(files map[string][]byte, _ *[]string) {
			files["OEBPS/nav.xhtml"] = []byte(`<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="http://example.test/a">A</a></li><li><a href="../../b.xhtml">B</a></li></ol></nav></body></html>`)
		},
	} {
		t.Run(name, func(t *testing.T) {
			path := rewriteEPUB(t, fixture.Path, change)
			alignment, err := readAlignmentOf(t, path)
			if err != nil {
				t.Fatalf("the narration is refused for what is wrong with the contents: %v", err)
			}
			if len(alignment.Contents) != 0 || len(alignment.Chapters()) != 0 || len(alignment.Files) != len(fixture.Chunks) {
				t.Fatalf("%d entries, %d chapters, %d files", len(alignment.Contents), len(alignment.Chapters()), len(alignment.Files))
			}
		})
	}

	original := maxContents
	t.Cleanup(func() { maxContents = original })
	maxContents = 3
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil || len(alignment.Contents) != 0 || len(alignment.Files) != len(fixture.Chunks) {
		t.Fatalf("contents over the cap: %d entries, %v", len(alignment.Contents), err)
	}
}

// What the contents cost: its documents and, for an anchor, the one text document
// it is in, and never the audio, which is most of an edition's bytes.
func TestReadAlignmentReadsTheContentsAndNeverTheAudio(t *testing.T) {
	options := contentsEdition()
	options.AudioBytes = 4 << 20
	fixture := generateAligned(t, options)
	file, size := openEPUB(t, fixture.Path)
	if size < 8<<20 {
		t.Fatalf("the edition is %d bytes: the audio is not there to be left unread", size)
	}
	counted := &countingReaderAt{ReaderAt: file}
	alignment, err := ReadAlignment(counted, size)
	if err != nil {
		t.Fatal(err)
	}
	if len(alignment.Chapters()) != 6 {
		t.Fatalf("%d chapters", len(alignment.Chapters()))
	}
	if read := counted.bytes.Load(); read > 512<<10 {
		t.Fatalf("read %d of %d bytes: the audio was read", read, size)
	}
}
