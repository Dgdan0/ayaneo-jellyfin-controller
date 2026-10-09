package reading

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestRealReadalongPacks is the read-only sweep of every pack on this PC (#66). It runs only when
// POCKETDS_READALONG_SWEEP names the packs' folder (D:\Media\ReadingDerived\Storyteller\readalong)
// and POCKETDS_STORYTELLER_ASSETS the folder Storyteller's /data/assets is (its read-along editions
// are read from there, never written). POCKETDS_FFPROBE, when set, measures each audio piece, and
// POCKETDS_READALONG_SWEEP_OUT, when set, receives each book's word copy as the Pocket would get it
// (for the app's own sweep) and summary.json. Nothing is served and no hub is asked.
func TestRealReadalongPacks(t *testing.T) {
	packs, assets := os.Getenv("POCKETDS_READALONG_SWEEP"), os.Getenv("POCKETDS_STORYTELLER_ASSETS")
	if packs == "" || assets == "" {
		t.Skip("set POCKETDS_READALONG_SWEEP and POCKETDS_STORYTELLER_ASSETS to sweep the real packs")
	}
	out := os.Getenv("POCKETDS_READALONG_SWEEP_OUT")
	folders, err := os.ReadDir(packs)
	if err != nil {
		t.Fatal(err)
	}
	var results []sweepResult
	for _, folder := range folders {
		if !folder.IsDir() || !packUUID.MatchString(folder.Name()) {
			continue
		}
		result := sweepPack(t, filepath.Join(packs, folder.Name()), folder.Name(), assets, out)
		results = append(results, result)
		line, _ := json.Marshal(result)
		t.Logf("%s", line)
	}
	if out != "" {
		data, _ := json.MarshalIndent(results, "", " ")
		if err := os.WriteFile(filepath.Join(out, "summary.json"), data, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	for _, result := range results {
		if result.Used && len(result.Problems) > 0 {
			t.Errorf("%s is used and has problems: %v", result.Title, result.Problems)
		}
	}
}

type sweepResult struct {
	UUID, Title string
	// Used: the hub would serve this pack's words (FindPack holds, both sets read and narrate the
	// edition's pieces). Reason says why not.
	Used   bool
	Reason string
	// Storyteller's sentences, the sentence set's, the word set's pars.
	EditionSentences, Sentences, WordPars int
	// Every <text> id of each set found in the text it names (in the copy the app gets, for the words).
	MissingIDs int
	// Clips that end before they begin, in the pack's SMIL as written.
	BackwardClips int
	// Pieces whose last clipEnd differs from the edition's, per set.
	SentencePiecesDiffer, WordPiecesDiffer int
	// Word-set pieces that stop short of the edition's end (a word left out past the end of the audio): told, not wrong.
	WordPiecesShort int
	// The edition's narrated pieces and the source files they make up: what the manifest maps onto
	// the tracks, unchanged when no piece differs.
	Pieces, Sources int
	// Layout: how the hub maps them, "files" (each source one of the book's files) or placed on the tracks.
	Layout string
	// Pieces that end past their audio as ffprobe measures it (when it is there).
	PiecesPastAudio int
	ProbedPieces    int
	// Word clips past the end of their audio as ffprobe measures it: in the copy the Pocket gets (0 wanted), and in the
	// pack as wordsync wrote it (which the hub mends).
	CopyClipsPastAudio, PackClipsPastAudio int
	// The word copy (audio omitted, restyled) as the Pocket would get it.
	CopyBytes, CopyHeld int64
	CopyOverlaid        int
	// Word clips the hub ended at their audio's end (or gave no length) in the copy.
	EndedAtAudio, CopyMended int
	Seconds                  float64
	Problems                 []string
}

var smilClip = regexp.MustCompile(`clipBegin="([^"]*)"[^>]*clipEnd="([^"]*)"`)
var elementID = regexp.MustCompile(`\sid="([^"]+)"`)
var smilClipSource = regexp.MustCompile(`src="([^"]+)" clipBegin="([^"]*)" clipEnd="([^"]*)"`)

func sweepPack(t *testing.T, dir, uuid, assets, out string) (result sweepResult) {
	started := time.Now()
	result = sweepResult{UUID: uuid}
	problem := func(format string, args ...any) {
		result.Problems = append(result.Problems, fmt.Sprintf(format, args...))
	}
	defer func() { result.Seconds = time.Since(started).Seconds() }()
	data, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		result.Reason = "no manifest"
		return result
	}
	var manifest PackManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		result.Reason = "manifest unreadable"
		return result
	}
	result.Title = manifest.Title
	remote, ok := strings.CutPrefix(manifest.Source.Path, "/data/assets/")
	if !ok {
		result.Reason = "source outside /data/assets"
		return result
	}
	editionPath := filepath.Join(assets, filepath.FromSlash(remote))
	handle, err := os.Open(editionPath)
	if err != nil {
		result.Reason = "edition not found"
		return result
	}
	defer handle.Close()
	info, _ := handle.Stat()
	edition := MediaFile{ReadOnlyFile: handle, Path: editionPath, Size: info.Size(), ModTime: info.ModTime()}
	pack, reason := readPack(dir, uuid, edition, time.Now())
	if pack == nil {
		result.Reason = reason
		return result
	}
	sentenceSet, wordSet := pack.Overlay(GranularitySentence), pack.Overlay(GranularityWord)
	if sentenceSet == nil {
		result.Reason = "no sentence set: " + pack.Skipped[GranularitySentence]
		return result
	}
	plain, err := ReadAlignment(handle, edition.Size)
	if err != nil {
		result.Reason = "edition unreadable: " + err.Error()
		return result
	}
	sentences, err := ReadOverlaidAlignment(handle, edition.Size, sentenceSet)
	if err != nil {
		result.Reason = "sentence set unreadable: " + err.Error()
		return result
	}
	if wordSet == nil {
		// The word set cannot be used; the sentence set still is, when it narrates the edition's pieces.
		for _, file := range plain.Files {
			result.EditionSentences += len(file.Pars)
		}
		for _, file := range sentences.Files {
			result.Sentences += len(file.Pars)
		}
		result.SentencePiecesDiffer = piecesDiffer(plain, sentences)
		result.Used = sentences.SamePieces(plain)
		result.Reason = "sentences only: the word set " + pack.Skipped[GranularityWord]
		return result
	}
	words, err := ReadOverlaidAlignment(handle, edition.Size, wordSet)
	if err != nil {
		result.Reason = "word set unreadable: " + err.Error()
		return result
	}
	count := func(a *Alignment) (n int) {
		for _, file := range a.Files {
			n += len(file.Pars)
		}
		return
	}
	result.EditionSentences, result.Sentences, result.WordPars = count(plain), count(sentences), count(words)
	// Clips the hub ends where the edition says a piece ends (a pack that copied a last clipEnd the aligner ran past): told, not wrong.
	result.EndedAtAudio = words.PastEnd + words.CutAtEnd
	result.SentencePiecesDiffer, result.WordPiecesDiffer = piecesDiffer(plain, sentences), piecesPast(plain, words)
	result.WordPiecesShort = piecesDiffer(plain, words) - result.WordPiecesDiffer
	result.Pieces = len(plain.Files)
	if sources, err := plain.Sources(); err == nil {
		result.Sources, result.Layout = len(sources), "files"
	} else {
		result.Layout = "pieces placed on the tracks (" + err.Error() + ")"
	}
	// Clips as written, in both sets.
	for _, set := range []*Overlay{sentenceSet, wordSet} {
		for _, name := range set.Names() {
			if !strings.HasSuffix(strings.ToLower(name), ".smil") {
				continue
			}
			smil, err := set.read(name)
			if err != nil {
				problem("%s %s unreadable", set.Granularity, name)
				continue
			}
			for _, match := range smilClip.FindAllSubmatch(smil, -1) {
				begin, beginErr := parseClock(string(match[1]))
				end, endErr := parseClock(string(match[2]))
				if beginErr != nil || endErr != nil || end < begin {
					result.BackwardClips++
				}
			}
		}
	}
	// The word copy the Pocket gets: the audio left out, restyled, mended as the hub mends.
	plan, err := PlanReadingEPUB(handle, edition.Size, CopyOptions{OmitAudio: true, Restyle: true, MendNarration: true, Overlay: wordSet, MaxHeld: 64 << 20})
	if err != nil {
		result.Reason = "word copy: " + err.Error()
		return result
	}
	result.CopyBytes, result.CopyHeld, result.CopyOverlaid, result.CopyMended = plan.Size, plan.Held(), plan.Report.Overlaid, plan.Report.Mended
	var copied bytes.Buffer
	if _, err := io.Copy(&copied, plan.Reader(handle)); err != nil {
		t.Fatal(err)
	}
	copyZip, err := zip.NewReader(bytes.NewReader(copied.Bytes()), int64(copied.Len()))
	if err != nil {
		problem("the word copy is no archive: %v", err)
		return result
	}
	ids := map[string]map[string]bool{}
	idsOf := func(document string, from func(string) ([]byte, error)) map[string]bool {
		if found, done := ids[document]; done {
			return found
		}
		found := map[string]bool{}
		if text, err := from(document); err == nil {
			for _, match := range elementID.FindAllSubmatch(text, -1) {
				found[string(match[1])] = true
			}
		}
		ids[document] = found
		return found
	}
	copyEntries := map[string]*zip.File{}
	for _, entry := range copyZip.File {
		copyEntries[entry.Name] = entry
	}
	fromCopy := func(name string) ([]byte, error) {
		entry := copyEntries[name]
		if entry == nil {
			return nil, os.ErrNotExist
		}
		stream, err := entry.Open()
		if err != nil {
			return nil, err
		}
		defer stream.Close()
		return io.ReadAll(stream)
	}
	for _, file := range words.Files {
		for _, par := range file.Pars {
			if !idsOf(par.Text, fromCopy)[par.Fragment] {
				result.MissingIDs++
			}
		}
	}
	for _, file := range sentences.Files {
		for _, par := range file.Pars {
			if !idsOf(par.Text, fromCopy)[par.Fragment] {
				result.MissingIDs++
			}
		}
	}
	// The SMIL the Pocket reads is the pack's, byte for byte.
	for _, name := range wordSet.Names() {
		if !strings.HasSuffix(strings.ToLower(name), ".smil") {
			continue
		}
		want, _ := wordSet.read(name)
		got, err := fromCopy(name)
		if err != nil || plan.Report.Mended == 0 && !bytes.Equal(got, want) {
			problem("%s is not the pack's in the copy", name)
		}
	}
	// Each piece's narration against the audio itself, when ffprobe is there.
	if ffprobe := os.Getenv("POCKETDS_FFPROBE"); ffprobe != "" {
		audioDir := filepath.Join(filepath.Dir(filepath.Dir(editionPath)), "transcoded audio")
		lengths := map[string]float64{}
		for _, file := range plain.Files {
			seconds, err := probeSeconds(ffprobe, filepath.Join(audioDir, path.Base(file.Entry)))
			if err != nil {
				continue
			}
			lengths[path.Base(file.Entry)] = seconds * 1000
			result.ProbedPieces++
			if float64(file.LengthMs) > seconds*1000+50 {
				result.PiecesPastAudio++
			}
		}
		// Clips that run on past the audio they name: in the copy the Pocket gets (mended by the hub), and in the pack as written.
		pastIn := func(documents map[string][]byte) int {
			past := 0
			for _, data := range documents {
				for _, match := range smilClipSource.FindAllSubmatch(data, -1) {
					begin, _ := parseClock(string(match[2]))
					end, _ := parseClock(string(match[3]))
					if length, known := lengths[path.Base(string(match[1]))]; known && end > begin && float64(end) > length+50 {
						past++
					}
				}
			}
			return past
		}
		copySMIL, packSMIL := map[string][]byte{}, map[string][]byte{}
		for _, name := range wordSet.Names() {
			if strings.HasSuffix(strings.ToLower(name), ".smil") {
				copySMIL[name], _ = fromCopy(name)
				packSMIL[name], _ = wordSet.read(name)
			}
		}
		result.CopyClipsPastAudio, result.PackClipsPastAudio = pastIn(copySMIL), pastIn(packSMIL)
		if result.CopyClipsPastAudio > 0 {
			problem("%d clips of the word copy run past their audio", result.CopyClipsPastAudio)
		}
	}
	if result.MissingIDs > 0 {
		problem("%d ids missing", result.MissingIDs)
	}
	if result.BackwardClips > 0 {
		problem("%d backward clips", result.BackwardClips)
	}
	if result.SentencePiecesDiffer+result.WordPiecesDiffer > 0 {
		problem("pieces differ: %d sentence, %d word", result.SentencePiecesDiffer, result.WordPiecesDiffer)
	}
	if result.PiecesPastAudio > 0 {
		problem("%d pieces end past their audio", result.PiecesPastAudio)
	}
	result.Used = sentences.SamePieces(plain) && words.NarratesWithin(plain)
	if !result.Used {
		result.Reason = "pieces_differ"
	}
	if out != "" {
		name := strings.Map(func(r rune) rune {
			if strings.ContainsRune(`<>:"/\|?*`, r) {
				return '_'
			}
			return r
		}, manifest.Title)
		if err := os.WriteFile(filepath.Join(out, name+".word.epub"), copied.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
		// And the sentence copy, what an app that does not ask for words gets, for the app's sweep to set beside it.
		var sentenceCopy bytes.Buffer
		if _, err := WriteReadingEPUB(&sentenceCopy, handle, edition.Size, CopyOptions{OmitAudio: true, Restyle: true, MendNarration: true, Overlay: sentenceSet}); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(out, name+".sentence.epub"), sentenceCopy.Bytes(), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return result
}

// piecesPast is how many of a's pieces b leaves out, adds, or runs past the end of.
func piecesPast(a, b *Alignment) int {
	lengths := map[string]int64{}
	for _, file := range a.Files {
		lengths[file.Entry] = file.LengthMs
	}
	past := 0
	seen := map[string]bool{}
	for _, file := range b.Files {
		seen[file.Entry] = true
		if length, found := lengths[file.Entry]; !found || file.LengthMs > length {
			past++
		}
	}
	for entry := range lengths {
		if !seen[entry] {
			past++
		}
	}
	return past
}

// piecesDiffer is how many of a's pieces b narrates otherwise: missing, added or ending elsewhere.
func piecesDiffer(a, b *Alignment) int {
	lengths := map[string]int64{}
	for _, file := range a.Files {
		lengths[file.Entry] = file.LengthMs
	}
	differ := 0
	seen := map[string]bool{}
	for _, file := range b.Files {
		seen[file.Entry] = true
		if length, found := lengths[file.Entry]; !found || length != file.LengthMs {
			differ++
		}
	}
	for entry := range lengths {
		if !seen[entry] {
			differ++
		}
	}
	return differ
}

func probeSeconds(ffprobe, file string) (float64, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	output, err := exec.CommandContext(ctx, ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", file).Output()
	if err != nil {
		return 0, err
	}
	return strconv.ParseFloat(strings.TrimSpace(string(output)), 64)
}
