package api

// Probing audio files with ffprobe, for the audiobook routes: how long a file
// is, which track number its own tags give it, and the chapter marks inside it.
// All of it is optional. Without ffprobe the manifest simply has no chapters
// and no tag order, and everything else works.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"os/exec"
	"strconv"
	"strings"
	"time"

	readingdomain "ayaneohub/internal/reading"
)

const (
	// A probe reads the head of a file, and an M4B's index; measured 22 ms on an
	// MP3 and 84 ms on a 419 MB M4B. Five seconds is for a disk that is asleep.
	audioProbeTimeout = 5 * time.Second
	// Four at once: a book of forty files is ten rounds, and a burst of
	// different books cannot start dozens of processes.
	audioProbeSlots = 4
	// Entries are small; this only bounds the memory of a long uptime.
	audioProbeCacheLimit = 4096
	// What ffprobe may print before its answer is refused.
	audioProbeOutputLimit = 4 << 20
)

type probedAudio struct {
	DurationMs int64
	// Track is the number the file's own tags give it (ID3 TRCK, an MP4 trkn),
	// 0 when it has none or it cannot be read.
	Track    int
	Chapters []probedChapter
}

type probedChapter struct {
	Title          string
	StartMs, EndMs int64
}

// probeKey identifies a file as it was when it was probed. Size and modified
// time stand for "the same bytes": a file that changes is probed again.
type probeKey struct {
	path    string
	size    int64
	modNano int64
}

// probeFile answers what is known about one file, from the cache or from
// s.probeAudio, never more than probeSlots at a time and never for longer than
// the probe timeout. A failure (no ffprobe, a file it cannot read, a timeout) is
// no information rather than an error, and is not remembered: the tool may be
// installed later, and a timeout is not a fact about the file.
func (s *Server) probeFile(ctx context.Context, file probeKey) probedAudio {
	s.probeMu.Lock()
	cached, ok := s.probeCache[file]
	s.probeMu.Unlock()
	if ok {
		return cached
	}
	select {
	case s.probeSlots <- struct{}{}:
		defer func() { <-s.probeSlots }()
	case <-ctx.Done():
		return probedAudio{}
	}
	// The budget starts here, once a slot is held: time spent waiting behind
	// other probes is not time this one was slow.
	runCtx, cancel := context.WithTimeout(ctx, s.probeTimeout)
	defer cancel()
	result, err := s.probeAudio(runCtx, file.path)
	if err != nil {
		// No error text: it may name the file.
		slog.Debug("audio probe failed")
		return probedAudio{}
	}
	s.probeMu.Lock()
	if len(s.probeCache) >= audioProbeCacheLimit {
		clear(s.probeCache)
	}
	s.probeCache[file] = result
	s.probeMu.Unlock()
	return result
}

// ffprobeArgs is the whole command line, one element per argument. There is no
// shell, so a name cannot become a command, and the path comes after -i so one
// that begins with a dash cannot become an option.
//
// The container is forced from the file's extension: a file named .mp3 that is
// really a playlist is then not followed to other files or addresses, it just
// fails to probe; and only the file protocol is allowed besides.
//
// There is no -nostdin. ffmpeg takes it and ffprobe does not ("Option not
// found", measured on Jellyfin's 7.1.3 build), so with it every probe would
// fail and look like a missing tool. runFFprobe gives the process the null
// device as stdin instead.
func ffprobeArgs(path string, kind readingdomain.AudioKind) []string {
	return []string{
		"-v", "error", "-hide_banner",
		"-protocol_whitelist", "file",
		"-f", kind.Demuxer,
		"-print_format", "json", "-show_format", "-show_chapters",
		"-i", path,
	}
}

// runFFprobe is the default Server.probeAudio.
func runFFprobe(ctx context.Context, path string) (probedAudio, error) {
	kind, ok := readingdomain.AudioKindOf(path)
	if !ok {
		return probedAudio{}, errors.New("ffprobe is only run on audio files")
	}
	ffprobe, err := findFFprobe()
	if err != nil {
		return probedAudio{}, err
	}
	output := &cappedBuffer{limit: audioProbeOutputLimit}
	command := exec.CommandContext(ctx, ffprobe, ffprobeArgs(path, kind)...)
	command.Stdin = nil // the null device: this process never reads the hub's input
	command.Stdout = output
	// Stderr is dropped: with -v error it says why a file failed, and that text
	// names the file.
	if err := command.Run(); err != nil {
		return probedAudio{}, fmt.Errorf("ffprobe: %w", err)
	}
	return parseFFprobe(output.Bytes())
}

// cappedBuffer refuses output past its limit, which ends the process with a
// broken pipe rather than letting one file fill the hub's memory.
type cappedBuffer struct {
	bytes.Buffer
	limit int
}

func (b *cappedBuffer) Write(p []byte) (int, error) {
	if b.Len()+len(p) > b.limit {
		return 0, io.ErrShortWrite
	}
	return b.Buffer.Write(p)
}

// parseFFprobe reads the three things asked of ffprobe's JSON. The "filename"
// it also prints is the host path and is never read.
func parseFFprobe(output []byte) (probedAudio, error) {
	var raw struct {
		Format struct {
			Duration string         `json:"duration"`
			Tags     map[string]any `json:"tags"`
		} `json:"format"`
		Chapters []struct {
			StartTime string         `json:"start_time"`
			EndTime   string         `json:"end_time"`
			Tags      map[string]any `json:"tags"`
		} `json:"chapters"`
	}
	if err := json.Unmarshal(output, &raw); err != nil {
		return probedAudio{}, errors.New("ffprobe answered something unreadable")
	}
	probed := probedAudio{Track: trackNumber(tagValue(raw.Format.Tags, "track", "tracknumber"))}
	if ms, ok := secondsToMillis(raw.Format.Duration); ok {
		probed.DurationMs = ms
	}
	for _, chapter := range raw.Chapters {
		start, startOK := secondsToMillis(chapter.StartTime)
		end, endOK := secondsToMillis(chapter.EndTime)
		if !startOK || !endOK || end < start {
			continue
		}
		probed.Chapters = append(probed.Chapters, probedChapter{
			Title: strings.TrimSpace(tagValue(chapter.Tags, "title")), StartMs: start, EndMs: end,
		})
	}
	return probed, nil
}

// tagValue finds a tag whatever case the container wrote it in: ID3 and MP4
// give "track", Vorbis comments "TRACKNUMBER".
func tagValue(tags map[string]any, names ...string) string {
	for _, name := range names {
		for key, value := range tags {
			if !strings.EqualFold(key, name) {
				continue
			}
			switch typed := value.(type) {
			case string:
				return typed
			case float64:
				return strconv.FormatFloat(typed, 'f', -1, 64)
			}
		}
	}
	return ""
}

// trackNumber reads "3", "03", "3/8" and " 3 ". Anything else, and zero or
// less, is no number.
func trackNumber(value string) int {
	value, _, _ = strings.Cut(strings.TrimSpace(value), "/")
	n, err := strconv.ParseInt(strings.TrimSpace(value), 10, 32)
	if err != nil || n <= 0 {
		return 0
	}
	return int(n)
}

func secondsToMillis(value string) (int64, bool) {
	seconds, err := strconv.ParseFloat(strings.TrimSpace(value), 64)
	if err != nil || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds < 0 || seconds > 1e9 {
		return 0, false
	}
	return int64(math.Round(seconds * 1000)), true
}
