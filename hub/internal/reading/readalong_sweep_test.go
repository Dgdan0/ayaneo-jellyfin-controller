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
	// The edition's narrated pieces and the source files they make up: what the manifest maps onto
	// the tracks, unchanged when no piece differs.
	Pieces, Sources int
	// Layout: how the hub maps them, "files" (each source one of the book's files) or placed on the tracks.
	Layout string
	// Pieces that end past their audio as ffprobe measures it (when it is there).
	PiecesPastAudio int
	ProbedPieces    int
	// The word copy (audio omitted, restyled) as the Pocket would get it.
	CopyBytes, CopyHeld int64
	CopyOverlaid        int
	Seconds             float64
	Problems            []string
}

var smilClip = regexp.MustCompile(`clipBegin="([^"]*)"[^>]*clipEnd="([^"]*)"`)
var elementID = regexp.MustCompile(`\sid="([^"]+)"`)

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
	if sentenceSet == nil || wordSet == nil {
		result.Reason = "a set is missing"
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
	result.SentencePiecesDiffer, result.WordPiecesDiffer = piecesDiffer(plain, sentences), piecesDiffer(plain, words)
	result.Pieces = len(plain.Files)
	if sources, err := plain.Sources(); err == nil {
		result.Sources, result.Layout = len(sources), "files"
	} else {
		result.Layout = "pieces placed on the tracks (" + err.Error() + ")"
	}
	if words.PastEnd+words.CutAtEnd+sentences.PastEnd+sentences.CutAtEnd > 0 {
		problem("past the end: %d/%d sentences, %d/%d words", sentences.PastEnd, sentences.CutAtEnd, words.PastEnd, words.CutAtEnd)
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
	result.CopyBytes, result.CopyHeld, result.CopyOverlaid = plan.Size, plan.Held(), plan.Report.Overlaid
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
		if err != nil || !bytes.Equal(got, want) {
			problem("%s is not the pack's in the copy", name)
		}
	}
	// Each piece's narration against the audio itself, when ffprobe is there.
	if ffprobe := os.Getenv("POCKETDS_FFPROBE"); ffprobe != "" {
		audioDir := filepath.Join(filepath.Dir(filepath.Dir(editionPath)), "transcoded audio")
		for _, file := range plain.Files {
			seconds, err := probeSeconds(ffprobe, filepath.Join(audioDir, path.Base(file.Entry)))
			if err != nil {
				continue
			}
			result.ProbedPieces++
			if float64(file.LengthMs) > seconds*1000+50 {
				result.PiecesPastAudio++
			}
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
	result.Used = sentences.SamePieces(plain) && words.SamePieces(plain)
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
	}
	return result
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
