package api

import "testing"

func TestNamedChaptersBecomeSegmentsWhenJellyfinHasNone(t *testing.T) {
	chapters := []PlaybackChapter{
		{Name: "Prologue", PositionMillis: 0},
		{Name: "Opening", PositionMillis: 199_000},
		{Name: "Part A", PositionMillis: 289_000},
		{Name: "Ending", PositionMillis: 1_207_000},
		{Name: "Preview", PositionMillis: 1_297_000},
	}
	got := segmentsOrChapters(nil, chapters, 1_360_000)
	want := []PlaybackSegment{
		{Type: "Intro", StartMillis: 199_000, EndMillis: 289_000},
		{Type: "Outro", StartMillis: 1_207_000, EndMillis: 1_297_000},
		{Type: "Preview", StartMillis: 1_297_000, EndMillis: 1_360_000},
	}
	if len(got) != len(want) {
		t.Fatalf("segments = %+v", got)
	}
	for i := range want {
		if got[i].Type != want[i].Type || got[i].StartMillis != want[i].StartMillis || got[i].EndMillis != want[i].EndMillis {
			t.Fatalf("segment %d = %+v, want %+v", i, got[i], want[i])
		}
	}
}

func TestOnlyWholeChapterNamesCount(t *testing.T) {
	for name, kind := range map[string]string{
		"OP": "Intro", "Intro": "Intro", "Opening Credits": "Intro", "ED": "Outro", "Credits": "Outro",
		"End Credits": "Outro", "Previously On": "Recap", "Next Episode": "Preview",
		"Part A": "", "Opening Night": "", "Chapter 3": "", "The Ending of Things": "", "Edward": "",
	} {
		if got := chapterSegmentKind(name); got != kind {
			t.Errorf("chapterSegmentKind(%q) = %q, want %q", name, got, kind)
		}
	}
}

func TestJellyfinsOwnSegmentsWin(t *testing.T) {
	own := []PlaybackSegment{{Type: "Intro", StartMillis: 1, EndMillis: 2}}
	got := segmentsOrChapters(own, []PlaybackChapter{{Name: "Credits", PositionMillis: 10}}, 20)
	if len(got) != 1 || got[0].StartMillis != 1 {
		t.Fatalf("segments = %+v", got)
	}
}
