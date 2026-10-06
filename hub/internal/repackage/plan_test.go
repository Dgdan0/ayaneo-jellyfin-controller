package repackage

import (
	"errors"
	"testing"
)

// What goes into an Apple download's MP4, decided from the streams the source
// has (#5). It is a pure function: the manifest tells the app this plan, and the
// ffmpeg command is built from the same plan, so the promise and the file cannot
// drift apart.

func video(index int, codec, profile string, depth int, pixel string) Stream {
	return Stream{Index: index, Type: "video", Codec: codec, Profile: profile, BitDepth: depth, PixelFormat: pixel, Width: 1920, Height: 1080}
}

func audio(index int, codec, language string, channels int) Stream {
	return Stream{Index: index, Type: "audio", Codec: codec, Language: language, Channels: channels, Label: language + " " + codec}
}

func subtitle(index int, codec, language string) Stream {
	return Stream{Index: index, Type: "subtitle", Codec: codec, Language: language, Label: language + " " + codec}
}

func source(streams ...Stream) Source {
	return Source{Container: "mkv", SizeBytes: 4_000_000_000, DurationSeconds: 5400, DefaultAudio: -1, Streams: streams}
}

func TestVideoThatApplePlaysIsCopiedAndTagged(t *testing.T) {
	for _, test := range []struct {
		name       string
		stream     Stream
		wantCodec  string
		wantTag    string
		wantReason string
	}{
		{"h264 8-bit", video(0, "h264", "High", 8, "yuv420p"), "h264", "avc1", ""},
		{"h264 with nothing said of its depth", video(0, "h264", "Main", 0, ""), "h264", "avc1", ""},
		{"h264 full range", video(0, "h264", "High", 8, "yuvj420p"), "h264", "avc1", ""},
		{"hevc 8-bit", video(0, "hevc", "Main", 8, "yuv420p"), "hevc", "hvc1", ""},
		{"hevc 10-bit", video(0, "hevc", "Main 10", 10, "yuv420p10le"), "hevc", "hvc1", ""},
		{"hevc as h265", video(0, "h265", "Main 10", 10, ""), "hevc", "hvc1", ""},
	} {
		t.Run(test.name, func(t *testing.T) {
			plan, err := PlanApple(source(test.stream, audio(1, "aac", "eng", 2)))
			if err != nil {
				t.Fatal(err)
			}
			got := plan.Video
			if got.Converted || got.OutputCodec != test.wantCodec || got.Tag != test.wantTag || got.Reason != "" {
				t.Fatalf("video = %+v, want a copy as %s tagged %s", got, test.wantCodec, test.wantTag)
			}
			if got.SourceIndex != 0 || got.Width != 1920 || got.Height != 1080 {
				t.Fatalf("video lost where it came from: %+v", got)
			}
		})
	}
}

func TestVideoThatAppleCannotPlayIsConvertedToEightBitH264(t *testing.T) {
	for _, test := range []struct {
		name       string
		stream     Stream
		wantReason string
	}{
		{"xvid", video(0, "mpeg4", "Advanced Simple Profile", 8, "yuv420p"), "unsupported_codec"},
		{"divx three", video(0, "msmpeg4v3", "", 0, ""), "unsupported_codec"},
		{"vc-1", video(0, "vc1", "Advanced", 8, "yuv420p"), "unsupported_codec"},
		{"wmv", video(0, "wmv3", "", 0, ""), "unsupported_codec"},
		{"mpeg-2", video(0, "mpeg2video", "Main", 8, "yuv420p"), "unsupported_codec"},
		{"av1", video(0, "av1", "Main", 10, "yuv420p10le"), "unsupported_codec"},
		{"vp9", video(0, "vp9", "Profile 0", 8, "yuv420p"), "unsupported_codec"},
		{"h264 at ten bits", video(0, "h264", "High 10", 10, "yuv420p10le"), "unsupported_profile"},
		{"h264 at ten bits by profile alone", video(0, "h264", "High 10", 0, ""), "unsupported_profile"},
		{"h264 4:2:2", video(0, "h264", "High 4:2:2", 8, "yuv422p"), "unsupported_profile"},
		{"h264 4:4:4", video(0, "h264", "High 4:4:4 Predictive", 8, "yuv444p"), "unsupported_profile"},
		{"hevc 4:4:4", video(0, "hevc", "Rext", 10, "yuv444p10le"), "unsupported_profile"},
		{"hevc at twelve bits", video(0, "hevc", "Rext", 12, "yuv420p12le"), "unsupported_profile"},
	} {
		t.Run(test.name, func(t *testing.T) {
			plan, err := PlanApple(source(test.stream, audio(1, "aac", "eng", 2)))
			if err != nil {
				t.Fatal(err)
			}
			got := plan.Video
			if !got.Converted || got.OutputCodec != "h264" || got.Tag != "avc1" || got.Reason != test.wantReason {
				t.Fatalf("video = %+v, want converted to h264 for %s", got, test.wantReason)
			}
		})
	}
}

func TestACoverPictureIsNotTheVideo(t *testing.T) {
	cover := video(0, "mjpeg", "", 8, "yuvj420p")
	plan, err := PlanApple(source(cover, video(1, "h264", "High", 8, "yuv420p"), audio(2, "aac", "eng", 2)))
	if err != nil {
		t.Fatal(err)
	}
	if plan.Video.SourceIndex != 1 {
		t.Fatalf("the video is stream %d, want 1", plan.Video.SourceIndex)
	}
	if _, err := PlanApple(source(cover, audio(1, "aac", "eng", 2))); !errors.Is(err, ErrNoVideo) {
		t.Fatalf("a file with only a cover picture planned as %v, want ErrNoVideo", err)
	}
	if _, err := PlanApple(source(audio(0, "aac", "eng", 2))); !errors.Is(err, ErrNoVideo) {
		t.Fatalf("a file with no picture planned as %v, want ErrNoVideo", err)
	}
}

func TestAudioApplePlaysIsCopiedAndTheRestBecomesAAC(t *testing.T) {
	for _, test := range []struct {
		codec      string
		channels   int
		converted  bool
		wantCodec  string
		wantOutCh  int
		wantKbps   int
		wantReason string
	}{
		{"aac", 2, false, "aac", 2, 0, ""},
		{"ac3", 6, false, "ac3", 6, 0, ""},
		{"eac3", 6, false, "eac3", 6, 0, ""},
		{"mp3", 2, false, "mp3", 2, 0, ""},
		{"dts", 6, true, "aac", 6, 384, "unsupported_codec"},
		{"dts", 8, true, "aac", 6, 384, "unsupported_codec"},
		{"truehd", 8, true, "aac", 6, 384, "unsupported_codec"},
		{"flac", 2, true, "aac", 2, 192, "unsupported_codec"},
		{"opus", 2, true, "aac", 2, 192, "unsupported_codec"},
		{"opus", 1, true, "aac", 1, 96, "unsupported_codec"},
		{"vorbis", 2, true, "aac", 2, 192, "unsupported_codec"},
		{"pcm_s16le", 2, true, "aac", 2, 192, "unsupported_codec"},
		{"mp2", 2, true, "aac", 2, 192, "unsupported_codec"},
		{"dts", 0, true, "aac", 2, 192, "unsupported_codec"},
		{"dts", 5, true, "aac", 5, 320, "unsupported_codec"},
		{"dts", 7, true, "aac", 6, 384, "unsupported_codec"},
	} {
		plan, err := PlanApple(source(video(0, "h264", "High", 8, "yuv420p"), audio(1, test.codec, "eng", test.channels)))
		if err != nil {
			t.Fatal(err)
		}
		if len(plan.Audio) != 1 {
			t.Fatalf("%s: %d audio tracks", test.codec, len(plan.Audio))
		}
		got := plan.Audio[0]
		if got.Converted != test.converted || got.OutputCodec != test.wantCodec || got.OutputChannels != test.wantOutCh ||
			got.BitRateKbps != test.wantKbps || got.Reason != test.wantReason {
			t.Errorf("%s %d channels: %+v, want converted=%v %s %d channels %d kbps (%s)",
				test.codec, test.channels, got, test.converted, test.wantCodec, test.wantOutCh, test.wantKbps, test.wantReason)
		}
	}
}

func TestEveryAudioTrackIsKeptInSourceOrderWithOneDefault(t *testing.T) {
	plan, err := PlanApple(Source{
		Container: "mkv", SizeBytes: 5_000_000_000, DurationSeconds: 3600, DefaultAudio: 3,
		Streams: []Stream{
			video(0, "hevc", "Main", 8, "yuv420p"),
			audio(1, "eac3", "eng", 6), audio(2, "dts", "rus", 6), audio(3, "ac3", "heb", 2),
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Audio) != 3 {
		t.Fatalf("%d audio tracks", len(plan.Audio))
	}
	var order []int
	var defaults []int
	for _, track := range plan.Audio {
		order = append(order, track.SourceIndex)
		if track.Default {
			defaults = append(defaults, track.SourceIndex)
		}
	}
	if order[0] != 1 || order[1] != 2 || order[2] != 3 {
		t.Fatalf("order = %v, want the source's 1 2 3", order)
	}
	if len(defaults) != 1 || defaults[0] != 3 {
		t.Fatalf("defaults = %v, want only the source's default, 3", defaults)
	}
}

func TestTheDefaultAudioFallsBackToTheFlaggedOneThenTheFirst(t *testing.T) {
	flagged := audio(2, "ac3", "eng", 6)
	flagged.Default = true
	plan, _ := PlanApple(source(video(0, "h264", "High", 8, "yuv420p"), audio(1, "aac", "rus", 2), flagged))
	if plan.Audio[0].Default || !plan.Audio[1].Default {
		t.Fatalf("flagged default not used: %+v", plan.Audio)
	}
	plan, _ = PlanApple(source(video(0, "h264", "High", 8, "yuv420p"), audio(1, "aac", "rus", 2), audio(2, "ac3", "eng", 6)))
	if !plan.Audio[0].Default || plan.Audio[1].Default {
		t.Fatalf("first track is not the default: %+v", plan.Audio)
	}
	// A default index that names no audio track (a subtitle's, a stale one) is no default.
	plan, _ = PlanApple(Source{Container: "mkv", DefaultAudio: 9, SizeBytes: 1, Streams: []Stream{
		video(0, "h264", "High", 8, "yuv420p"), audio(1, "aac", "rus", 2), audio(2, "ac3", "eng", 6)}})
	if !plan.Audio[0].Default || plan.Audio[1].Default {
		t.Fatalf("a default that names nothing broke the choice: %+v", plan.Audio)
	}
}

func TestAFileWithoutAudioPlansWithoutAnyAndNoDefault(t *testing.T) {
	plan, err := PlanApple(source(video(0, "h264", "High", 8, "yuv420p")))
	if err != nil {
		t.Fatal(err)
	}
	if len(plan.Audio) != 0 {
		t.Fatalf("audio = %+v", plan.Audio)
	}
}

func TestTextSubtitlesComeAcrossAndPictureOnesAreSaidToBeLeftOut(t *testing.T) {
	external := subtitle(7, "subrip", "heb")
	external.External = true
	forced := subtitle(4, "ass", "eng")
	forced.Forced = true
	sdh := subtitle(5, "webvtt", "eng")
	sdh.HearingImpaired = true
	plan, err := PlanApple(source(
		video(0, "h264", "High", 8, "yuv420p"), audio(1, "aac", "eng", 2),
		subtitle(2, "subrip", "eng"), subtitle(3, "hdmv_pgs_subtitle", "fre"), forced, sdh,
		subtitle(6, "dvd_subtitle", "spa"), external, subtitle(8, "mov_text", "ita"),
		subtitle(9, "dvb_subtitle", "ger"), subtitle(10, "eia_608", "eng"), subtitle(11, "ttml", "eng"),
		subtitle(12, "ssa", "jpn"), subtitle(13, "srt", "eng"), subtitle(14, "text", "eng"),
	))
	if err != nil {
		t.Fatal(err)
	}
	want := []struct {
		index     int
		available bool
		reason    string
		language  string
	}{
		{2, true, "", "eng"}, {3, false, "picture_subtitle", "fra"}, {4, true, "", "eng"}, {5, true, "", "eng"},
		{6, false, "picture_subtitle", "spa"}, {7, true, "", "heb"}, {8, true, "", "ita"},
		{9, false, "picture_subtitle", "deu"}, {10, false, "unsupported_format", "eng"}, {11, false, "unsupported_format", "eng"},
		{12, true, "", "jpn"}, {13, true, "", "eng"}, {14, true, "", "eng"},
	}
	if len(plan.Subtitles) != len(want) {
		t.Fatalf("%d subtitles, want %d: %+v", len(plan.Subtitles), len(want), plan.Subtitles)
	}
	for i, w := range want {
		got := plan.Subtitles[i]
		if got.SourceIndex != w.index || got.Available != w.available || got.Reason != w.reason || got.Language != w.language {
			t.Errorf("subtitle %d = %+v, want index %d available=%v reason=%q language=%q", i, got, w.index, w.available, w.reason, w.language)
		}
		if got.Available && got.OutputCodec != "mov_text" {
			t.Errorf("subtitle %d comes across as %q, want mov_text", i, got.OutputCodec)
		}
		if !got.Available && got.OutputCodec != "" {
			t.Errorf("subtitle %d is left out and still has an output codec %q", i, got.OutputCodec)
		}
	}
	byIndex := map[int]Subtitle{}
	for _, track := range plan.Subtitles {
		byIndex[track.SourceIndex] = track
	}
	if !byIndex[4].Forced || !byIndex[5].HearingImpaired || !byIndex[7].External || byIndex[2].External || byIndex[2].Forced {
		t.Errorf("flags were not carried: %+v", byIndex)
	}
}

func TestLanguagesAreWrittenAsThreeLetterTerminologicCodes(t *testing.T) {
	for in, want := range map[string]string{
		"eng": "eng", "heb": "heb", "rus": "rus", "jpn": "jpn", "und": "und",
		"fre": "fra", "ger": "deu", "chi": "zho", "dut": "nld", "gre": "ell", "cze": "ces", "rum": "ron",
		"per": "fas", "ice": "isl", "slo": "slk", "wel": "cym", "may": "msa", "bur": "mya", "arm": "hye",
		"ENG": "eng", " Fre ": "fra",
		"": "und", "en": "und", "english": "und", "e1g": "und", "zxx": "zxx",
	} {
		if got := NormalizeLanguage(in); got != want {
			t.Errorf("NormalizeLanguage(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestOnlyContainersThatNeedItAskForTimestampsToBeMade(t *testing.T) {
	for container, want := range map[string]bool{
		"mkv": false, "matroska,webm": false, "mp4": false, "mov,mp4,m4a,3gp,3g2,mj2": false,
		"avi": true, "asf": true, "wmv": true, "mpeg": true, "mpegts": true, "ts": true, "flv": true, "": false,
	} {
		src := source(video(0, "mpeg4", "", 0, ""), audio(1, "mp3", "eng", 2))
		src.Container = container
		plan, err := PlanApple(src)
		if err != nil {
			t.Fatal(err)
		}
		if plan.GenPTS != want {
			t.Errorf("%q: GenPTS = %v, want %v", container, plan.GenPTS, want)
		}
	}
}

func TestThePlanSignatureNamesTheDecisionsAndNothingElse(t *testing.T) {
	base := source(video(0, "hevc", "Main", 8, "yuv420p"), audio(1, "eac3", "eng", 6), subtitle(2, "subrip", "eng"))
	first, _ := PlanApple(base)
	again, _ := PlanApple(base)
	if first.Signature() != again.Signature() || len(first.Signature()) != 16 {
		t.Fatalf("signature %q is not stable or not 16 characters", first.Signature())
	}
	// The size of the file and the length of the film do not change what the MP4 holds.
	bigger := base
	bigger.SizeBytes *= 2
	bigger.DurationSeconds *= 2
	if other, _ := PlanApple(bigger); other.Signature() != first.Signature() {
		t.Error("a different file size changed the signature")
	}
	// A different track, language, codec decision or flag does.
	for name, change := range map[string]func(*Source){
		"a new audio track": func(s *Source) { s.Streams = append(s.Streams, audio(3, "ac3", "heb", 2)) },
		"audio codec":       func(s *Source) { s.Streams[1].Codec = "dts" },
		"video codec":       func(s *Source) { s.Streams[0].Codec = "mpeg4" },
		"language":          func(s *Source) { s.Streams[1].Language = "rus" },
		"subtitle format":   func(s *Source) { s.Streams[2].Codec = "hdmv_pgs_subtitle" },
		"forced flag":       func(s *Source) { s.Streams[2].Forced = true },
		"stream index":      func(s *Source) { s.Streams[2].Index = 9 },
	} {
		changed := base
		changed.Streams = append([]Stream(nil), base.Streams...)
		change(&changed)
		other, err := PlanApple(changed)
		if err != nil {
			t.Fatal(err)
		}
		if other.Signature() == first.Signature() {
			t.Errorf("%s did not change the signature", name)
		}
	}
}

func TestTheSizeEstimateFollowsWhatIsCopiedAndWhatIsConverted(t *testing.T) {
	copied := Source{
		Container: "mkv", SizeBytes: 5_500_000_000, BitRate: 6_100_000, DurationSeconds: 7200, DefaultAudio: -1,
		Streams: []Stream{
			func() Stream { s := video(0, "hevc", "Main", 8, "yuv420p"); s.BitRate = 5_000_000; return s }(),
			func() Stream { s := audio(1, "eac3", "eng", 6); s.BitRate = 640_000; return s }(),
			subtitle(2, "subrip", "eng"), subtitle(3, "hdmv_pgs_subtitle", "fre"),
		},
	}
	plan, err := PlanApple(copied)
	if err != nil {
		t.Fatal(err)
	}
	// 5 Mb/s of video and 640 kb/s of audio for two hours, plus a little.
	wantCopy := int64((5_000_000+640_000)/8) * 7200
	if got := plan.EstimatedBytes; got < wantCopy || got > wantCopy+wantCopy/50 {
		t.Fatalf("copied estimate = %d, want just over %d", got, wantCopy)
	}

	// DTS at 1.5 Mb/s becomes 384 kb/s of AAC, so the file is smaller.
	converted := copied
	converted.Streams = append([]Stream(nil), copied.Streams...)
	converted.Streams[1] = func() Stream { s := audio(1, "dts", "eng", 6); s.BitRate = 1_509_000; return s }()
	smaller, _ := PlanApple(converted)
	wantSmaller := int64((5_000_000+384_000)/8) * 7200
	if got := smaller.EstimatedBytes; got < wantSmaller || got > wantSmaller+wantSmaller/50 {
		t.Fatalf("converted-audio estimate = %d, want just over %d", got, wantSmaller)
	}

	// With no length to multiply by, the original's size is the honest upper bound.
	noLength := copied
	noLength.DurationSeconds = 0
	unknown, _ := PlanApple(noLength)
	if unknown.EstimatedBytes != copied.SizeBytes {
		t.Fatalf("estimate without a duration = %d, want the file's %d", unknown.EstimatedBytes, copied.SizeBytes)
	}

	// Bitrates the source does not state are guessed from the file, never zero.
	bare := Source{Container: "mkv", SizeBytes: 2_000_000_000, DurationSeconds: 5400, DefaultAudio: -1,
		Streams: []Stream{video(0, "h264", "High", 8, "yuv420p"), audio(1, "ac3", "eng", 6)}}
	guess, _ := PlanApple(bare)
	if guess.EstimatedBytes < 1_000_000_000 || guess.EstimatedBytes > 2_100_000_000 {
		t.Fatalf("an estimate without stream bitrates = %d, want near the 2 GB file", guess.EstimatedBytes)
	}
}
