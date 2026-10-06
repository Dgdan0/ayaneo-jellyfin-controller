package repackage

import (
	"slices"
	"strings"
	"testing"
)

// The ffmpeg command is built from the plan, one -map per stream the plan keeps,
// so what the manifest promised is what the command asks for and nothing else:
// no stream is carried along because it happened to be there.

func hevcFilm() Plan {
	plan, err := PlanApple(Source{
		Container: "mkv", SizeBytes: 5_000_000_000, DurationSeconds: 5400, DefaultAudio: 2,
		Streams: []Stream{
			video(0, "hevc", "Main 10", 10, "yuv420p10le"),
			audio(1, "dts", "rus", 6), audio(2, "eac3", "eng", 6),
			subtitle(3, "subrip", "eng"), subtitle(4, "hdmv_pgs_subtitle", "fre"),
			func() Stream { s := subtitle(5, "subrip", "heb"); s.External = true; return s }(),
		},
	})
	if err != nil {
		panic(err)
	}
	return plan
}

var hevcFilmInputs = Inputs{SourcePath: `E:\Videos\film.mkv`, Subtitles: map[int]string{5: `C:\work\sub-5.srt`}}

func mustArgs(t *testing.T, plan Plan, in Inputs, out string, encoder Encoder) []string {
	t.Helper()
	args, err := BuildArgs(plan, in, out, encoder)
	if err != nil {
		t.Fatal(err)
	}
	return args
}

// has reports whether the arguments hold these words next to each other, in order.
func has(args []string, words ...string) bool {
	for at := 0; at+len(words) <= len(args); at++ {
		if slices.Equal(args[at:at+len(words)], words) {
			return true
		}
	}
	return false
}

func TestArgsCopyWhatApplePlaysAndTagTheHEVC(t *testing.T) {
	args := mustArgs(t, hevcFilm(), hevcFilmInputs, `C:\cache\out.part`, X264)
	for _, want := range [][]string{
		{"-c:v", "copy"}, {"-tag:v", "hvc1"},
		{"-c:a:1", "copy"}, {"-c:s", "mov_text"},
		{"-movflags", "+faststart"}, {"-f", "mp4"},
		{"-map_metadata", "-1"}, {"-map_chapters", "0"},
	} {
		if !has(args, want...) {
			t.Errorf("arguments lack %v: %v", want, args)
		}
	}
	if args[len(args)-1] != `C:\cache\out.part` {
		t.Errorf("the output is not last: %q", args[len(args)-1])
	}
	if has(args, "-c:v", "libx264") || has(args, "-c:v", "h264_nvenc") {
		t.Error("a copied video was given an encoder")
	}
	if !has(args, "-y") || !has(args, "-nostdin") || !has(args, "-progress", "pipe:1") {
		t.Errorf("a batch run needs -y, -nostdin and a progress pipe: %v", args)
	}
}

func TestArgsMapEachStreamThePlanKeepsAndNoOther(t *testing.T) {
	args := mustArgs(t, hevcFilm(), hevcFilmInputs, "out.part", X264)
	var maps []string
	for at, word := range args {
		if word == "-map" {
			maps = append(maps, args[at+1])
		}
	}
	// The picture, both audio tracks in order, the embedded text subtitle, and the
	// sidecar (input 1). The picture subtitle (stream 4) is not asked for.
	want := []string{"0:0", "0:1", "0:2", "0:3", "1:0"}
	if !slices.Equal(maps, want) {
		t.Fatalf("maps = %v, want %v", maps, want)
	}
	// No bare -map 0 and no -c copy that would carry along what was left out.
	if has(args, "-map", "0") || has(args, "-c", "copy") || has(args, "-codec", "copy") {
		t.Errorf("a catch-all crept in: %v", args)
	}
}

func TestArgsOpenOnlyLocalFilesAndTheSidecarsInPlanOrder(t *testing.T) {
	args := mustArgs(t, hevcFilm(), hevcFilmInputs, "out.part", X264)
	var inputs []string
	for at, word := range args {
		if word != "-i" {
			continue
		}
		inputs = append(inputs, args[at+1])
		if at < 2 || args[at-2] != "-protocol_whitelist" || args[at-1] != "file" {
			t.Errorf("input %q is not preceded by -protocol_whitelist file", args[at+1])
		}
	}
	if !slices.Equal(inputs, []string{hevcFilmInputs.SourcePath, `C:\work\sub-5.srt`}) {
		t.Fatalf("inputs = %v", inputs)
	}
}

func TestArgsConvertAudioAtThePlannedRateAndKeepTheRest(t *testing.T) {
	args := mustArgs(t, hevcFilm(), hevcFilmInputs, "out.part", X264)
	for _, want := range [][]string{
		{"-c:a:0", "aac"}, {"-b:a:0", "384k"}, {"-ac:a:0", "6"}, // the DTS
		{"-c:a:1", "copy"}, // the E-AC-3
	} {
		if !has(args, want...) {
			t.Errorf("arguments lack %v: %v", want, args)
		}
	}
	if has(args, "-b:a:1") || has(args, "-ac:a:1") {
		t.Error("a copied audio track was given a rate or a channel count")
	}
}

func TestArgsConvertVideoWithTheEncoderChosenAndNoHEVCTag(t *testing.T) {
	plan, err := PlanApple(Source{Container: "avi", SizeBytes: 1, DurationSeconds: 60, DefaultAudio: -1,
		Streams: []Stream{video(0, "mpeg4", "", 0, ""), audio(1, "mp3", "eng", 2)}})
	if err != nil {
		t.Fatal(err)
	}
	for name, encoder := range map[string]Encoder{"x264": X264, "nvenc": NVENC} {
		args := mustArgs(t, plan, Inputs{SourcePath: "src.avi"}, "out.part", encoder)
		if !has(args, "-c:v", encoder.Name) {
			t.Errorf("%s: the video is not encoded with %s: %v", name, encoder.Name, args)
		}
		if has(args, "-c:v", "copy") || has(args, "-tag:v", "hvc1") {
			t.Errorf("%s: a converted video was copied or tagged as HEVC: %v", name, args)
		}
		if !has(args, "-pix_fmt", "yuv420p") || !has(args, "-profile:v", "high") {
			t.Errorf("%s: the converted video is not 8-bit High: %v", name, args)
		}
		if !has(args, "-tag:v", "avc1") {
			t.Errorf("%s: the converted video is not tagged avc1: %v", name, args)
		}
		if !has(args, "-fflags", "+genpts") {
			t.Errorf("%s: an AVI was not asked to make timestamps: %v", name, args)
		}
	}
	// An MKV is not asked to.
	if has(mustArgs(t, hevcFilm(), hevcFilmInputs, "out.part", X264), "-fflags", "+genpts") {
		t.Error("an MKV was asked to make timestamps")
	}
}

func TestArgsNameEachTrackWithItsLanguageDefaultAndFlags(t *testing.T) {
	forced := subtitle(6, "subrip", "spa")
	forced.Forced = true
	forced.HearingImpaired = true
	plan, err := PlanApple(Source{Container: "mkv", SizeBytes: 1, DurationSeconds: 60, DefaultAudio: 2,
		Streams: []Stream{
			video(0, "h264", "High", 8, "yuv420p"),
			audio(1, "aac", "fre", 2), audio(2, "ac3", "heb", 6),
			subtitle(5, "subrip", "eng"), forced,
		}})
	if err != nil {
		t.Fatal(err)
	}
	args := mustArgs(t, plan, Inputs{SourcePath: "src.mkv"}, "out.part", X264)
	for _, want := range [][]string{
		{"-metadata:s:a:0", "language=fra"}, {"-metadata:s:a:1", "language=heb"},
		{"-disposition:a:0", "0"}, {"-disposition:a:1", "default"},
		{"-metadata:s:s:0", "language=eng"}, {"-metadata:s:s:1", "language=spa"},
		{"-disposition:s:0", "0"}, {"-disposition:s:1", "forced+hearing_impaired"},
	} {
		if !has(args, want...) {
			t.Errorf("arguments lack %v: %v", want, args)
		}
	}
}

func handlerName(args []string, stream string) string {
	for at, word := range args {
		if word == stream && at+1 < len(args) && strings.HasPrefix(args[at+1], "handler_name=") {
			return strings.TrimPrefix(args[at+1], "handler_name=")
		}
	}
	return "<none>"
}

func TestArgsKeepATitleAsOneArgumentWhateverItHolds(t *testing.T) {
	titled := func(label string) []string {
		track := audio(1, "aac", "heb", 2)
		track.Label = label
		plan, err := PlanApple(Source{Container: "mkv", SizeBytes: 1, DurationSeconds: 60, DefaultAudio: -1,
			Streams: []Stream{video(0, "h264", "High", 8, "yuv420p"), track}})
		if err != nil {
			t.Fatal(err)
		}
		return mustArgs(t, plan, Inputs{SourcePath: "src.mkv"}, "out.part", X264)
	}
	if got := handlerName(titled("עברית - AAC - Stereo\nsecond line;\"quoted\""), "-metadata:s:a:0"); got != `עברית - AAC - Stereo second line;"quoted"` {
		t.Errorf("title = %q, want the Hebrew title with its line break turned into a space", got)
	}
	if got := handlerName(titled(strings.Repeat("x", 500)), "-metadata:s:a:0"); len(got) > 100 || len(got) == 0 {
		t.Errorf("a title of %d bytes was passed on", len(got))
	}
	// A track with no label gets no title rather than an empty one.
	if got := handlerName(titled("  \t "), "-metadata:s:a:0"); got != "<none>" {
		t.Errorf("an empty label was passed on as %q", got)
	}
}

func TestArgsAreNotBuiltWithoutASidecarThePlanPromised(t *testing.T) {
	// The plan promised the Hebrew sidecar; with no file for it the command cannot
	// keep that promise, so it is not built.
	if _, err := BuildArgs(hevcFilm(), Inputs{SourcePath: "src.mkv"}, "out.part", X264); err == nil {
		t.Fatal("a command was built without the sidecar the plan promised")
	}
	if _, err := BuildArgs(hevcFilm(), Inputs{}, "out.part", X264); err == nil {
		t.Fatal("a command was built without a source")
	}
}
