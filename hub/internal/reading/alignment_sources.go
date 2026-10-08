package reading

import (
	"fmt"
	"sort"
)

// Which file of the book each narrated audio file is a piece of, and where in it.
// Storyteller numbers the files of a book NNNNN and cuts each into pieces CCCCC:
// at its chapters when it has them (Dune's one M4B is 00001-00001 to 00001-00018,
// its eighteen chapters), otherwise into stretches of about two hours (Mistborn's
// two 12-hour files are seven pieces each). A piece with nothing narrated in it
// (an opening credit, a part's title read in six seconds) is not in the edition at
// all, and neither is a file of the book with nothing narrated (Ender's Game's last
// four tracks). The hub's files are matched to the narration by length, never by a
// number, and a pairing that lengths cannot settle is refused.

// AlignedSource is one of the book's files as the narration saw it: Storyteller
// numbers its files and cuts a long one into chunks, each of them an audio file
// in the edition.
type AlignedSource struct {
	Number int
	// Files are the indexes of its chunks in Alignment.Files, in chunk order, and
	// Chunks their numbers; ChunkStartMs is where each begins inside the source when
	// they run one after another from the first, as their running sum.
	Files        []int
	Chunks       []int
	ChunkStartMs []int64
	LengthMs     int64
}

// contiguous says the chunks run 1, 2, 3 … with none missing.
func (s AlignedSource) contiguous() bool {
	for position, chunk := range s.Chunks {
		if chunk != position+1 {
			return false
		}
	}
	return true
}

// sourceGroups groups the audio files by the file of the book they are a piece
// of, in the order of their numbers, each with its chunks in order. A name that is
// not Storyteller's, a chunk named twice or a chunk with nothing narrated is not an
// alignment this can read; chunks need not run one after another.
func (a *Alignment) sourceGroups() ([]AlignedSource, error) {
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
		if file.LengthMs <= 0 {
			return nil, badf("a chunk narrates nothing")
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
			if position > 0 && file.Chunk == source.Chunks[position-1] {
				return nil, badf("two audio files are the same chunk of one file")
			}
			source.Chunks = append(source.Chunks, file.Chunk)
			source.ChunkStartMs = append(source.ChunkStartMs, source.LengthMs)
			source.LengthMs += file.LengthMs
		}
		sources = append(sources, source)
	}
	return sources, nil
}

// Sources groups the audio files by the file of the book they are a piece of.
// A name that is not Storyteller's, a gap in the chunks, or a chunk with nothing
// narrated is not an alignment this can read.
func (a *Alignment) Sources() ([]AlignedSource, error) {
	sources, err := a.sourceGroups()
	if err != nil {
		return nil, err
	}
	for _, source := range sources {
		if !source.contiguous() {
			return nil, badf("a file's chunks are not numbered one after another")
		}
	}
	return sources, nil
}

// Pairing is which file of the book each narrated source is.
type Pairing struct {
	// File is, for each source, the index of its file among the lengths given.
	File []int
	// ByOrder says lengths could not settle every pair, and the pairs they left open
	// were made by order.
	ByOrder bool
}

// MatchSources pairs each narrated file with the file of the book it is, by
// length: a source's narrated length against a file's own, within 250 ms and 15
// ms for each chunk, since a chunk's last sentence ends a few milliseconds from
// the end of the audio it was cut from (12 ms short on Dark Matter's files, about
// 10 ms long for each chunk on Mistborn's, 69 ms over seven). The pairing must be
// complete and one to one, because a wrong one puts every sentence of a file in
// another.
//
// Lengths come first, and a file only one narration can be settles that
// narration. When they leave files open (Mistborn's two parts are 12:20:13 and
// 12:20:13, and what was narrated of them differs by 14 ms) the narration took the
// book's files in the order it reads them, so sources, which are in the order of
// their numbers, are paired with the files left open in the order the files are
// given, which is the order they are played in. That is taken only where it fits:
// a source whose length is not the length of the file its place would give it
// leaves the pairing ambiguous, which is refused.
//
// The result is, for each source, the index of its file in durationsMs.
func MatchSources(sources []AlignedSource, durationsMs []int64) (Pairing, error) {
	if len(sources) == 0 || len(sources) != len(durationsMs) {
		return Pairing{}, ErrAlignmentCount
	}
	candidates := make([][]int, len(sources))
	for i, source := range sources {
		tolerance := runToleranceMs(len(source.Files))
		for j, duration := range durationsMs {
			if abs64(source.LengthMs-duration) <= tolerance {
				candidates[i] = append(candidates[i], j)
			}
		}
		if len(candidates[i]) == 0 {
			return Pairing{}, ErrAlignmentMismatch
		}
	}
	return pairCandidates(candidates, len(durationsMs))
}

// pairCandidates makes the one pairing of sources to targets that their candidates
// allow (candidates[i] are the targets source i can be, in order): a target only
// one source can be, or a source with one target left, settles that pair, again
// and again. What that leaves open is paired by order, the sources in the order
// of their numbers with the open targets in the order they are given (the order
// they are played in), each with a target after the one before it; and only when
// there is one such pairing: the earliest and the latest pairing by order are the
// same. There may be more targets than sources (a file of the book that nothing
// narrates); the targets left over are not narrated.
func pairCandidates(candidates [][]int, targets int) (Pairing, error) {
	assigned := make([]int, len(candidates))
	for i := range assigned {
		assigned[i] = -1
	}
	taken := map[int]bool{}
	for changed := true; changed; {
		changed = false
		for i := range candidates {
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
				return Pairing{}, ErrAlignmentMismatch
			case 1:
				assigned[i], taken[remaining[0]] = remaining[0], true
				changed = true
			}
		}
		// A target that only one source can be settles that source, too.
		for j := 0; j < targets; j++ {
			if taken[j] {
				continue
			}
			only, count := -1, 0
			for i := range candidates {
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
	var open, free []int
	for i, j := range assigned {
		if j < 0 {
			open = append(open, i)
		}
	}
	if len(open) == 0 {
		return Pairing{File: assigned}, nil
	}
	for j := 0; j < targets; j++ {
		if !taken[j] {
			free = append(free, j)
		}
	}
	fits := func(i, j int) bool {
		for _, candidate := range candidates[i] {
			if candidate == j {
				return true
			}
		}
		return false
	}
	earliest, latest := make([]int, len(open)), make([]int, len(open))
	at := -1
	for k, i := range open {
		for at++; at < len(free) && !fits(i, free[at]); at++ {
		}
		if at >= len(free) {
			return Pairing{}, ErrAlignmentAmbiguous
		}
		earliest[k] = at
	}
	at = len(free)
	for k := len(open) - 1; k >= 0; k-- {
		for at--; at >= 0 && !fits(open[k], free[at]); at-- {
		}
		if at < 0 {
			return Pairing{}, ErrAlignmentAmbiguous
		}
		latest[k] = at
	}
	for k := range open {
		if earliest[k] != latest[k] {
			return Pairing{}, ErrAlignmentAmbiguous
		}
	}
	for k, i := range open {
		assigned[i] = free[earliest[k]]
	}
	return Pairing{File: assigned, ByOrder: true}, nil
}

// AlignedTrack is one of the book's tracks as the narration may lie on it: its
// length and, when it has two or more, where its chapters begin (a lone M4B's
// chapters as Storyteller lists them, or a file's own marks), in order.
type AlignedTrack struct {
	DurationMs int64
	ChapterMs  []int64
}

// Placement is where each of the narration's audio files lies in the tracks, by
// its index in Alignment.Files: the track (an index into the tracks given) and
// where in it the file begins.
type Placement struct {
	Track   []int
	StartMs []int64
	// ByOrder: lengths left some sources open, and the order settled them.
	ByOrder bool
	// ByChapters is how many sources lie on their track's chapters; the others are
	// pieces that run one after another from the track's start.
	ByChapters int
	// Unnarrated is how many tracks no narration lies on.
	Unnarrated int
}

// What a piece may differ from the chapter it is cut at, and what the pieces of a
// file that run one after another may differ, in all, from the file. A piece cut
// at a chapter is measured on its own, so its error does not add up: the 400
// pieces of this library's books cut at chapters are within 46 ms of theirs. The
// pieces of a running sum carry each other's errors, and each piece is a cut that
// may add a few milliseconds, so the sum is allowed what MatchSources allows a
// source: 250 ms and 15 ms a piece, which grows with the pieces and not with the
// hours (Mistborn's seven pieces of 12 h 20 min are 69 ms over; a second of
// error, allowed for every hour of a long file, would put a sentence a second
// from where it is spoken). The same rule as MatchSources, so a narration that
// lengths leave ambiguous there is not settled here by a looser one.
const pieceToleranceMs = 250

func runToleranceMs(pieces int) int64 {
	return int64(250 + 15*pieces)
}

// PlaceOnTracks lays the narration over the book's tracks when its files are not
// the book's files one for one (MatchSources): a source is one track, its pieces
// either cut at that track's chapters, each piece the length of its chapter and
// beginning where it begins (chunk N is chapter N, and a chapter with nothing
// narrated has no piece), or running one after another from the track's start,
// every chunk there and their total the track's length. Chapters are preferred,
// since each piece is then placed on its own and nothing adds up. Every source
// must lie on a track, one track each, as pairCandidates settles them; a track may
// have no narration. Anything else is refused: the narration is never placed where
// the lengths do not say it is.
func (a *Alignment) PlaceOnTracks(tracks []AlignedTrack) (Placement, error) {
	sources, err := a.sourceGroups()
	if err != nil {
		return Placement{}, err
	}
	if len(sources) > len(tracks) {
		return Placement{}, fmt.Errorf("%w: %d narrated files for %d tracks", ErrAlignmentCount, len(sources), len(tracks))
	}
	type layout struct {
		starts     []int64
		byChapters bool
	}
	layouts := make([]map[int]layout, len(sources))
	candidates := make([][]int, len(sources))
	for i, source := range sources {
		layouts[i] = map[int]layout{}
		for j, track := range tracks {
			if starts, ok := a.onChapters(source, track); ok {
				layouts[i][j] = layout{starts, true}
			} else if source.contiguous() && track.DurationMs > 0 &&
				abs64(source.LengthMs-track.DurationMs) <= runToleranceMs(len(source.Files)) {
				layouts[i][j] = layout{source.ChunkStartMs, false}
			} else {
				continue
			}
			candidates[i] = append(candidates[i], j)
		}
		if len(candidates[i]) == 0 {
			return Placement{}, fmt.Errorf("%w: narrated file %d (%d pieces, %d ms) lies on no track", ErrAlignmentMismatch, source.Number, len(source.Files), source.LengthMs)
		}
	}
	pairing, err := pairCandidates(candidates, len(tracks))
	if err != nil {
		return Placement{}, err
	}
	placement := Placement{Track: make([]int, len(a.Files)), StartMs: make([]int64, len(a.Files)), ByOrder: pairing.ByOrder, Unnarrated: len(tracks) - len(sources)}
	for i, source := range sources {
		track := pairing.File[i]
		chosen := layouts[i][track]
		if chosen.byChapters {
			placement.ByChapters++
		}
		for k, file := range source.Files {
			placement.Track[file], placement.StartMs[file] = track, chosen.starts[k]
		}
	}
	return placement, nil
}

// onChapters places a source's pieces at the chapters of a track, piece N at
// chapter N, when every piece is the length of its chapter.
func (a *Alignment) onChapters(source AlignedSource, track AlignedTrack) ([]int64, bool) {
	chapters := track.ChapterMs
	if len(chapters) < 2 || source.Chunks[len(source.Chunks)-1] > len(chapters) {
		return nil, false
	}
	starts := make([]int64, len(source.Files))
	for k, index := range source.Files {
		chapter := source.Chunks[k] - 1
		end := track.DurationMs
		if chapter+1 < len(chapters) {
			end = chapters[chapter+1]
		}
		if end <= chapters[chapter] || abs64(a.Files[index].LengthMs-(end-chapters[chapter])) > pieceToleranceMs {
			return nil, false
		}
		starts[k] = chapters[chapter]
	}
	return starts, true
}

func abs64(value int64) int64 {
	if value < 0 {
		return -value
	}
	return value
}
