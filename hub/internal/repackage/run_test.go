package repackage

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// These tests run the real ffmpeg on small files they generate, and skip where
// there is none: the Jellyfin server's own copy, found as the hub finds it, or
// one on the PATH.

func tool(t *testing.T, name string) string {
	t.Helper()
	if path, err := exec.LookPath(name); err == nil {
		return path
	}
	if programFiles := os.Getenv("ProgramFiles"); programFiles != "" {
		for _, relative := range []string{
			filepath.Join("Jellyfin", "Server", name+".exe"),
			filepath.Join("Jellyfin", "Server", "jellyfin-ffmpeg", name+".exe"),
		} {
			candidate := filepath.Join(programFiles, relative)
			if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
				return candidate
			}
		}
	}
	t.Skipf("%s is not installed here", name)
	return ""
}

func generate(t *testing.T, args ...string) {
	t.Helper()
	ffmpeg := tool(t, "ffmpeg")
	command := exec.Command(ffmpeg, append([]string{"-nostdin", "-hide_banner", "-loglevel", "error", "-y"}, args...)...)
	if output, err := command.CombinedOutput(); err != nil {
		t.Skipf("this ffmpeg cannot make the sample (%v): %s", err, output)
	}
}

type probed struct {
	Streams []struct {
		Index       int               `json:"index"`
		CodecType   string            `json:"codec_type"`
		CodecName   string            `json:"codec_name"`
		CodecTag    string            `json:"codec_tag_string"`
		Profile     string            `json:"profile"`
		PixFmt      string            `json:"pix_fmt"`
		Channels    int               `json:"channels"`
		Tags        map[string]string `json:"tags"`
		Disposition map[string]int    `json:"disposition"`
	} `json:"streams"`
	Format struct {
		FormatName string `json:"format_name"`
	} `json:"format"`
}

func probe(t *testing.T, path string) probed {
	t.Helper()
	output, err := exec.Command(tool(t, "ffprobe"), "-v", "error", "-show_streams", "-show_format", "-of", "json", path).Output()
	if err != nil {
		t.Fatalf("ffprobe %s: %v", filepath.Base(path), err)
	}
	var result probed
	if err := json.Unmarshal(output, &result); err != nil {
		t.Fatal(err)
	}
	return result
}

// sourceFrom is the plan's view of a file, taken from ffprobe the way Jellyfin
// takes it: a stream's index is the index ffmpeg maps by.
func sourceFrom(t *testing.T, path string, defaultAudio int) Source {
	t.Helper()
	info := probe(t, path)
	source := Source{Container: strings.Split(info.Format.FormatName, ",")[0], DefaultAudio: defaultAudio, DurationSeconds: 6}
	if stat, err := os.Stat(path); err == nil {
		source.SizeBytes = stat.Size()
	}
	for _, stream := range info.Streams {
		kind := stream.CodecType
		converted := Stream{
			Index: stream.Index, Type: kind, Codec: stream.CodecName, Profile: stream.Profile, PixelFormat: stream.PixFmt,
			Language: stream.Tags["language"], Channels: stream.Channels, Label: stream.Tags["language"] + " " + stream.CodecName,
			Forced: stream.Disposition["forced"] == 1,
		}
		if strings.HasSuffix(stream.PixFmt, "10le") {
			converted.BitDepth = 10
		}
		source.Streams = append(source.Streams, converted)
	}
	return source
}

func fileHash(t *testing.T, path string) [32]byte {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return sha256.Sum256(data)
}

// sampleMKV is a short film as a library holds one: 10-bit HEVC, an E-AC-3 and a
// DTS track, an SRT and an ASS subtitle inside the file.
func sampleMKV(t *testing.T, dir string) string {
	t.Helper()
	srt := filepath.Join(dir, "en.srt")
	ass := filepath.Join(dir, "fr.ass")
	if err := os.WriteFile(srt, []byte("1\n00:00:00,500 --> 00:00:02,000\nHello there\n\n2\n00:00:02,500 --> 00:00:04,000\nSecond line\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(ass, []byte("[Script Info]\nScriptType: v4.00+\nPlayResX: 384\nPlayResY: 288\n\n[V4+ Styles]\n"+
		"Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n"+
		"Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1\n\n[Events]\n"+
		"Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"+
		"Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Bonjour {\\i1}le monde{\\i0}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	out := filepath.Join(dir, "film.mkv")
	generate(t,
		"-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=6",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6",
		"-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000:duration=6",
		"-i", srt, "-i", ass,
		"-map", "0:v", "-map", "1:a", "-map", "2:a", "-map", "3:s", "-map", "4:s",
		"-c:v", "libx265", "-preset", "ultrafast", "-x265-params", "log-level=none", "-pix_fmt", "yuv420p10le", "-tag:v", "hev1",
		"-c:a:0", "eac3", "-b:a:0", "384k", "-ac:a:0", "6",
		"-c:a:1", "dca", "-strict", "-2", "-b:a:1", "768k", "-ac:a:1", "6",
		"-c:s:0", "srt", "-c:s:1", "ass",
		"-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=rus",
		"-metadata:s:s:0", "language=eng", "-metadata:s:s:1", "language=fre",
		out)
	return out
}

// topLevelKinds lists the boxes a file starts with, to see whether the movie box
// comes before the media data.
func topLevelKinds(t *testing.T, path string) []string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var kinds []string
	for at := 0; at+8 <= len(data); {
		size := int(binary.BigEndian.Uint32(data[at:]))
		kinds = append(kinds, string(data[at+4:at+8]))
		switch size {
		case 0:
			return kinds
		case 1:
			size = int(binary.BigEndian.Uint64(data[at+8:]))
		}
		at += size
	}
	return kinds
}

func TestBuildCopiesWhatApplePlaysConvertsTheRestAndStatesTheTracks(t *testing.T) {
	ffmpeg := tool(t, "ffmpeg")
	dir := t.TempDir()
	source := sampleMKV(t, dir)
	sidecar := filepath.Join(dir, "he.srt")
	if err := os.WriteFile(sidecar, []byte("1\n00:00:00,500 --> 00:00:02,000\nשלום עולם\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	original := fileHash(t, source)

	info := sourceFrom(t, source, 2) // the DTS is the default audio
	external := Stream{Index: 5, Type: "subtitle", Codec: "subrip", Language: "heb", Label: "Hebrew - SubRip", External: true}
	info.Streams = append(info.Streams, external)
	plan, err := PlanApple(info)
	if err != nil {
		t.Fatal(err)
	}
	if plan.Video.Converted || !plan.Audio[1].Converted || plan.Audio[0].Converted {
		t.Fatalf("the plan is not the one this test is about: %+v", plan)
	}

	out := filepath.Join(dir, "out.mp4.part")
	var reported []int
	err = Build(context.Background(), Spec{
		FFmpeg: ffmpeg, Plan: plan, Out: out, Duration: 6 * time.Second, Encoder: X264,
		Inputs: Inputs{SourcePath: source, Subtitles: map[int]string{5: sidecar}},
	}, func(percent int) { reported = append(reported, percent) })
	if err != nil {
		t.Fatal(err)
	}

	got := probe(t, out)
	if !strings.Contains(got.Format.FormatName, "mp4") {
		t.Errorf("format = %q", got.Format.FormatName)
	}
	var kinds, codecs []string
	var languages []string
	for _, stream := range got.Streams {
		kinds = append(kinds, stream.CodecType)
		codecs = append(codecs, stream.CodecName)
		languages = append(languages, stream.Tags["language"])
	}
	if want := "video audio audio subtitle subtitle subtitle"; strings.Join(kinds, " ") != want {
		t.Fatalf("streams = %v, want %s", kinds, want)
	}
	if want := "hevc eac3 aac mov_text mov_text mov_text"; strings.Join(codecs, " ") != want {
		t.Errorf("codecs = %v, want %s", codecs, want)
	}
	if want := "und eng rus eng fra heb"; strings.Join(languages, " ") != want {
		t.Errorf("languages = %v, want %s", languages, want)
	}
	if got.Streams[0].CodecTag != "hvc1" || got.Streams[0].PixFmt != "yuv420p10le" {
		t.Errorf("the picture is %s %s, want hvc1 at ten bits", got.Streams[0].CodecTag, got.Streams[0].PixFmt)
	}
	if got.Streams[2].Channels != 6 || got.Streams[2].Profile != "LC" {
		t.Errorf("the converted DTS is %d channels %s, want 5.1 AAC-LC", got.Streams[2].Channels, got.Streams[2].Profile)
	}
	if title := got.Streams[1].Tags["handler_name"]; title != "eng eac3" {
		t.Errorf("the first audio track's title = %q", title)
	}

	// The movie box is ahead of the media data (+faststart), and the track headers
	// are the ones the patch writes.
	kindsOfBoxes := topLevelKinds(t, out)
	moov, mdat := -1, -1
	for at, kind := range kindsOfBoxes {
		if kind == "moov" && moov < 0 {
			moov = at
		}
		if kind == "mdat" && mdat < 0 {
			mdat = at
		}
	}
	if moov < 0 || mdat < 0 || moov > mdat {
		t.Errorf("boxes = %v, want moov before mdat", kindsOfBoxes)
	}
	data, _ := os.ReadFile(out)
	tracks := inspectTracks(t, data)
	wantFlags := []uint32{3, 2, 3, 2, 2, 2}
	wantGroups := []int16{0, 1, 1, 3, 3, 3}
	for at, track := range tracks {
		if track.flags != wantFlags[at] || track.alternate != wantGroups[at] {
			t.Errorf("track %d (%s): flags %d group %d, want %d and %d", at, track.handler, track.flags, track.alternate, wantFlags[at], wantGroups[at])
		}
	}

	if len(reported) == 0 {
		t.Error("no progress was reported")
	}
	for at, percent := range reported {
		if percent < 0 || percent > 99 || (at > 0 && percent < reported[at-1]) {
			t.Fatalf("progress %v: a percent outside 0-99 or one that went back", reported)
		}
	}
	if fileHash(t, source) != original {
		t.Fatal("the source file changed")
	}
}

func TestBuildConvertsAnOldVideoToEightBitH264(t *testing.T) {
	ffmpeg := tool(t, "ffmpeg")
	dir := t.TempDir()
	source := filepath.Join(dir, "old.avi")
	generate(t,
		"-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24:duration=3",
		"-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=3",
		"-c:v", "mpeg4", "-q:v", "5", "-c:a", "libmp3lame", "-b:a", "128k", source)
	plan, err := PlanApple(sourceFrom(t, source, -1))
	if err != nil {
		t.Fatal(err)
	}
	if !plan.Video.Converted || plan.Audio[0].Converted || !plan.GenPTS {
		t.Fatalf("plan = %+v", plan)
	}
	out := filepath.Join(dir, "old.mp4")
	if err := Build(context.Background(), Spec{
		FFmpeg: ffmpeg, Plan: plan, Out: out, Duration: 3 * time.Second, Encoder: X264,
		Inputs: Inputs{SourcePath: source},
	}, nil); err != nil {
		t.Fatal(err)
	}
	got := probe(t, out)
	if len(got.Streams) != 2 {
		t.Fatalf("%d streams", len(got.Streams))
	}
	if video := got.Streams[0]; video.CodecName != "h264" || video.CodecTag != "avc1" || video.PixFmt != "yuv420p" || video.Profile != "High" {
		t.Errorf("the picture is %s %s %s %s, want High-profile 8-bit avc1", video.CodecName, video.CodecTag, video.PixFmt, video.Profile)
	}
	if audio := got.Streams[1]; audio.CodecName != "mp3" {
		t.Errorf("the MP3 became %s: it should be copied", audio.CodecName)
	}
}

func TestBuildStopsWhenItsContextEnds(t *testing.T) {
	ffmpeg := tool(t, "ffmpeg")
	dir := t.TempDir()
	source := filepath.Join(dir, "long.avi")
	generate(t, "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=60", "-c:v", "mpeg4", "-q:v", "6", source)
	plan, err := PlanApple(sourceFrom(t, source, -1))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	started := time.Now()
	err = Build(ctx, Spec{
		FFmpeg: ffmpeg, Plan: plan, Out: filepath.Join(dir, "long.mp4"), Duration: 60 * time.Second, Encoder: X264,
		Inputs: Inputs{SourcePath: source},
	}, func(int) { cancel() })
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if took := time.Since(started); took > 30*time.Second {
		t.Fatalf("stopping took %v", took)
	}
}

func TestFFmpegOpensOnlyLocalFiles(t *testing.T) {
	ffmpeg := tool(t, "ffmpeg")
	err := RunFFmpeg(context.Background(), ffmpeg, []string{
		"-nostdin", "-hide_banner", "-loglevel", "error",
		"-protocol_whitelist", "file", "-i", "http://127.0.0.1:9/x.mkv", "-f", "null", "-",
	}, nil)
	var failure *FFmpegError
	if !errors.As(err, &failure) || !strings.Contains(strings.ToLower(failure.Stderr), "whitelist") {
		t.Fatalf("a URL as the source was not refused by the whitelist: %v", err)
	}
}

func TestBuildFailsOnAStreamTheSourceDoesNotHave(t *testing.T) {
	ffmpeg := tool(t, "ffmpeg")
	dir := t.TempDir()
	source := sampleMKV(t, dir)
	info := sourceFrom(t, source, -1)
	info.Streams = append(info.Streams, Stream{Index: 9, Type: "audio", Codec: "aac", Language: "eng", Channels: 2})
	plan, err := PlanApple(info)
	if err != nil {
		t.Fatal(err)
	}
	err = Build(context.Background(), Spec{
		FFmpeg: ffmpeg, Plan: plan, Out: filepath.Join(dir, "out.mp4"), Duration: 6 * time.Second, Encoder: X264,
		Inputs: Inputs{SourcePath: source},
	}, nil)
	var failure *FFmpegError
	if !errors.As(err, &failure) {
		t.Fatalf("err = %v, want an ffmpeg failure", err)
	}
}

func TestProgressLinesAreReadAsTimeWritten(t *testing.T) {
	for line, want := range map[string]time.Duration{
		"out_time_us=2000000": 2 * time.Second, "out_time_us=0": 0, "out_time_us= 1500000 ": 1500 * time.Millisecond,
	} {
		if got, ok := parseProgressLine(line); !ok || got != want {
			t.Errorf("%q = %v %v, want %v", line, got, ok, want)
		}
	}
	for _, line := range []string{"out_time_us=N/A", "out_time_us=-5", "frame=144", "progress=end", "out_time_ms=2000000", ""} {
		if _, ok := parseProgressLine(line); ok {
			t.Errorf("%q was read as progress", line)
		}
	}
}

func TestTheEncoderIsX264WhereNothingBetterAnswers(t *testing.T) {
	if got := DetectEncoder(context.Background(), filepath.Join(t.TempDir(), "no-such-ffmpeg")); got.Name != X264.Name {
		t.Fatalf("with no ffmpeg the encoder is %s", got.Name)
	}
	if got := DetectEncoder(context.Background(), tool(t, "ffmpeg")); got.Name != X264.Name && got.Name != NVENC.Name {
		t.Fatalf("the encoder is %s", got.Name)
	}
}

func TestTheTailOfWhatFFmpegSaidIsKeptAndBounded(t *testing.T) {
	var tail tailWriter
	for i := 0; i < 1000; i++ {
		_, _ = tail.Write([]byte("line " + strconv.Itoa(i) + "\n"))
	}
	got := tail.String()
	if len(got) > tailBytes || !strings.HasSuffix(got, "line 999") || bytes.Contains([]byte(got), []byte("line 0\n")) {
		t.Fatalf("tail = %d bytes ending %q", len(got), got[max(0, len(got)-20):])
	}
}
