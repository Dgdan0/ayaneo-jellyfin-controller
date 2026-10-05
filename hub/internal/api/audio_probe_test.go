package api

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	readingdomain "ayaneohub/internal/reading"
)

func TestFindFFprobeLooksWhereFFmpegIsLookedForAndSaysWhichIsMissing(t *testing.T) {
	programFiles := t.TempDir()
	t.Setenv("PATH", t.TempDir()) // nothing here, so only the Jellyfin folders can answer
	t.Setenv("ProgramFiles", programFiles)

	if _, err := findFFprobe(); err == nil || err.Error() != "ffprobe executable was not found" {
		t.Fatalf("ffprobe with nothing installed: %v", err)
	}
	if _, err := findFFmpeg(); err == nil || err.Error() != "ffmpeg executable was not found" {
		t.Fatalf("ffmpeg with nothing installed: %v", err)
	}

	server := filepath.Join(programFiles, "Jellyfin", "Server")
	if err := os.MkdirAll(filepath.Join(server, "jellyfin-ffmpeg"), 0o755); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"ffprobe.exe", "ffmpeg.exe"} {
		if err := os.WriteFile(filepath.Join(server, name), []byte("x"), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	probe, err := findFFprobe()
	if err != nil || probe != filepath.Join(server, "ffprobe.exe") {
		t.Fatalf("ffprobe = %q, %v", probe, err)
	}
	mpeg, err := findFFmpeg()
	if err != nil || mpeg != filepath.Join(server, "ffmpeg.exe") {
		t.Fatalf("ffmpeg = %q, %v", mpeg, err)
	}

	// The bundled copy beside the server is the second place.
	if err := os.Remove(filepath.Join(server, "ffprobe.exe")); err != nil {
		t.Fatal(err)
	}
	bundled := filepath.Join(server, "jellyfin-ffmpeg", "ffprobe.exe")
	if err := os.WriteFile(bundled, []byte("x"), 0o755); err != nil {
		t.Fatal(err)
	}
	if probe, err := findFFprobe(); err != nil || probe != bundled {
		t.Fatalf("bundled ffprobe = %q, %v", probe, err)
	}
}

// ffprobe rejects -nostdin ("Option not found"), unlike ffmpeg, so a probe that
// carried it would fail every time and look like a missing tool. The command
// has no shell and no stdin (the null device) instead.
func TestFFprobeArgumentsForceTheContainerAndNeverGoThroughAShell(t *testing.T) {
	kind, _ := readingdomain.AudioKindOf("a.mp3")
	args := ffprobeArgs(`C:\Media\Book (1).mp3`, kind)
	for _, want := range [][]string{
		{"-v", "error"}, {"-f", "mp3"}, {"-protocol_whitelist", "file"}, {"-print_format", "json"},
	} {
		if !containsRun(args, want) {
			t.Errorf("args %v lack %v", args, want)
		}
	}
	for _, want := range []string{"-show_format", "-show_chapters", "-hide_banner"} {
		if !slices.Contains(args, want) {
			t.Errorf("args %v lack %s", args, want)
		}
	}
	if slices.Contains(args, "-nostdin") {
		t.Error("ffprobe does not take -nostdin")
	}
	if n := len(args); n < 2 || args[n-2] != "-i" || args[n-1] != `C:\Media\Book (1).mp3` {
		t.Errorf("the path must be the last argument, as one element: %v", args)
	}
	// A name that looks like an option cannot become one.
	args = ffprobeArgs("-version.mp3", kind)
	if n := len(args); args[n-2] != "-i" || args[n-1] != "-version.mp3" {
		t.Errorf("an option-like name is not passed after -i: %v", args)
	}
	m4b, _ := readingdomain.AudioKindOf("a.m4b")
	if !containsRun(ffprobeArgs("a.m4b", m4b), []string{"-f", "mov"}) {
		t.Error("an m4b is probed as the mov container")
	}
}

func containsRun(args, run []string) bool {
	for i := 0; i+len(run) <= len(args); i++ {
		if reflect.DeepEqual(args[i:i+len(run)], run) {
			return true
		}
	}
	return false
}

func TestParseFFprobeReadsDurationTrackTagAndChapters(t *testing.T) {
	got, err := parseFFprobe([]byte(`{
		"chapters": [
			{"id": 0, "start_time": "0.000000", "end_time": "600.500000", "tags": {"title": "Part One"}},
			{"id": 1, "start_time": "600.500000", "end_time": "1200.000000", "tags": {"TITLE": "Part Two"}},
			{"id": 2, "start_time": "1200.000000", "end_time": "1800.250000"}
		],
		"format": {"filename": "C:\\Media\\secret path\\a.m4b", "duration": "1800.250000", "tags": {"track": "3/8", "title": "x"}}
	}`))
	if err != nil {
		t.Fatal(err)
	}
	want := probedAudio{
		DurationMs: 1800250, Track: 3,
		Chapters: []probedChapter{{"Part One", 0, 600500}, {"Part Two", 600500, 1200000}, {"", 1200000, 1800250}},
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v\nwant %+v", got, want)
	}
}

func TestParseFFprobeTakesTheTrackNumberHoweverItIsWritten(t *testing.T) {
	for _, test := range []struct {
		tags string
		want int
	}{
		{`{"track": "1"}`, 1},
		{`{"track": "01"}`, 1},
		{`{"track": " 7 "}`, 7},
		{`{"track": "2/x"}`, 2},
		{`{"TRACK": "4/12"}`, 4},
		{`{"TRACKNUMBER": "5"}`, 5},
		{`{"tracknumber": "6/6"}`, 6},
		{`{"Track": "08"}`, 8},
		{`{"track": "0"}`, 0},
		{`{"track": "-3"}`, 0},
		{`{"track": "abc"}`, 0},
		{`{"track": ""}`, 0},
		{`{"track": "99999999999999999999"}`, 0},
		{`{"disc": "1"}`, 0},
		{`{}`, 0},
	} {
		got, err := parseFFprobe([]byte(`{"format": {"duration": "1.5", "tags": ` + test.tags + `}}`))
		if err != nil {
			t.Fatalf("%s: %v", test.tags, err)
		}
		if got.Track != test.want {
			t.Errorf("%s: track = %d, want %d", test.tags, got.Track, test.want)
		}
	}
}

func TestParseFFprobeSkipsWhatItCannotRead(t *testing.T) {
	if _, err := parseFFprobe([]byte(`not json`)); err == nil {
		t.Error("garbage was accepted")
	}
	got, err := parseFFprobe([]byte(`{}`))
	if err != nil || got.DurationMs != 0 || got.Track != 0 || len(got.Chapters) != 0 {
		t.Errorf("an empty answer = %+v, %v", got, err)
	}
	got, err = parseFFprobe([]byte(`{"format": {"duration": "N/A"}, "chapters": [
		{"start_time": "N/A", "end_time": "5.0"}, {"start_time": "1.0", "end_time": "2.0", "tags": {"title": "ok"}}]}`))
	if err != nil || got.DurationMs != 0 || !reflect.DeepEqual(got.Chapters, []probedChapter{{"ok", 1000, 2000}}) {
		t.Errorf("unreadable times: %+v, %v", got, err)
	}
	got, _ = parseFFprobe([]byte(`{"format": {"duration": "-5"}}`))
	if got.DurationMs != 0 {
		t.Errorf("a negative duration = %d", got.DurationMs)
	}
}

func probeServer(t *testing.T) *Server {
	t.Helper()
	return NewServer(libraryAPIConfig("", ""))
}

func TestProbeFileCachesBySizeAndModifiedTimeNotForever(t *testing.T) {
	server := probeServer(t)
	var calls atomic.Int32
	server.probeAudio = func(_ context.Context, path string) (probedAudio, error) {
		calls.Add(1)
		return probedAudio{DurationMs: int64(len(path))}, nil
	}
	ctx := context.Background()
	file := probeKey{path: "/media/a.mp3", size: 10, modNano: 100}

	first := server.probeFile(ctx, file)
	if first.DurationMs != int64(len(file.path)) || calls.Load() != 1 {
		t.Fatalf("first = %+v after %d calls", first, calls.Load())
	}
	if second := server.probeFile(ctx, file); !reflect.DeepEqual(first, second) || calls.Load() != 1 {
		t.Fatalf("an unchanged file was probed again: %d calls", calls.Load())
	}
	for _, changed := range []probeKey{
		{path: file.path, size: 11, modNano: 100},
		{path: file.path, size: 10, modNano: 101},
		{path: "/media/b.mp3", size: 10, modNano: 100},
	} {
		before := calls.Load()
		server.probeFile(ctx, changed)
		if calls.Load() != before+1 {
			t.Errorf("%+v was served from another file's answer", changed)
		}
	}
}

func TestProbeFileGivesNoInformationRatherThanAnErrorAndDoesNotRememberFailures(t *testing.T) {
	server := probeServer(t)
	var calls atomic.Int32
	server.probeAudio = func(context.Context, string) (probedAudio, error) {
		if calls.Add(1) == 1 {
			return probedAudio{DurationMs: 99, Track: 9}, errors.New("ffprobe executable was not found")
		}
		return probedAudio{DurationMs: 5}, nil
	}
	file := probeKey{path: "/media/a.mp3", size: 1, modNano: 1}
	if got := server.probeFile(context.Background(), file); !reflect.DeepEqual(got, probedAudio{}) {
		t.Fatalf("a failed probe = %+v, want nothing", got)
	}
	// The tool may have been installed since, or the failure was a timeout.
	if got := server.probeFile(context.Background(), file); got.DurationMs != 5 || calls.Load() != 2 {
		t.Fatalf("after a failure: %+v after %d calls", got, calls.Load())
	}
}

func TestProbeFileRunsAtMostFourAtOnceEachWithinFiveSeconds(t *testing.T) {
	server := probeServer(t)
	var running, peak atomic.Int32
	release := make(chan struct{})
	deadlines := make(chan time.Duration, 32)
	server.probeAudio = func(ctx context.Context, _ string) (probedAudio, error) {
		now := running.Add(1)
		for {
			seen := peak.Load()
			if now <= seen || peak.CompareAndSwap(seen, now) {
				break
			}
		}
		if deadline, ok := ctx.Deadline(); ok {
			deadlines <- time.Until(deadline)
		} else {
			deadlines <- -1
		}
		<-release
		running.Add(-1)
		return probedAudio{}, nil
	}

	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			server.probeFile(context.Background(), probeKey{path: "/media/" + string(rune('a'+i)) + ".mp3", size: 1, modNano: 1})
		}(i)
	}
	// Four start; the other eight wait for a slot.
	deadline := time.After(5 * time.Second)
	for running.Load() < 4 {
		select {
		case <-deadline:
			t.Fatalf("only %d started", running.Load())
		case <-time.After(5 * time.Millisecond):
		}
	}
	time.Sleep(100 * time.Millisecond)
	if got := running.Load(); got != 4 {
		t.Fatalf("%d probes ran at once, want 4", got)
	}
	close(release)
	wg.Wait()
	if peak.Load() != 4 {
		t.Fatalf("peak concurrency %d, want 4", peak.Load())
	}
	close(deadlines)
	for left := range deadlines {
		if left <= 4*time.Second || left > 5*time.Second {
			t.Errorf("a probe had %v to run, want about 5s", left)
		}
	}
}

func TestProbeFileStopsAfterItsTimeoutAndWhenItsCallerGivesUp(t *testing.T) {
	server := probeServer(t)
	server.probeTimeout = 40 * time.Millisecond
	server.probeAudio = func(ctx context.Context, _ string) (probedAudio, error) {
		<-ctx.Done()
		return probedAudio{DurationMs: 1}, ctx.Err()
	}
	started := time.Now()
	if got := server.probeFile(context.Background(), probeKey{path: "/media/a.mp3", size: 1, modNano: 1}); !reflect.DeepEqual(got, probedAudio{}) {
		t.Fatalf("a probe that timed out = %+v", got)
	}
	if elapsed := time.Since(started); elapsed < 30*time.Millisecond || elapsed > 2*time.Second {
		t.Fatalf("timed out after %v", elapsed)
	}

	// A caller that has given up does not take a slot or start a process.
	var calls atomic.Int32
	server.probeAudio = func(context.Context, string) (probedAudio, error) {
		calls.Add(1)
		return probedAudio{}, nil
	}
	for i := 0; i < cap(server.probeSlots); i++ {
		server.probeSlots <- struct{}{}
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		server.probeFile(ctx, probeKey{path: "/media/b.mp3", size: 1, modNano: 1})
		close(done)
	}()
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("a cancelled caller kept waiting for a slot")
	}
	if calls.Load() != 0 {
		t.Fatalf("a cancelled caller still ran the probe %d times", calls.Load())
	}
	for i := 0; i < cap(server.probeSlots); i++ {
		<-server.probeSlots
	}
}

// The real tool, when this machine has one, on a file the fixtures generate: it
// answers in the shape parseFFprobe expects, and the host path it prints in its
// "filename" field goes nowhere.
func TestRunFFprobeReadsAGeneratedFile(t *testing.T) {
	if _, err := findFFprobe(); err != nil {
		t.Skip("no ffprobe on this machine")
	}
	root := t.TempDir()
	manifest, err := readingdomain.GenerateFixtureSet(root)
	if err != nil {
		t.Fatal(err)
	}
	var mp3 string
	for _, asset := range manifest.Assets {
		if asset.Role == readingdomain.FixtureAudiobook {
			mp3 = filepath.Join(root, filepath.FromSlash(asset.Path))
		}
	}
	got, err := runFFprobe(context.Background(), mp3)
	if err != nil {
		t.Fatal(err)
	}
	if got.DurationMs < 500 || got.DurationMs > 2000 || got.Track != 0 || len(got.Chapters) != 0 {
		t.Fatalf("probe of the generated one-second narration = %+v", got)
	}
	if _, err := runFFprobe(context.Background(), filepath.Join(root, "missing.mp3")); err == nil {
		t.Fatal("a missing file probed without an error")
	}
	if _, err := runFFprobe(context.Background(), filepath.Join(root, "fixture-manifest.json")); err == nil || strings.Contains(err.Error(), root) {
		t.Fatalf("a non-audio file: %v", err)
	}
}

// What Jellyfin's ffprobe 7.1.3 printed for a chaptered M4B and a tagged MP3,
// with the file names changed: the key names and shapes the parser relies on.
const (
	realFFprobeM4B = `{"chapters": [
		{"id": 0, "time_base": "1/1000", "start": 0, "start_time": "0.000000", "end": 1000, "end_time": "1.000000", "tags": {"title": "Opening"}},
		{"id": 1, "time_base": "1/1000", "start": 1000, "start_time": "1.000000", "end": 2200, "end_time": "2.200000", "tags": {"title": "The Middle"}},
		{"id": 2, "time_base": "1/1000", "start": 2200, "start_time": "2.200000", "end": 3000, "end_time": "3.000000", "tags": {"title": ""}}],
	"format": {"filename": "chaptered.m4b", "nb_streams": 2, "nb_programs": 0, "nb_stream_groups": 0, "format_name": "mov,mp4,m4a,3gp,3g2,mj2",
		"format_long_name": "QuickTime / MOV", "start_time": "0.000000", "duration": "3.000000", "size": "14383", "bit_rate": "38354", "probe_score": 0,
		"tags": {"major_brand": "M4A ", "minor_version": "512", "compatible_brands": "M4A isomiso2", "title": "Chaptered Book", "encoder": "Lavf61.7.100", "track": "2/5"}}}`
	realFFprobeMP3 = `{"chapters": [
	],
	"format": {"filename": "tagged.mp3", "nb_streams": 1, "nb_programs": 0, "nb_stream_groups": 0, "format_name": "mp3",
		"format_long_name": "MP2/3 (MPEG audio layer 2/3)", "start_time": "0.025057", "duration": "2.037551", "size": "8414", "bit_rate": "33035", "probe_score": 0,
		"tags": {"track": "3/8", "title": "Track Three", "encoder": "Lavf61.7.100"}}}`
)

func TestParseFFprobeReadsWhatTheRealToolPrints(t *testing.T) {
	m4b, err := parseFFprobe([]byte(realFFprobeM4B))
	if err != nil {
		t.Fatal(err)
	}
	wantChapters := []probedChapter{{"Opening", 0, 1000}, {"The Middle", 1000, 2200}, {"", 2200, 3000}}
	if m4b.DurationMs != 3000 || m4b.Track != 2 || !reflect.DeepEqual(m4b.Chapters, wantChapters) {
		t.Fatalf("m4b = %+v", m4b)
	}
	mp3, err := parseFFprobe([]byte(realFFprobeMP3))
	if err != nil || mp3.DurationMs != 2038 || mp3.Track != 3 || len(mp3.Chapters) != 0 {
		t.Fatalf("mp3 = %+v, %v", mp3, err)
	}
}

// The same two files made and probed for real, when this machine has the tools:
// the forced container (-f mov for an .m4b) reads the chapters and the track tag.
func TestRunFFprobeReadsChaptersAndTagsFromRealFiles(t *testing.T) {
	ffmpeg, err := findFFmpeg()
	if err != nil {
		t.Skip("no ffmpeg on this machine")
	}
	if _, err := findFFprobe(); err != nil {
		t.Skip("no ffprobe on this machine")
	}
	dir := t.TempDir()
	metadata := filepath.Join(dir, "chapters.txt")
	chapters := strings.Join([]string{
		";FFMETADATA1", "title=Chaptered Book",
		"[CHAPTER]", "TIMEBASE=1/1000", "START=0", "END=1000", "title=Opening",
		"[CHAPTER]", "TIMEBASE=1/1000", "START=1000", "END=2200", "title=The Middle", "",
	}, "\n")
	if err := os.WriteFile(metadata, []byte(chapters), 0o600); err != nil {
		t.Fatal(err)
	}
	m4b, mp3 := filepath.Join(dir, "chaptered.m4b"), filepath.Join(dir, "tagged.mp3")
	for _, args := range [][]string{
		{"-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=3", "-i", metadata,
			"-map_metadata", "1", "-map_chapters", "1", "-c:a", "aac", "-b:a", "32k", "-metadata", "track=2/5", m4b},
		{"-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=330:duration=2",
			"-c:a", "libmp3lame", "-b:a", "32k", "-metadata", "track=3/8", mp3},
	} {
		if out, err := exec.Command(ffmpeg, args...).CombinedOutput(); err != nil {
			t.Skipf("this ffmpeg could not make the test file: %v %s", err, out)
		}
	}

	got, err := runFFprobe(context.Background(), m4b)
	if err != nil {
		t.Fatal(err)
	}
	if got.Track != 2 || got.DurationMs < 2900 || got.DurationMs > 3300 || len(got.Chapters) != 2 ||
		got.Chapters[0].Title != "Opening" || got.Chapters[0].StartMs != 0 || got.Chapters[1].Title != "The Middle" ||
		got.Chapters[1].StartMs != 1000 || got.Chapters[1].EndMs != 2200 {
		t.Fatalf("m4b = %+v", got)
	}
	got, err = runFFprobe(context.Background(), mp3)
	if err != nil || got.Track != 3 || got.DurationMs < 1900 || got.DurationMs > 2300 || len(got.Chapters) != 0 {
		t.Fatalf("mp3 = %+v, %v", got, err)
	}
}
