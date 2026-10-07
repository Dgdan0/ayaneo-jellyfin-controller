package repackage

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestARealFile repackages a file you name and reports what came out. It is the
// check to run on a film from the library before trusting the plan with it, and
// it is skipped unless REPACKAGE_FILE names one:
//
//	REPACKAGE_FILE='E:\Videos\...\film.mkv' REPACKAGE_OUT='C:\scratch' \
//	    go test ./internal/repackage -run TestARealFile -v -timeout 2h
//
// The source is only read (its size and time are compared afterwards), the MP4 is
// written into REPACKAGE_OUT, and REPACKAGE_ENCODER=nvenc converts video on the
// graphics card instead of libx264. Streams are described from ffprobe, which is
// where Jellyfin's own description comes from.
func TestARealFile(t *testing.T) {
	file, out := os.Getenv("REPACKAGE_FILE"), os.Getenv("REPACKAGE_OUT")
	if file == "" || out == "" {
		t.Skip("REPACKAGE_FILE and REPACKAGE_OUT name the file to repackage and where to put the MP4")
	}
	ffmpeg := tool(t, "ffmpeg")
	before, err := os.Stat(file)
	if err != nil {
		t.Fatal(err)
	}
	plan, err := PlanApple(sourceFrom(t, file, -1))
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("video %d: %s -> %s %s (converted %v %s)", plan.Video.SourceIndex, plan.Video.Codec, plan.Video.OutputCodec, plan.Video.Tag, plan.Video.Converted, plan.Video.Reason)
	for _, track := range plan.Audio {
		t.Logf("audio %d %s %s %dch -> %s %dch (converted %v, default %v)", track.SourceIndex, track.Language, track.Codec, track.Channels, track.OutputCodec, track.OutputChannels, track.Converted, track.Default)
	}
	available, left := 0, 0
	for _, track := range plan.Subtitles {
		if track.Available {
			available++
		} else {
			left++
			t.Logf("subtitle %d %s %s is left out: %s", track.SourceIndex, track.Language, track.Codec, track.Reason)
		}
	}
	t.Logf("subtitles: %d come across as mov_text, %d are left out", available, left)

	encoder := X264
	if strings.EqualFold(os.Getenv("REPACKAGE_ENCODER"), "nvenc") {
		encoder = NVENC
	}
	if err := os.MkdirAll(out, 0o700); err != nil {
		t.Fatal(err)
	}
	target := filepath.Join(out, "repackaged.mp4")
	started := time.Now()
	lastPrinted := -10
	err = Build(context.Background(), BuildSpec{
		FFmpeg: ffmpeg, Plan: plan, Out: target, Encoder: encoder,
		Duration: time.Duration(sourceFrom(t, file, -1).DurationSeconds * float64(time.Second)),
		Inputs:   Inputs{SourcePath: file},
	}, func(percent int) {
		if percent >= lastPrinted+10 {
			lastPrinted = percent
			t.Logf("%3d%% after %v", percent, time.Since(started).Round(time.Second))
		}
	})
	if err != nil {
		t.Fatalf("Build: %v", err)
	}
	t.Logf("built in %v", time.Since(started).Round(time.Second))

	info, err := os.Stat(target)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("size %d bytes (estimated %d, original %d)", info.Size(), plan.EstimatedBytes, before.Size())

	got := probe(t, target)
	for _, stream := range got.Streams {
		t.Logf("out %d %-8s %-9s tag %-5s %dch lang %-3s default %d handler %q", stream.Index, stream.CodecType, stream.CodecName, stream.CodecTag,
			stream.Channels, stream.Tags["language"], stream.Disposition["default"], stream.Tags["handler_name"])
	}
	kinds := topLevelKinds(t, target)
	t.Logf("top-level boxes: %v", kinds)
	moov, mdat := -1, -1
	for at, kind := range kinds {
		if kind == "moov" && moov < 0 {
			moov = at
		}
		if kind == "mdat" && mdat < 0 {
			mdat = at
		}
	}
	if moov < 0 || mdat < 0 || moov > mdat {
		t.Errorf("the movie box is not ahead of the media data: %v", kinds)
	}
	data := make([]byte, 0)
	if head, err := os.Open(target); err == nil {
		// The track headers sit in the movie box, near the front of a faststart file.
		buffer := make([]byte, 16<<20)
		read, _ := head.Read(buffer)
		data = buffer[:read]
		head.Close()
	}
	for at, track := range inspectTracks(t, data) {
		t.Logf("track %d: %s flags %#x alternate group %d", at, track.handler, track.flags, track.alternate)
	}

	// An ffmpeg reader has nothing to say of the container, and the first half
	// minute of the picture and the sound decodes without a complaint.
	if warning := readsBackClean(t, target); warning != "" {
		t.Errorf("reading the container: %s", warning)
	}
	check := exec.Command(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-i", target,
		"-t", "30", "-map", "0:v:0", "-map", "0:a", "-f", "null", "-")
	if output, err := check.CombinedOutput(); err != nil || len(strings.TrimSpace(string(output))) > 0 {
		t.Errorf("decoding the first 30 seconds: %v: %s", err, output)
	}

	after, err := os.Stat(file)
	if err != nil || after.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) {
		t.Errorf("the source changed: %v %v", after, err)
	}
}
