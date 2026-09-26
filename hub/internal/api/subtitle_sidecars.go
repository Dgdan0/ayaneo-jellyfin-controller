package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"ayaneohub/internal/adapters/bazarr"
)

const maxSubtitleSidecarBytes = 8 << 20

var subtitleLanguageCode = regexp.MustCompile(`^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8}){0,2}$`)

// Bazarr can save a valid subtitle under a release name different from the
// movie filename. Jellyfin only discovers a sidecar with the video stem. Keep
// Bazarr's original intact and create a small, managed copy in that folder.
func prepareSubtitleSidecars(jellyfinVideo, bazarrVideo string, tracks []bazarr.SubtitleTrack) error {
	if len(tracks) == 0 {
		return nil
	}
	if jellyfinVideo == "" || bazarrVideo == "" {
		return fmt.Errorf("the video path is unavailable")
	}
	video, err := os.Stat(jellyfinVideo)
	if err != nil || !video.Mode().IsRegular() {
		return fmt.Errorf("the Jellyfin video is unavailable on this Hub")
	}
	other, err := os.Stat(bazarrVideo)
	if err != nil || !os.SameFile(video, other) {
		return fmt.Errorf("Bazarr and Jellyfin do not refer to the same video file")
	}
	directory, err := filepath.EvalSymlinks(filepath.Dir(jellyfinVideo))
	if err != nil {
		return fmt.Errorf("the video folder is unavailable")
	}
	stem := strings.TrimSuffix(filepath.Base(jellyfinVideo), filepath.Ext(jellyfinVideo))
	if stem == "" {
		return fmt.Errorf("the video filename is invalid")
	}
	type sidecar struct {
		destination string
		data        []byte
	}
	toWrite := make([]sidecar, 0, len(tracks))
	used := make(map[string]bool)
	total := 0
	for _, track := range tracks {
		if track.Path == nil || *track.Path == "" { // Embedded subtitle; no file to copy.
			continue
		}
		path := filepath.Clean(*track.Path)
		parent, err := filepath.EvalSymlinks(filepath.Dir(path))
		if err != nil || !strings.EqualFold(parent, directory) {
			return fmt.Errorf("a subtitle is outside the video folder")
		}
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > maxSubtitleSidecarBytes {
			return fmt.Errorf("a subtitle file is missing or too large")
		}
		ext := strings.ToLower(filepath.Ext(path))
		if ext != ".srt" && ext != ".ass" && ext != ".ssa" && ext != ".vtt" {
			return fmt.Errorf("unsupported subtitle file format")
		}
		if strings.EqualFold(filepath.Base(path), stem+ext) || strings.HasPrefix(strings.ToLower(filepath.Base(path)), strings.ToLower(stem)+".") {
			continue // Jellyfin can already discover this name.
		}
		code := strings.ToLower(strings.TrimSpace(track.Code2))
		if !subtitleLanguageCode.MatchString(code) {
			return fmt.Errorf("a subtitle has no usable language code")
		}
		total += int(info.Size())
		if total > 32<<20 || len(toWrite) >= 8 {
			return fmt.Errorf("too many subtitles to prepare at once")
		}
		f, err := os.Open(path)
		if err != nil {
			return fmt.Errorf("could not open a subtitle file: %w", err)
		}
		data, readErr := io.ReadAll(io.LimitReader(f, maxSubtitleSidecarBytes+1))
		closeErr := f.Close()
		if readErr != nil || closeErr != nil || len(data) == 0 || len(data) > maxSubtitleSidecarBytes {
			return fmt.Errorf("could not read a subtitle file")
		}
		flags := ""
		if track.Forced {
			flags += ".forced"
		}
		if track.HI {
			flags += ".sdh"
		}
		name := stem + ".pocketds." + code + flags + ext
		if used[strings.ToLower(name)] {
			h := sha256.Sum256([]byte(path))
			name = stem + ".pocketds-" + hex.EncodeToString(h[:4]) + "." + code + flags + ext
		}
		used[strings.ToLower(name)] = true
		toWrite = append(toWrite, sidecar{filepath.Join(directory, name), data})
	}
	for _, entry := range toWrite {
		if info, err := os.Lstat(entry.destination); err == nil {
			if !info.Mode().IsRegular() || info.Size() > maxSubtitleSidecarBytes {
				return fmt.Errorf("an existing managed subtitle is not a regular subtitle file")
			}
		} else if !os.IsNotExist(err) {
			return fmt.Errorf("could not inspect an existing managed subtitle: %w", err)
		}
		if old, err := os.ReadFile(entry.destination); err == nil {
			if bytes.Equal(old, entry.data) {
				continue
			}
		} else if !os.IsNotExist(err) {
			return fmt.Errorf("could not inspect an existing managed subtitle: %w", err)
		}
		tmp, err := os.CreateTemp(directory, ".pocketds-subtitle-*")
		if err != nil {
			return fmt.Errorf("the video folder is not writable: %w", err)
		}
		tempPath := tmp.Name()
		_, writeErr := tmp.Write(entry.data)
		if writeErr == nil {
			writeErr = tmp.Sync()
		}
		if closeErr := tmp.Close(); writeErr == nil {
			writeErr = closeErr
		}
		if writeErr == nil {
			writeErr = os.Rename(tempPath, entry.destination)
		}
		if writeErr != nil {
			os.Remove(tempPath)
			return fmt.Errorf("could not save a Jellyfin subtitle copy: %w", writeErr)
		}
	}
	return nil
}
