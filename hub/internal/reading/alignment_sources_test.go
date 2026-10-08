package reading

import (
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"
)

// Storyteller's aligner can run past the end of a chunk: its last sentence is
// written as ending after the audio does, and one more follows it that begins
// there and is given the audio's length as its end, so it ends before it begins.
// That one says where the audio ends; nothing past it can be heard.
func TestReadAlignmentEndsAFileWhereTheAlignerRanPastIt(t *testing.T) {
	options := AlignedEPUBOptions{
		PackageDir: "OEBPS",
		AudioBytes: 1024,
		Narrations: []FixtureNarration{
			{ChunkMs: []int64{100_000}, Sentences: []int{40}, OverrunMs: []int64{5_439}},
			{ChunkMs: []int64{7_200_000, 3_300_000}, Sentences: []int{60, 40}, OverrunMs: []int64{18_534, 0}},
		},
	}
	fixture := generateAligned(t, options)
	alignment, err := readAlignmentOf(t, fixture.Path)
	if err != nil {
		t.Fatalf("an edition the aligner ran past the end of twice: %v", err)
	}
	if alignment.PastEnd != 2 || alignment.CutAtEnd != 2 {
		t.Fatalf("past the end %d, cut at the end %d, want 2 and 2", alignment.PastEnd, alignment.CutAtEnd)
	}
	var pars []FixturePar
	for i, file := range alignment.Files {
		if want := fixture.Chunks[i]; file.Entry != want.Entry || file.LengthMs != want.LengthMs {
			t.Errorf("file %d = %s ending at %d, want %s at %d", i, file.Entry, file.LengthMs, want.Entry, want.LengthMs)
		}
		for _, par := range file.Pars {
			pars = append(pars, FixturePar{Text: par.Text, Fragment: par.Fragment, Audio: file.Entry, BeginMs: par.BeginMs, EndMs: par.EndMs})
		}
	}
	if !reflect.DeepEqual(pars, fixture.Pars) {
		t.Fatalf("the sentences read are not the ones that can be heard (%d of %d)", len(pars), len(fixture.Pars))
	}
	for _, fragment := range []string{"id1-past1", "id2-past1"} {
		if _, _, ok := alignment.Find("OEBPS/text/part000"+fragment[2:3]+".xhtml", fragment); ok {
			t.Errorf("%s, past the end of its audio, is found", fragment)
		}
	}
	// What is left is the book's files, by length, as any edition is.
	sources, err := alignment.Sources()
	if err != nil {
		t.Fatal(err)
	}
	if pairing, err := MatchSources(sources, []int64{100_012, 10_500_020}); err != nil || !reflect.DeepEqual(pairing.File, []int{0, 1}) {
		t.Fatalf("pairing = %+v, %v", pairing, err)
	}
}

// A few sentences at the end of a file are the aligner running over; many are
// times that make no sense.
func TestReadAlignmentRefusesAnEditionMostlyPastTheEndOfItsAudio(t *testing.T) {
	path := generateAligned(t, AlignedEPUBOptions{
		PackageDir: "OEBPS", AudioBytes: 1024,
		Narrations: []FixtureNarration{{ChunkMs: []int64{10_000}, Sentences: []int{4}, OverrunMs: []int64{500}}},
	}).Path
	if _, err := readAlignmentOf(t, path); !errors.Is(err, ErrBadAlignment) || errors.Is(err, ErrNoAlignment) {
		t.Fatalf("two of five sentences past the end of their audio: %v", err)
	}
}

// file is a narrated audio file as Storyteller names it, of a length.
func file(source, chunk int, lengthMs int64) AlignedFile {
	return AlignedFile{Entry: fmt.Sprintf("Audio/%05d-%05d.mp4", source, chunk), Source: source, Chunk: chunk, LengthMs: lengthMs}
}

// One M4B that Storyteller cut at its chapters: Dune is 00001-00001 to
// 00001-00018, each piece the length of its chapter.
func TestPlaceOnTracksPutsThePiecesOfAFileAtItsChapters(t *testing.T) {
	chapters := []int64{0, 4_044_753, 8_588_202, 13_007_795}
	track := AlignedTrack{DurationMs: 17_428_062, ChapterMs: chapters}
	alignment := &Alignment{Files: []AlignedFile{
		file(1, 1, 4_044_754), file(1, 2, 4_543_449), file(1, 3, 4_419_593), file(1, 4, 4_420_267),
	}}
	placement, err := alignment.PlaceOnTracks([]AlignedTrack{track})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(placement.Track, []int{0, 0, 0, 0}) || !reflect.DeepEqual(placement.StartMs, chapters) || placement.ByChapters != 1 || placement.ByOrder {
		t.Fatalf("placement = %+v", placement)
	}

	// A chapter with nothing narrated has no piece: The Dark Forest's first, 52 s of
	// credits, and The Will of the Many's part titles.
	gaps := &Alignment{Files: []AlignedFile{file(1, 2, 4_543_449), file(1, 4, 4_420_267)}}
	placement, err = gaps.PlaceOnTracks([]AlignedTrack{track})
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(placement.StartMs, []int64{4_044_753, 13_007_795}) {
		t.Fatalf("starts = %v", placement.StartMs)
	}

	for name, files := range map[string][]AlignedFile{
		// A piece 300 ms from its chapter's length is not that chapter.
		"a piece that is not its chapter's length": {file(1, 1, 4_044_754), file(1, 2, 4_543_749)},
		// With a piece missing, a running sum would place every later one wrong.
		"pieces with a gap and no chapters to put them at": {file(1, 1, 8_000_000), file(1, 3, 9_428_062)},
		"more pieces than the file has chapters":           {file(1, 5, 4_000_000)},
	} {
		t.Run(name, func(t *testing.T) {
			tracks := []AlignedTrack{track}
			if strings.Contains(name, "no chapters") {
				tracks = []AlignedTrack{{DurationMs: 17_428_062}}
			}
			if placement, err := (&Alignment{Files: files}).PlaceOnTracks(tracks); !errors.Is(err, ErrAlignmentMismatch) {
				t.Fatalf("placement %+v, %v", placement, err)
			}
		})
	}
}

// A file's pieces that run one after another from its start, as Storyteller cuts
// a file with no chapters into two-hour pieces, are placed at their running sum;
// a track with no narration (Ender's Game's last four, Sunrise on the Reaping's
// credits and part titles) is left without.
func TestPlaceOnTracksLeavesATrackWithNothingNarrated(t *testing.T) {
	tracks := []AlignedTrack{{DurationMs: 48_924}, {DurationMs: 7_290_140}, {DurationMs: 4_017}, {DurationMs: 2_586_209}, {DurationMs: 78_342}}
	alignment := &Alignment{Files: []AlignedFile{
		file(2, 1, 7_200_000), file(2, 2, 90_120), file(4, 1, 2_586_175),
	}}
	placement, err := alignment.PlaceOnTracks(tracks)
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(placement.Track, []int{1, 1, 3}) || !reflect.DeepEqual(placement.StartMs, []int64{0, 7_200_000, 0}) ||
		placement.Unnarrated != 3 || placement.ByChapters != 0 {
		t.Fatalf("placement = %+v", placement)
	}
	if _, err := (&Alignment{Files: []AlignedFile{file(1, 1, 5), file(2, 1, 5)}}).PlaceOnTracks(tracks[:1]); !errors.Is(err, ErrAlignmentCount) {
		t.Fatalf("more narrated files than tracks: %v", err)
	}
}

// Two of Ender's Game's tracks are 608.280 s long. Lengths leave them open, and
// with tracks that nothing narrates among those left, the order of reading settles
// them only when it can settle them one way.
func TestPlaceOnTracksPairsFilesOfOneLengthByOrderOnlyWhenThatIsOnePairing(t *testing.T) {
	tracks := []AlignedTrack{{DurationMs: 600_000}, {DurationMs: 608_280}, {DurationMs: 500_000}, {DurationMs: 608_280}, {DurationMs: 300_000}}
	settled := &Alignment{Files: []AlignedFile{file(1, 1, 600_000), file(2, 1, 608_280), file(3, 1, 608_280)}}
	placement, err := settled.PlaceOnTracks(tracks)
	if err != nil || !reflect.DeepEqual(placement.Track, []int{0, 1, 3}) || !placement.ByOrder || placement.Unnarrated != 2 {
		t.Fatalf("placement = %+v, %v", placement, err)
	}
	// Three tracks of one length and two narrations of it: either two of the three.
	three := append([]AlignedTrack(nil), tracks...)
	three[4] = AlignedTrack{DurationMs: 608_280}
	if placement, err := settled.PlaceOnTracks(three); !errors.Is(err, ErrAlignmentAmbiguous) {
		t.Fatalf("two of three tracks of one length: %+v, %v", placement, err)
	}
}

// MatchSources pairs by order exactly as it did: the sources left open with the
// files left open, one for one, each where it fits.
func TestPairCandidatesByOrderNeedsTheOnePairing(t *testing.T) {
	for name, test := range map[string]struct {
		candidates [][]int
		targets    int
		want       []int
		err        error
	}{
		"settled by lengths":                  {[][]int{{0}, {1, 0}}, 2, []int{0, 1}, nil},
		"equal lengths, in order":             {[][]int{{0, 1}, {0, 1}}, 2, []int{0, 1}, nil},
		"an order the lengths contradict":     {[][]int{{1, 2}, {0, 1}, {0, 1, 2}}, 3, nil, ErrAlignmentAmbiguous},
		"in order around a target left over":  {[][]int{{0, 2}, {0, 2}}, 3, []int{0, 2}, nil},
		"two ways round a target left over":   {[][]int{{0, 1, 2}, {0, 1, 2}}, 3, nil, ErrAlignmentAmbiguous},
		"a source with no target left for it": {[][]int{{0}, {0}}, 2, nil, ErrAlignmentMismatch},
	} {
		t.Run(name, func(t *testing.T) {
			pairing, err := pairCandidates(test.candidates, test.targets)
			if !errors.Is(err, test.err) || (test.err == nil && !reflect.DeepEqual(pairing.File, test.want)) {
				t.Fatalf("pairing = %+v, %v; want %v, %v", pairing, err, test.want, test.err)
			}
		})
	}
}
