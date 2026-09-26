package api

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"ayaneohub/internal/adapters/bazarr"
)

func TestPrepareSubtitleSidecarsKeepsOriginalAndUpdatesOnlyManagedCopy(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "Film.mkv")
	source := filepath.Join(dir, "different-release.srt")
	managed := filepath.Join(dir, "Film.pocketds.en.srt")
	for name, content := range map[string]string{video: "large video bytes", source: "first subtitle"} {
		if err := os.WriteFile(name, []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	track := bazarr.SubtitleTrack{Code2: "en", Path: &source}
	if err := prepareSubtitleSidecars(video, video, []bazarr.SubtitleTrack{track}); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(managed); string(data) != "first subtitle" {
		t.Fatalf("wrong managed copy: %q", data)
	}
	if data, _ := os.ReadFile(video); string(data) != "large video bytes" {
		t.Fatal("video changed")
	}
	if err := os.WriteFile(source, []byte("improved subtitle"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := prepareSubtitleSidecars(video, video, []bazarr.SubtitleTrack{track}); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(managed); string(data) != "improved subtitle" {
		t.Fatalf("managed copy did not update: %q", data)
	}
	if data, _ := os.ReadFile(source); string(data) != "improved subtitle" {
		t.Fatal("Bazarr original changed")
	}
}

func TestPrepareSubtitleSidecarsRejectsUnrelatedFiles(t *testing.T) {
	dir := t.TempDir()
	video := filepath.Join(dir, "Film.mkv")
	other := filepath.Join(dir, "Other.mkv")
	outside := filepath.Join(t.TempDir(), "someone-else.en.srt")
	for _, path := range []string{video, other, outside} {
		if err := os.WriteFile(path, []byte("content"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	track := bazarr.SubtitleTrack{Code2: "en", Path: &outside}
	if err := prepareSubtitleSidecars(video, other, []bazarr.SubtitleTrack{track}); err == nil || !strings.Contains(err.Error(), "same video") {
		t.Fatalf("wrong-video validation: %v", err)
	}
	if err := prepareSubtitleSidecars(video, video, []bazarr.SubtitleTrack{track}); err == nil || !strings.Contains(err.Error(), "outside") {
		t.Fatalf("outside-file validation: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "Film.pocketds.en.srt")); !os.IsNotExist(err) {
		t.Fatalf("unexpected managed copy: %v", err)
	}
}
