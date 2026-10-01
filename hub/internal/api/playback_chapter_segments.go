package api

import (
	"regexp"
	"strconv"
	"strings"
)

// Jellyfin answers media segments only when a segment provider plugin is
// installed, and this server has none: every item measured on 2026-10-02 had
// zero. Many files, the anime especially, carry named chapters instead --
// "OP", "Opening", "Intro", "ED", "Ending", "Credits", "Preview" -- which say
// the same thing. These are the names Jellyfin's own Chapter Segments
// Provider plugin recognises, written for a chapter title that is nothing
// else, so "Part A" or "Opening Night" never becomes an intro.
var chapterSegmentNames = []struct {
	kind    string
	pattern *regexp.Regexp
}{
	{"Recap", regexp.MustCompile(`(?i)^\s*(recap|previously( on.*)?|story so far)\s*$`)},
	{"Intro", regexp.MustCompile(`(?i)^\s*(op|opening( credits| theme| song)?|intro(duction)?|title sequence|main titles?|theme( song)?)\s*$`)},
	{"Outro", regexp.MustCompile(`(?i)^\s*(ed|ending( credits| theme| song)?|outro|credits|end credits|closing( credits)?|end titles?)\s*$`)},
	{"Preview", regexp.MustCompile(`(?i)^\s*(preview|next( episode| time)?( preview)?|coming up)\s*$`)},
}

// chapterSegmentKind is the segment a chapter's name declares, or "".
func chapterSegmentKind(name string) string {
	for _, candidate := range chapterSegmentNames {
		if candidate.pattern.MatchString(name) {
			return candidate.kind
		}
	}
	return ""
}

// segmentsOrChapters prefers Jellyfin's own segments and otherwise reads
// them from chapter names: a named chapter runs to the next chapter, or to
// the end of the video.
func segmentsOrChapters(segments []PlaybackSegment, chapters []PlaybackChapter, durationMillis int64) []PlaybackSegment {
	if len(segments) > 0 {
		return segments
	}
	derived := make([]PlaybackSegment, 0, 2)
	for index, chapter := range chapters {
		kind := chapterSegmentKind(chapter.Name)
		if kind == "" {
			continue
		}
		end := durationMillis
		if index+1 < len(chapters) {
			end = chapters[index+1].PositionMillis
		}
		if end <= chapter.PositionMillis {
			continue
		}
		derived = append(derived, PlaybackSegment{
			ID:          "chapter-" + strconv.Itoa(index) + "-" + strings.ToLower(kind),
			Type:        kind,
			StartMillis: chapter.PositionMillis,
			EndMillis:   end,
		})
	}
	return derived
}
