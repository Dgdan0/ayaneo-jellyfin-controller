package api

// An aligned audiobook's chapters, from the book. An audiobook's own files say
// little about its chapters: Dark Matter is eight parts of about 75 minutes and
// The Final Empire two of 12 h 20 min, none with a mark inside. A book with a
// read-along edition knows more, because the edition's table of contents names its
// chapters and its narration says where each sentence is spoken
// (internal/reading/contents.go places each entry on a narrated file). With each
// narrated file matched to a track (reading_audio_alignment.go), an entry is a
// place in the audiobook: a track and a moment in it.
//
// An entry that the narration does not place, a cover or a copyright page, is not
// a chapter. The chapters stay in the order the contents lists them and in the
// order they are heard, and an entry that cannot be both is left out.

import (
	"fmt"
	"sort"
)

// minBookChapters is how many chapters a book's contents must give for them to
// stand for its chapters. One is a book that spans itself, as one mark is a file
// that does (chapterMarks).
const minBookChapters = 2

// bookChapters lists the edition's chapters as places in the tracks, each with
// source "book". A chapter that would begin outside its track (trackMs are the
// tracks' lengths) cannot be played to and is left out. What is left is the most
// chapters that, in the order the contents lists them, are heard one after another
// and never twice at one moment: a displaced entry is the one that goes. Mistborn's
// front matter is spoken in the middle of the second part, so an entry that is
// listed among it and heard there is not a chapter of the book it is listed with,
// and keeping it would end the list at that point.
func (a *audioAlignment) bookChapters(trackMs []int64) []ReadingAudioChapter {
	narrated := a.narration.Chapters()
	chapters := make([]ReadingAudioChapter, 0, len(narrated))
	for _, chapter := range narrated {
		place := a.places[chapter.File]
		start := place.startMs + chapter.Par.BeginMs
		if place.track < 0 || place.track >= len(trackMs) || start >= trackMs[place.track] {
			continue
		}
		chapters = append(chapters, ReadingAudioChapter{Title: chapter.Title, StartMs: start, Track: place.track, Source: chapterSourceBook})
	}
	chapters = inListeningOrder(chapters)
	for i := range chapters {
		if chapters[i].Title == "" {
			chapters[i].Title = fmt.Sprintf("Chapter %d", i+1)
		}
	}
	return chapters
}

// heardBefore says whether one place of the audiobook comes before another.
func heardBefore(left, right ReadingAudioChapter) bool {
	if left.Track != right.Track {
		return left.Track < right.Track
	}
	return left.StartMs < right.StartMs
}

// inListeningOrder keeps the longest run of chapters, in the order they are given,
// whose places are strictly later one after another: the longest strictly
// increasing subsequence. Of entries at one moment the earlier in the contents
// stays, so a part and its first chapter, which begin together, are one chapter
// named for the part.
func inListeningOrder(chapters []ReadingAudioChapter) []ReadingAudioChapter {
	// tails[k] is the last chapter of the best run of length k+1 seen so far: the
	// one that ends earliest in the audio.
	var tails []int
	before := make([]int, len(chapters))
	for i, chapter := range chapters {
		k := sort.Search(len(tails), func(k int) bool { return !heardBefore(chapters[tails[k]], chapter) })
		if k < len(tails) && !heardBefore(chapter, chapters[tails[k]]) {
			// The same moment as a chapter that is already there, which came first.
			continue
		}
		before[i] = -1
		if k > 0 {
			before[i] = tails[k-1]
		}
		if k == len(tails) {
			tails = append(tails, i)
		} else {
			tails[k] = i
		}
	}
	if len(tails) == 0 {
		return nil
	}
	kept := make([]ReadingAudioChapter, len(tails))
	for i, at := len(tails)-1, tails[len(tails)-1]; i >= 0; i, at = i-1, before[at] {
		kept[i] = chapters[at]
	}
	return kept
}

// trackLengths are the tracks' lengths in the order they are played.
func (p *audioPlan) trackLengths() []int64 {
	lengths := make([]int64, len(p.tracks))
	for i, track := range p.tracks {
		lengths[i] = track.DurationMs
	}
	return lengths
}
