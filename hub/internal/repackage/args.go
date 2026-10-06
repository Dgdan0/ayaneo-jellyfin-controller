package repackage

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Encoder is the H.264 encoder a conversion uses.
type Encoder struct {
	Name    string
	Options []string
}

// X264 is the software encoder: every machine has it, and veryfast keeps a long
// film from taking all night. CRF 20 is a little better than what most sources
// that need converting were made at.
var X264 = Encoder{Name: "libx264", Options: []string{
	"-preset", "veryfast", "-crf", "20", "-profile:v", "high", "-pix_fmt", "yuv420p",
}}

// NVENC is the same picture on an NVIDIA card, for the rare video conversion on a
// media PC that has one (the Jellyfin server's own ffmpeg ships with it).
var NVENC = Encoder{Name: "h264_nvenc", Options: []string{
	"-preset", "p5", "-tune", "hq", "-rc", "vbr", "-cq", "23", "-b:v", "0", "-profile:v", "high", "-pix_fmt", "yuv420p",
}}

// Inputs are the files a command reads. The source is read and never written, and
// the text subtitles that live beside it arrive as files of their own, fetched
// from Jellyfin by the caller and keyed by the subtitle's source index.
type Inputs struct {
	SourcePath string
	Subtitles  map[int]string
}

// BuildArgs is the ffmpeg command for a plan: one -map for each stream the plan
// keeps, in the order the MP4 will hold them (the picture, the audio, the
// subtitles), and nothing else.
//
// Every input is opened with -protocol_whitelist file, so a path that is really a
// URL, or a playlist that names one, is refused rather than fetched.
func BuildArgs(plan Plan, in Inputs, out string, encoder Encoder) ([]string, error) {
	if in.SourcePath == "" || out == "" {
		return nil, errors.New("a repackage needs a source and an output")
	}
	args := []string{
		"-nostdin", "-hide_banner", "-loglevel", "error", "-nostats", "-progress", "pipe:1", "-y",
	}
	if plan.GenPTS {
		args = append(args, "-fflags", "+genpts")
	}
	args = append(args, "-protocol_whitelist", "file", "-i", in.SourcePath)

	// Sidecars are inputs 1, 2, ... in the order the plan lists them.
	type mapped struct{ input, stream int }
	var subtitleMaps []mapped
	nextInput := 1
	for _, track := range plan.Subtitles {
		if !track.Available {
			continue
		}
		if !track.External {
			subtitleMaps = append(subtitleMaps, mapped{0, track.SourceIndex})
			continue
		}
		path := in.Subtitles[track.SourceIndex]
		if path == "" {
			return nil, fmt.Errorf("subtitle %d is promised and has no file", track.SourceIndex)
		}
		args = append(args, "-protocol_whitelist", "file", "-i", path)
		subtitleMaps = append(subtitleMaps, mapped{nextInput, 0})
		nextInput++
	}

	args = append(args, "-map", "0:"+strconv.Itoa(plan.Video.SourceIndex))
	for _, track := range plan.Audio {
		args = append(args, "-map", "0:"+strconv.Itoa(track.SourceIndex))
	}
	for _, track := range subtitleMaps {
		args = append(args, "-map", strconv.Itoa(track.input)+":"+strconv.Itoa(track.stream))
	}
	// The source's own tags (a release group's name, its muxer, its statistics)
	// are not carried over. Chapters are.
	args = append(args, "-map_metadata", "-1", "-map_chapters", "0")

	if plan.Video.Converted {
		args = append(args, "-c:v", encoder.Name)
		args = append(args, encoder.Options...)
	} else {
		args = append(args, "-c:v", "copy")
	}
	args = append(args, "-tag:v", plan.Video.Tag)

	for at, track := range plan.Audio {
		index := strconv.Itoa(at)
		if track.Converted {
			args = append(args, "-c:a:"+index, track.OutputCodec,
				"-b:a:"+index, strconv.Itoa(track.BitRateKbps)+"k", "-ac:a:"+index, strconv.Itoa(track.OutputChannels))
		} else {
			args = append(args, "-c:a:"+index, "copy")
		}
		args = append(args, "-metadata:s:a:"+index, "language="+track.Language)
		if title := cleanTitle(track.Label); title != "" {
			args = append(args, "-metadata:s:a:"+index, "handler_name="+title)
		}
		args = append(args, "-disposition:a:"+index, dispositionOf(track.Default, false, false))
	}

	written := 0
	for _, track := range plan.Subtitles {
		if !track.Available {
			continue
		}
		index := strconv.Itoa(written)
		written++
		args = append(args, "-metadata:s:s:"+index, "language="+track.Language)
		if title := cleanTitle(track.Label); title != "" {
			args = append(args, "-metadata:s:s:"+index, "handler_name="+title)
		}
		args = append(args, "-disposition:s:"+index, dispositionOf(false, track.Forced, track.HearingImpaired))
	}
	if written > 0 {
		args = append(args, "-c:s", "mov_text")
	}

	// A subtitle track is sparse, and in a long film ffmpeg can hold more packets
	// for it than its default queue before the next one comes.
	args = append(args, "-max_muxing_queue_size", "9999", "-movflags", "+faststart", "-f", "mp4", out)
	return args, nil
}

// dispositionOf is ffmpeg's word for a track's flags: "0" for none.
func dispositionOf(isDefault, forced, hearingImpaired bool) string {
	var flags []string
	if isDefault {
		flags = append(flags, "default")
	}
	if forced {
		flags = append(flags, "forced")
	}
	if hearingImpaired {
		flags = append(flags, "hearing_impaired")
	}
	if len(flags) == 0 {
		return "0"
	}
	return strings.Join(flags, "+")
}

const maxTitleBytes = 100

// cleanTitle is a track's label as an MP4 handler name: one line, no control
// characters, and short.
func cleanTitle(label string) string {
	var cleaned strings.Builder
	space := false
	for _, char := range label {
		if unicode.IsControl(char) || unicode.IsSpace(char) {
			space = cleaned.Len() > 0
			continue
		}
		if space {
			cleaned.WriteByte(' ')
			space = false
		}
		cleaned.WriteRune(char)
	}
	title := cleaned.String()
	for len(title) > maxTitleBytes {
		_, size := utf8.DecodeLastRuneInString(title)
		title = title[:len(title)-size]
	}
	return strings.TrimSpace(title)
}
