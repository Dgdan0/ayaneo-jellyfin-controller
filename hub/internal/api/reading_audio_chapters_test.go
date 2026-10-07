package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"sync/atomic"
	"testing"

	readingdomain "ayaneohub/internal/reading"
)

// A book of seven chapters across the five tracks of the tracked fixture, which the
// narration of each file is 12 ms short of, as the real ones are:
//
//	track 0  chapters 1 and 2: six sentences of 768.440 s, three to each
//	track 1  chapter 3
//	track 2  chapter 4, then chapter 5 from 666.666 s on; Storyteller cut the file in
//	         two, and the second chunk starts 1000 s in, at chapter 5's second sentence
//	track 3  chapter 6
//	track 4  chapter 7
func chapteredNarrations() map[string]readingdomain.FixtureNarration {
	return map[string]readingdomain.FixtureNarration{
		"Fixture Odyssey.mp3":                                 {ChunkMs: []int64{4_610_640}, Sentences: []int{6}, Chapters: []int{3, 3}},
		"Fixture Odyssey (1).mp3":                             {ChunkMs: []int64{1_000_488}, Sentences: []int{4}},
		"Fixture Odyssey (2).mp3":                             {ChunkMs: []int64{1_000_000, 1_000_738}, Sentences: []int{3, 3}, Chapters: []int{2, 4}},
		"Fixture Odyssey (3).MP3":                             {ChunkMs: []int64{2_499_988}, Sentences: []int{5}},
		"Part 5 - 100% Pure & Co., It's #5 [Ünïcode] פרק.mp3": {ChunkMs: []int64{3_000_238}, Sentences: []int{5}},
	}
}

// The contents of that book as its edition lists them: a cover and a copyright page
// that nothing narrates; a dedication that is listed among them and spoken in the
// middle of the sixth chapter; parts with their chapters inside, a part beginning
// with the chapter it opens; a chapter listed again from the middle of the file it
// is in, in each chunk; one with no title; an epilogue.
func chapteredContents() ([]readingdomain.FixtureContent, []readingdomain.FixtureDocument) {
	return []readingdomain.FixtureContent{
		{Title: "Cover", Document: "cover.xhtml"},
		{Title: "Copyright", Document: "copyright.xhtml"},
		{Title: "Dedication", Chapter: 6, Fragment: "id6-s2"},
		{Title: "Part One", Chapter: 1, Children: []readingdomain.FixtureContent{
			{Title: "Chapter 1", Chapter: 1},
			{Title: "Chapter 2", Chapter: 2},
			{Title: "Chapter 3", Chapter: 3},
		}},
		{Title: "Part Two", Chapter: 4, Children: []readingdomain.FixtureContent{
			{Title: "Chapter 4", Chapter: 4},
			{Title: "Chapter 5", Chapter: 5},
			{Title: "The second half", Chapter: 5, Fragment: "id5-s1"},
			{Title: "", Chapter: 5, Fragment: "id5-s3"},
		}},
		{Title: "Chapter 6", Chapter: 6},
		{Title: "Epilogue", Chapter: 7},
	}, []readingdomain.FixtureDocument{
		{Name: "cover.xhtml", Before: 0},
		{Name: "copyright.xhtml", Before: 0},
	}
}

func newChapteredEnv(t *testing.T, change func(*alignedOptions)) *audioEnv {
	t.Helper()
	contents, documents := chapteredContents()
	options := alignedOptions{narrate: chapteredNarrations(), contents: contents, documents: documents}
	if change != nil {
		change(&options)
	}
	return newAlignedTrackedEnv(t, options)
}

func bookChapter(title string, track int, startMs int64) ReadingAudioChapter {
	return ReadingAudioChapter{Title: title, StartMs: startMs, Track: track, Source: "book"}
}

func TestAudioManifestListsTheBooksChaptersAtTheirTracksAndMoments(t *testing.T) {
	env := newChapteredEnv(t, nil)
	manifest := env.manifest()
	if !manifest.Aligned || manifest.AlignmentReason != "" || len(manifest.Tracks) != 5 {
		t.Fatalf("aligned %v, reason %q, %d tracks", manifest.Aligned, manifest.AlignmentReason, len(manifest.Tracks))
	}
	want := []ReadingAudioChapter{
		// The cover and the copyright page have nothing narrated. The dedication is
		// listed ahead of the chapters and spoken a long way into them, so it is the
		// one that is left out. "Chapter 1" and "Chapter 4" begin where the part
		// they are in does, and the part, which comes first, keeps the place.
		bookChapter("Part One", 0, 0),
		bookChapter("Chapter 2", 0, 2_305_320),
		bookChapter("Chapter 3", 1, 0),
		bookChapter("Part Two", 2, 0),
		bookChapter("Chapter 5", 2, 666_666),
		// The second chunk of that file starts 1000 s in; the entry is at its first sentence
		// and, below, at its third, 667.158 s after that.
		bookChapter("The second half", 2, 1_000_000),
		// An entry with no title is still a place, and is named for it.
		bookChapter("Chapter 7", 2, 1_667_158),
		bookChapter("Chapter 6", 3, 0),
		bookChapter("Epilogue", 4, 0),
	}
	if !reflect.DeepEqual(manifest.Chapters, want) {
		t.Fatalf("chapters:\n got %+v\nwant %+v", manifest.Chapters, want)
	}
	// Every chapter is inside the track it names, and they run forward.
	for i, chapter := range manifest.Chapters {
		if chapter.StartMs >= manifest.Tracks[chapter.Track].DurationMs {
			t.Errorf("chapter %d begins at %d of a track of %d ms", i, chapter.StartMs, manifest.Tracks[chapter.Track].DurationMs)
		}
		if i > 0 {
			previous := manifest.Chapters[i-1]
			if chapter.Track < previous.Track || (chapter.Track == previous.Track && chapter.StartMs <= previous.StartMs) {
				t.Errorf("chapter %d (%+v) is not after chapter %d (%+v)", i, chapter, i-1, previous)
			}
		}
	}
}

func TestAudioManifestChaptersFromTheBookAreTheOnlyChaptersTheBookHas(t *testing.T) {
	// The files carry marks of their own, and the book's contents replace them: a book
	// has chapters of one source.
	env := newChapteredEnv(t, nil)
	env.mu.Lock()
	env.chapters["Fixture Odyssey (1).mp3"] = []probedChapter{{"One", 0, 400000}, {"Two", 400000, 800000}}
	env.mu.Unlock()
	manifest := env.manifest()
	if len(manifest.Chapters) != 9 {
		t.Fatalf("chapters = %+v", manifest.Chapters)
	}
	for _, chapter := range manifest.Chapters {
		if chapter.Source != "book" {
			t.Fatalf("chapter %+v is not from the book", chapter)
		}
	}
}

// Nothing but the edition's own words about itself changes the answer: where the
// edition cannot be mapped, or says too little, the chapters are what they were
// before this existed, mark for mark.
func TestAudioManifestKeepsTheFilesChaptersWhereTheBooksCannotBeUsed(t *testing.T) {
	marks := []probedChapter{{"One", 0, 400000}, {"Two", 400000, 800000}, {"", 800000, 1000500}}
	second := "Fixture Odyssey (1).mp3"
	// The chapters the files give: the second track in the order the tags put it.
	wantMarks := []ReadingAudioChapter{
		{Title: "One", StartMs: 0, Track: 1, Source: "marks"},
		{Title: "Two", StartMs: 400000, Track: 1, Source: "marks"},
		{Title: "Chapter 3", StartMs: 800000, Track: 1, Source: "marks"},
	}
	for _, test := range []struct {
		name    string
		options alignedOptions
		reason  string
		aligned bool
	}{
		{"a book with no read-along edition", alignedOptions{readaloud: `{"uuid":"a","filepath":"/library/audiobooks/Fixture Odyssey/aligned.epub","status":"PROCESSING"}`}, "", false},
		{"an edition that cannot be mapped to the files", alignedOptions{omit: []string{"Fixture Odyssey (3).MP3"}}, "files_do_not_match", false},
		{"an edition that is not there", alignedOptions{epub: func(path string) { os.Remove(path) }}, "missing_file", false},
		{"an edition with no contents", alignedOptions{epub: func(path string) {
			rewriteAlignedEPUB(t, path, func(opf string) string { return strings.Replace(opf, ` properties="nav"`, "", 1) })
		}}, "", true},
		{"contents that are not XML", alignedOptions{epub: func(path string) {
			rewriteAlignedEntry(t, path, "OEBPS/nav.xhtml", "<html><nav>")
		}}, "", true},
		{"contents of one chapter", alignedOptions{contents: []readingdomain.FixtureContent{{Title: "The book", Chapter: 1}}}, "", true},
		{"contents of nothing narrated", alignedOptions{
			contents:  []readingdomain.FixtureContent{{Title: "Index", Document: "index.xhtml"}, {Title: "Colophon", Document: "colophon.xhtml"}},
			documents: []readingdomain.FixtureDocument{{Name: "index.xhtml", Before: -1}, {Name: "colophon.xhtml", Before: -1}},
		}, "", true},
	} {
		t.Run(test.name, func(t *testing.T) {
			env := newAlignedTrackedEnv(t, test.options)
			env.mu.Lock()
			env.chapters[second] = marks
			env.mu.Unlock()
			manifest := env.manifest()
			if manifest.Aligned != test.aligned || manifest.AlignmentReason != test.reason {
				t.Fatalf("aligned %v, reason %q", manifest.Aligned, manifest.AlignmentReason)
			}
			if !reflect.DeepEqual(manifest.Chapters, wantMarks) {
				t.Fatalf("chapters = %+v\nwant %+v", manifest.Chapters, wantMarks)
			}
		})
	}
}

// rewriteAlignedEntry replaces one entry of a generated edition, as it is.
func rewriteAlignedEntry(t *testing.T, path, name, content string) {
	t.Helper()
	rewriteAlignedEPUBEntry(t, path, name, func(string) string { return content })
}

func TestAudioManifestChaptersOfAnEPUB2EditionComeFromItsNCX(t *testing.T) {
	for name, in := range map[string]readingdomain.FixtureContentsIn{"an NCX alone": readingdomain.ContentsInNCX, "an NCX beside a navigation document": readingdomain.ContentsInBoth} {
		t.Run(name, func(t *testing.T) {
			env := newChapteredEnv(t, func(options *alignedOptions) { options.contentsIn = in })
			manifest := env.manifest()
			if len(manifest.Chapters) != 9 || manifest.Chapters[0] != bookChapter("Part One", 0, 0) || manifest.Chapters[3] != bookChapter("Part Two", 2, 0) {
				t.Fatalf("chapters = %+v", manifest.Chapters)
			}
			// With both, the navigation document is the one that is read.
			for _, chapter := range manifest.Chapters {
				if strings.HasPrefix(chapter.Title, "NCX ") {
					t.Fatalf("chapter %+v is from the NCX though there is a navigation document", chapter)
				}
			}
		})
	}
}

// Mistborn: its second part's third chapter is two sentences among the front matter
// of the text and is spoken in the middle of the part. The text lists it first;
// listening has it after the second and before the fourth. The chapters stay in the
// order of the contents and in the order they are heard, and the one that is not in
// both is the one that goes.
func TestAudioManifestChaptersLeaveOutTheOneThatIsSpokenOutOfOrder(t *testing.T) {
	env := newCalibreEnv(t)
	manifest := env.manifest()
	if !manifest.Aligned {
		t.Fatalf("not aligned: %q", manifest.AlignmentReason)
	}
	// Listed: Part 3, Part 1, Part 2, Part 4. Part 3 is heard 17 997.278 s into the
	// second part, after Part 2 begins and before Part 4 does.
	want := []ReadingAudioChapter{
		bookChapter("Part 1", 0, 0),
		bookChapter("Part 2", 1, 0),
		bookChapter("Part 4", 1, 7_198_368+7_201_550+7_194_720),
	}
	if !reflect.DeepEqual(manifest.Chapters, want) {
		t.Fatalf("chapters:\n got %+v\nwant %+v", manifest.Chapters, want)
	}
}

// A lone M4B is one track, and its chapters are the narrated files placed in it.
func TestAudioManifestChaptersOfALoneM4BAreMomentsOfItsOneTrack(t *testing.T) {
	env := newAlignedM4BEnv(t)
	manifest := env.manifest()
	want := []ReadingAudioChapter{
		bookChapter("Part 1", 0, 0),
		bookChapter("Part 2", 0, 1_500_500),
		bookChapter("Part 3", 0, 3_300_750),
	}
	if !reflect.DeepEqual(manifest.Chapters, want) {
		t.Fatalf("chapters:\n got %+v\nwant %+v", manifest.Chapters, want)
	}
	// Storyteller's own chapters for the file were the same places, named by it; the
	// edition's names are the book's.
	if len(manifest.Tracks) != 1 {
		t.Fatalf("%d tracks", len(manifest.Tracks))
	}
}

func TestAudioManifestChaptersHaveTheDocumentedFields(t *testing.T) {
	keys := func(value map[string]any) []string {
		out := make([]string, 0, len(value))
		for key := range value {
			out = append(out, key)
		}
		sort.Strings(out)
		return out
	}
	env := newChapteredEnv(t, nil)
	var body map[string]any
	if err := json.Unmarshal(env.get(env.audioPath(env.child)).Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	chapters := body["chapters"].([]any)
	if len(chapters) != 9 {
		t.Fatalf("%d chapters", len(chapters))
	}
	first := chapters[0].(map[string]any)
	if got, want := keys(first), []string{"source", "startMs", "title", "track"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("chapter keys = %v, want %v", got, want)
	}
	if first["source"] != "book" || first["title"] != "Part One" || first["track"] != float64(0) || first["startMs"] != float64(0) {
		t.Fatalf("first chapter = %v", first)
	}
	// And an older app, which reads title, startMs and track, sees more of them.
}

// An edition that changes is another revision, and its chapters with it.
func TestAudioManifestChaptersFollowTheEdition(t *testing.T) {
	env := newChapteredEnv(t, nil)
	first := env.manifest()
	contents, documents := chapteredContents()
	// The same edition with one chapter fewer in its contents, under the same name.
	if _, err := readingdomain.GenerateAlignedEPUB(filepath.Join(env.build.book.Dir, "aligned.epub"), readingdomain.AlignedEPUBOptions{
		PackageDir: "OEBPS", AudioBytes: 4096, Narrations: chapteredNarrationList(), Contents: contents[:len(contents)-1], Documents: documents,
	}); err != nil {
		t.Fatal(err)
	}
	env.forget()
	second := env.manifest()
	if second.Revision == first.Revision || len(second.Chapters) != 8 || second.Chapters[7].Title != "Chapter 6" {
		t.Fatalf("revision %s then %s, %d chapters ending %+v", first.Revision, second.Revision, len(second.Chapters), second.Chapters[len(second.Chapters)-1])
	}
}

func chapteredNarrationList() []readingdomain.FixtureNarration {
	byName := chapteredNarrations()
	var list []readingdomain.FixtureNarration
	for _, name := range trackedTagOrder {
		list = append(list, byName[name])
	}
	return list
}

func TestInListeningOrderKeepsTheLongestRunAndTheEarlierOfAMoment(t *testing.T) {
	at := func(track int, start int64) ReadingAudioChapter {
		return ReadingAudioChapter{Title: "t" + string(rune('0'+track)) + "@" + string(rune('0'+start)), Track: track, StartMs: start}
	}
	titles := func(chapters []ReadingAudioChapter) []string {
		var out []string
		for _, chapter := range chapters {
			out = append(out, chapter.Title)
		}
		return out
	}
	for name, test := range map[string]struct {
		in   []ReadingAudioChapter
		want []string
	}{
		"nothing":                              {nil, nil},
		"one":                                  {[]ReadingAudioChapter{at(0, 5)}, []string{"t0@5"}},
		"already in order":                     {[]ReadingAudioChapter{at(0, 1), at(0, 2), at(1, 0), at(1, 3)}, []string{"t0@1", "t0@2", "t1@0", "t1@3"}},
		"one displaced to the front":           {[]ReadingAudioChapter{at(1, 8), at(0, 1), at(0, 2), at(1, 0)}, []string{"t0@1", "t0@2", "t1@0"}},
		"one displaced to the middle":          {[]ReadingAudioChapter{at(0, 1), at(1, 9), at(0, 2), at(1, 0)}, []string{"t0@1", "t0@2", "t1@0"}},
		"two runs as long, the earlier-ending": {[]ReadingAudioChapter{at(0, 1), at(1, 0), at(0, 5)}, []string{"t0@1", "t0@5"}},
		"the same moment, the first":           {[]ReadingAudioChapter{at(0, 1), at(0, 1), at(0, 4)}, []string{"t0@1", "t0@4"}},
		"the same moment as the last":          {[]ReadingAudioChapter{at(0, 1), at(0, 4), at(0, 4)}, []string{"t0@1", "t0@4"}},
		"a part and its first chapter":         {[]ReadingAudioChapter{{Title: "Part", Track: 0, StartMs: 0}, {Title: "Chapter", Track: 0, StartMs: 0}, {Title: "Next", Track: 0, StartMs: 9}}, []string{"Part", "Next"}},
		"all backwards, the last stays":        {[]ReadingAudioChapter{at(0, 3), at(0, 2), at(0, 1)}, []string{"t0@1"}},
		"tracks count before moments":          {[]ReadingAudioChapter{at(1, 0), at(0, 9)}, []string{"t0@9"}},
		"an earlier track after a later":       {[]ReadingAudioChapter{at(0, 1), at(2, 0), at(1, 5), at(1, 6)}, []string{"t0@1", "t1@5", "t1@6"}},
	} {
		t.Run(name, func(t *testing.T) {
			got := titles(inListeningOrder(append([]ReadingAudioChapter(nil), test.in...)))
			if !reflect.DeepEqual(got, test.want) {
				t.Fatalf("got %v, want %v", got, test.want)
			}
		})
	}
}

// A chapter that begins past the end of its track, or in one the book does not have,
// cannot be played to.
func TestBookChaptersLeaveOutWhatBeginsOutsideItsTrack(t *testing.T) {
	fixture, err := readingdomain.GenerateAlignedEPUB(filepath.Join(t.TempDir(), "aligned.epub"), readingdomain.AlignedEPUBOptions{
		PackageDir: "OEBPS", AudioBytes: 1024,
		Narrations: []readingdomain.FixtureNarration{
			{ChunkMs: []int64{100_000}, Sentences: []int{4}},
			{ChunkMs: []int64{100_000}, Sentences: []int{4}},
			{ChunkMs: []int64{100_000}, Sentences: []int{4}},
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	file, err := os.Open(fixture.Path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		t.Fatal(err)
	}
	narration, err := readingdomain.ReadAlignment(file, info.Size())
	if err != nil {
		t.Fatal(err)
	}
	// Each narrated file is a piece of a track: the first at its start, the second at 90 s
	// of a track that ends at 120 s, the third in a track the book does not have.
	mapped := &audioAlignment{narration: narration, places: []alignedPlace{{track: 0, startMs: 0}, {track: 1, startMs: 90_000}, {track: 5, startMs: 0}}}
	got := mapped.bookChapters([]int64{100_000, 120_000})
	want := []ReadingAudioChapter{bookChapter("Part 1", 0, 0), bookChapter("Part 2", 1, 90_000)}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("chapters = %+v, want %+v", got, want)
	}
	// With the second track shorter, its chapter begins after its end.
	if got := mapped.bookChapters([]int64{100_000, 90_000}); !reflect.DeepEqual(got, want[:1]) {
		t.Fatalf("chapters = %+v, want %+v", got, want[:1])
	}
}

// The contents are read last, and an edition without them is still an edition. So
// an edition read while the request that wanted it ended, which may have been cut
// short before its contents, is not kept as the edition for the hours it would be.
func TestAudioPlanKeepsNoEditionReadWhileItsRequestEnded(t *testing.T) {
	env := newChapteredEnv(t, nil)
	record, _, err := env.server.storytellerBookRecord(context.Background(), 12)
	if err != nil {
		t.Fatal(err)
	}
	book := env.server.reconcileStorytellerBook(*record)

	var reads atomic.Int32
	real := env.server.readAlignment
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	env.server.readAlignment = func(file io.ReaderAt, size int64) (*readingdomain.Alignment, error) {
		reads.Add(1)
		narration, err := real(file, size)
		cancel()
		return narration, err
	}
	if _, err := env.server.buildAudioPlan(ctx, book); !errors.Is(err, context.Canceled) {
		t.Fatalf("a plan whose request ended while its edition was read: %v", err)
	}
	plan, err := env.server.buildAudioPlan(context.Background(), book)
	if err != nil || reads.Load() != 2 || len(plan.chapters) != 9 {
		t.Fatalf("the edition was read %d times; the next plan has %d chapters, %v", reads.Load(), len(plan.chapters), err)
	}
}
