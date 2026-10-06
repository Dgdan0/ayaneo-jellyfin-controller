package repackage

import (
	"bufio"
	"bytes"
	"context"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"time"
)

// FFmpegError is a run of ffmpeg that did not finish. Stderr is the end of what it
// said, for the hub's own log: it names files, so it is never shown to an app.
type FFmpegError struct {
	Err    error
	Stderr string
}

func (e *FFmpegError) Error() string { return "ffmpeg: " + e.Err.Error() }
func (e *FFmpegError) Unwrap() error { return e.Err }

// tailWriter keeps the last bytes written to it.
type tailWriter struct {
	mu   sync.Mutex
	data []byte
}

const tailBytes = 4096

func (w *tailWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.data = append(w.data, p...)
	if len(w.data) > tailBytes {
		w.data = w.data[len(w.data)-tailBytes:]
	}
	return len(p), nil
}

func (w *tailWriter) String() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	return strings.TrimSpace(string(bytes.ToValidUTF8(w.data, nil)))
}

// RunFFmpeg runs ffmpeg and reports how far through the output it has written,
// from the progress it prints to its standard output. It returns when ffmpeg
// does, or when the context ends and ffmpeg has been stopped.
func RunFFmpeg(ctx context.Context, ffmpeg string, args []string, progress func(written time.Duration)) error {
	command := exec.CommandContext(ctx, ffmpeg, args...)
	lowPriority(command)
	stderr := &tailWriter{}
	command.Stderr = stderr
	stdout, err := command.StdoutPipe()
	if err != nil {
		return err
	}
	if err := command.Start(); err != nil {
		return &FFmpegError{Err: err}
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			if written, ok := parseProgressLine(scanner.Text()); ok && progress != nil {
				progress(written)
			}
		}
	}()
	waitErr := command.Wait()
	<-done
	if ctxErr := ctx.Err(); ctxErr != nil {
		return ctxErr
	}
	if waitErr != nil {
		return &FFmpegError{Err: waitErr, Stderr: stderr.String()}
	}
	return nil
}

// parseProgressLine reads "out_time_us=1234567", ffmpeg's -progress report of how
// much output has been written. (Its out_time_ms is the same number in
// microseconds, despite the name.)
func parseProgressLine(line string) (time.Duration, bool) {
	value, found := strings.CutPrefix(line, "out_time_us=")
	if !found {
		return 0, false
	}
	microseconds, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil || microseconds < 0 {
		return 0, false
	}
	return time.Duration(microseconds) * time.Microsecond, true
}

// Spec is one repackage.
type Spec struct {
	FFmpeg string
	Plan   Plan
	Inputs Inputs
	// Out is the file to write, replaced if it exists. The caller gives it a name
	// that is not yet the finished file's, and renames it when this returns.
	Out      string
	Duration time.Duration
	Encoder  Encoder
}

// Build runs ffmpeg for the plan, then states the finished file's track headers.
// progress is told how far along it is, as a percent that stops at 99: the last
// of it, moving the movie box to the front of the file, prints nothing.
func Build(ctx context.Context, spec Spec, progress func(percent int)) error {
	args, err := BuildArgs(spec.Plan, spec.Inputs, spec.Out, spec.Encoder)
	if err != nil {
		return err
	}
	err = RunFFmpeg(ctx, spec.FFmpeg, args, func(written time.Duration) {
		if progress != nil && spec.Duration > 0 {
			progress(int(min(99, max(0, written*100/spec.Duration))))
		}
	})
	if err != nil {
		return err
	}
	return PatchTracks(spec.Out, spec.Plan)
}

// DetectEncoder says which H.264 encoder a conversion should use: NVENC when a
// tiny encode on it succeeds (the encoder being compiled in says nothing about a
// card being there), otherwise libx264.
func DetectEncoder(ctx context.Context, ffmpeg string) Encoder {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, ffmpeg,
		"-nostdin", "-hide_banner", "-loglevel", "error",
		"-f", "lavfi", "-i", "color=c=black:s=320x180:d=0.2:r=10",
		"-c:v", NVENC.Name, "-f", "null", "-")
	lowPriority(command)
	if err := command.Run(); err != nil {
		return X264
	}
	return NVENC
}
