// Package repackage turns a source file into the MP4 an Apple device plays (#5).
//
// AVPlayer cannot play an MKV, and most of a library's files differ from what it
// can play in one stream or two: a DTS track, a picture subtitle, an old Xvid
// video. So the hub does not transcode a film; it copies every stream AVPlayer
// already understands into an MP4, converts the few it does not, and says before
// it starts exactly what the file will hold.
//
// This file is that decision, a pure function of the streams. The manifest tells
// the app the plan, and the ffmpeg command is built from the same plan, so what
// was promised and what was made cannot drift apart.
package repackage

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

// ErrNoVideo means the source has no picture to put in an MP4.
var ErrNoVideo = errors.New("the source has no video stream")

// Why a stream is converted or left out, as the manifest words it.
const (
	ReasonUnsupportedCodec   = "unsupported_codec"
	ReasonUnsupportedProfile = "unsupported_profile"
	ReasonPictureSubtitle    = "picture_subtitle"
	ReasonUnsupportedFormat  = "unsupported_format"
)

// Stream is one stream of the source as Jellyfin describes it. Codec names are
// ffprobe's, which Jellyfin passes through, and Index is the stream's index in the
// file, which is what ffmpeg's -map takes.
type Stream struct {
	Index    int
	Type     string // "video", "audio" or "subtitle", lower case
	Codec    string
	Profile  string
	Language string
	Label    string
	Channels int
	BitRate  int // bits per second, 0 when the source does not say

	BitDepth    int    // 0 when the source does not say
	PixelFormat string // "" when the source does not say
	Width       int
	Height      int

	Default         bool
	Forced          bool
	HearingImpaired bool
	// External is a subtitle that lives in a file beside the video, which the
	// builder fetches from Jellyfin as text.
	External bool
}

// Source is what the plan needs to know of the file.
type Source struct {
	Container       string
	SizeBytes       int64
	BitRate         int
	DurationSeconds float64
	// DefaultAudio is the index of the audio stream Jellyfin plays by default,
	// or a negative number when it names none.
	DefaultAudio int
	Streams      []Stream
}

type Video struct {
	SourceIndex int
	Codec       string
	OutputCodec string
	Tag         string // the sample entry AVPlayer reads: hvc1 or avc1
	Converted   bool
	Reason      string
	Width       int
	Height      int
}

type Audio struct {
	SourceIndex    int
	Language       string
	Label          string
	Codec          string
	OutputCodec    string
	Channels       int
	OutputChannels int
	// BitRateKbps is the AAC rate of a converted track; 0 for a copy.
	BitRateKbps int
	Converted   bool
	Reason      string
	Default     bool
}

type Subtitle struct {
	SourceIndex     int
	Language        string
	Label           string
	Codec           string
	OutputCodec     string // mov_text, or "" for one that is left out
	External        bool
	Forced          bool
	HearingImpaired bool
	Available       bool
	Reason          string
}

// Plan is what the MP4 will hold.
type Plan struct {
	Video     Video
	Audio     []Audio
	Subtitles []Subtitle
	// GenPTS asks ffmpeg to make presentation timestamps for a container that
	// does not keep them (AVI, ASF, MPEG).
	GenPTS         bool
	EstimatedBytes int64
}

// PlanApple decides what an Apple device's MP4 holds.
func PlanApple(src Source) (Plan, error) {
	var plan Plan
	videoAt := -1
	for at, stream := range src.Streams {
		if stream.Type == "video" && !stillImageCodecs[strings.ToLower(stream.Codec)] {
			videoAt = at
			break
		}
	}
	if videoAt < 0 {
		return Plan{}, ErrNoVideo
	}
	plan.Video = planVideo(src.Streams[videoAt])

	for _, stream := range src.Streams {
		switch stream.Type {
		case "audio":
			plan.Audio = append(plan.Audio, planAudio(stream))
		case "subtitle":
			plan.Subtitles = append(plan.Subtitles, planSubtitle(stream))
		}
	}
	chooseDefaultAudio(plan.Audio, src, src.Streams)
	plan.GenPTS = needsTimestamps(src.Container)
	plan.EstimatedBytes = estimate(plan, src)
	return plan, nil
}

// Pictures that ride in a file as a "video" stream: a film's cover.
var stillImageCodecs = map[string]bool{
	"mjpeg": true, "png": true, "bmp": true, "gif": true, "tiff": true, "webp": true, "jpegls": true, "ljpeg": true,
}

func planVideo(stream Stream) Video {
	codec := strings.ToLower(stream.Codec)
	out := Video{SourceIndex: stream.Index, Codec: stream.Codec, Width: stream.Width, Height: stream.Height}
	switch codec {
	case "h264":
		if !isEightBitFourTwoZero(stream) {
			return convertedVideo(out, ReasonUnsupportedProfile)
		}
		out.OutputCodec, out.Tag = "h264", "avc1"
		return out
	case "hevc", "h265":
		if !isHEVCApplePlays(stream) {
			return convertedVideo(out, ReasonUnsupportedProfile)
		}
		out.OutputCodec, out.Tag = "hevc", "hvc1"
		return out
	}
	return convertedVideo(out, ReasonUnsupportedCodec)
}

func convertedVideo(video Video, reason string) Video {
	video.OutputCodec, video.Tag, video.Converted, video.Reason = "h264", "avc1", true, reason
	return video
}

// isEightBitFourTwoZero says whether an H.264 stream is the 8-bit 4:2:0 that
// Apple's decoders handle. High 10, 4:2:2 and 4:4:4 are not, and anime is full of
// High 10. A source that says nothing of its depth is taken at its profile name,
// and one that says nothing at all is taken as ordinary.
func isEightBitFourTwoZero(stream Stream) bool {
	if stream.BitDepth > 8 {
		return false
	}
	if pixel := strings.ToLower(stream.PixelFormat); pixel != "" {
		return pixel == "yuv420p" || pixel == "yuvj420p" || pixel == "nv12"
	}
	profile := strings.ToLower(stream.Profile)
	for _, marker := range []string{"10", "4:2:2", "4:4:4", "422", "444", "predictive", "hi10", "hi422", "hi444"} {
		if strings.Contains(profile, marker) {
			return false
		}
	}
	return true
}

// isHEVCApplePlays accepts Main and Main 10 at 4:2:0, which is every HEVC file in
// an ordinary library, and sends the range extensions (4:2:2, 4:4:4, 12-bit) to
// conversion.
func isHEVCApplePlays(stream Stream) bool {
	if stream.BitDepth > 10 {
		return false
	}
	if pixel := strings.ToLower(stream.PixelFormat); pixel != "" {
		return pixel == "yuv420p" || pixel == "yuvj420p" || pixel == "yuv420p10le" || pixel == "yuv420p10be" || pixel == "nv12" || pixel == "p010le"
	}
	profile := strings.ToLower(stream.Profile)
	return !strings.Contains(profile, "rext") && !strings.Contains(profile, "4:2:2") && !strings.Contains(profile, "4:4:4")
}

// Audio an Apple device plays inside an MP4. Everything else becomes AAC.
var copiedAudio = map[string]bool{"aac": true, "ac3": true, "eac3": true, "mp3": true}

func planAudio(stream Stream) Audio {
	codec := strings.ToLower(stream.Codec)
	channels := stream.Channels
	if channels <= 0 {
		channels = 2
	}
	out := Audio{
		SourceIndex: stream.Index, Language: NormalizeLanguage(stream.Language), Label: stream.Label,
		Codec: stream.Codec, Channels: channels, Default: false,
	}
	if copiedAudio[codec] {
		out.OutputCodec, out.OutputChannels = codec, channels
		return out
	}
	out.OutputCodec, out.Converted, out.Reason = "aac", true, ReasonUnsupportedCodec
	// Keep a 5.1 mix as 5.1. More than six channels are folded down to it.
	out.OutputChannels = min(channels, 6)
	out.BitRateKbps = aacKbps(out.OutputChannels)
	return out
}

func aacKbps(channels int) int {
	switch {
	case channels <= 1:
		return 96
	case channels == 2:
		return 192
	case channels <= 4:
		return 256
	case channels == 5:
		return 320
	default:
		return 384
	}
}

// chooseDefaultAudio marks exactly one audio track: the one the source plays by
// default, else one it flags, else the first.
func chooseDefaultAudio(tracks []Audio, src Source, streams []Stream) {
	if len(tracks) == 0 {
		return
	}
	chosen := -1
	for at, track := range tracks {
		if track.SourceIndex == src.DefaultAudio && src.DefaultAudio >= 0 {
			chosen = at
			break
		}
	}
	if chosen < 0 {
		flagged := map[int]bool{}
		for _, stream := range streams {
			if stream.Type == "audio" && stream.Default {
				flagged[stream.Index] = true
			}
		}
		for at, track := range tracks {
			if flagged[track.SourceIndex] {
				chosen = at
				break
			}
		}
	}
	if chosen < 0 {
		chosen = 0
	}
	tracks[chosen].Default = true
}

// Text subtitles ffmpeg can turn into mov_text. A sidecar is fetched from
// Jellyfin, which hands it over as UTF-8 text whatever its own encoding was.
var textSubtitles = map[string]bool{
	"subrip": true, "srt": true, "ass": true, "ssa": true, "webvtt": true, "vtt": true, "mov_text": true,
	"text": true, "microdvd": true, "subviewer": true, "subviewer1": true, "sami": true, "smi": true,
	"realtext": true, "jacosub": true, "mpl2": true, "pjs": true, "stl": true, "vplayer": true,
}

// Subtitles that are pictures, which an MP4 for AVPlayer cannot carry.
var pictureSubtitles = map[string]bool{
	"hdmv_pgs_subtitle": true, "pgssub": true, "pgs": true, "dvd_subtitle": true, "dvdsub": true,
	"dvb_subtitle": true, "dvbsub": true, "xsub": true, "vobsub": true, "dvb_teletext": true,
}

func planSubtitle(stream Stream) Subtitle {
	codec := strings.ToLower(stream.Codec)
	out := Subtitle{
		SourceIndex: stream.Index, Language: NormalizeLanguage(stream.Language), Label: stream.Label,
		Codec: stream.Codec, External: stream.External, Forced: stream.Forced, HearingImpaired: stream.HearingImpaired,
	}
	switch {
	case textSubtitles[codec]:
		out.OutputCodec, out.Available = "mov_text", true
	case pictureSubtitles[codec]:
		out.Reason = ReasonPictureSubtitle
	default:
		out.Reason = ReasonUnsupportedFormat
	}
	return out
}

// Containers that carry no presentation timestamps of their own.
var timestamplessContainers = map[string]bool{
	"avi": true, "asf": true, "wmv": true, "mpeg": true, "mpegts": true, "ts": true, "flv": true, "mpg": true,
}

func needsTimestamps(container string) bool {
	for _, name := range strings.Split(strings.ToLower(container), ",") {
		if timestamplessContainers[strings.TrimSpace(name)] {
			return true
		}
	}
	return false
}

// Bibliographic codes that differ from the terminologic ones an MP4 stores.
var terminologic = map[string]string{
	"alb": "sqi", "arm": "hye", "baq": "eus", "bur": "mya", "chi": "zho", "cze": "ces", "dut": "nld",
	"fre": "fra", "geo": "kat", "ger": "deu", "gre": "ell", "ice": "isl", "mac": "mkd", "mao": "mri",
	"may": "msa", "per": "fas", "rum": "ron", "slo": "slk", "tib": "bod", "wel": "cym",
}

// NormalizeLanguage gives the three-letter ISO 639-2/T code an MP4 stores, or
// "und" for anything that is not three letters. Jellyfin passes on whichever form
// the file used ("fre" in an MKV, "fra" in an MP4), and the same language must be
// the same code to the app that matches a track by it.
func NormalizeLanguage(code string) string {
	code = strings.ToLower(strings.TrimSpace(code))
	if len(code) != 3 {
		return "und"
	}
	for _, letter := range code {
		if letter < 'a' || letter > 'z' {
			return "und"
		}
	}
	if converted, found := terminologic[code]; found {
		return converted
	}
	return code
}

// Signature names the decisions a plan makes, so a file built from one plan is
// never served for another (a renewed grant whose source was re-analysed, say).
// It leaves out the size and the length, which do not change what the MP4 holds,
// and the labels, which only change what it is called.
func (p Plan) Signature() string {
	var text strings.Builder
	fmt.Fprintf(&text, "plan1|v:%d,%s,%s,%t,%t|", p.Video.SourceIndex, p.Video.OutputCodec, p.Video.Tag, p.Video.Converted, p.GenPTS)
	for _, track := range p.Audio {
		fmt.Fprintf(&text, "a:%d,%s,%s,%d,%d,%t,%t;", track.SourceIndex, track.Language, track.OutputCodec, track.OutputChannels, track.BitRateKbps, track.Converted, track.Default)
	}
	text.WriteString("|")
	for _, track := range p.Subtitles {
		fmt.Fprintf(&text, "s:%d,%s,%t,%t,%t,%t;", track.SourceIndex, track.Language, track.Available, track.External, track.Forced, track.HearingImpaired)
	}
	sum := sha256.Sum256([]byte(text.String()))
	return hex.EncodeToString(sum[:8])
}

// estimate is how many bytes the MP4 will take, good for a free-space check: the
// copied streams at the bitrates the source states, the converted ones at their
// target, and a guess where the source says nothing.
func estimate(plan Plan, src Source) int64 {
	seconds := src.DurationSeconds
	if seconds <= 0 {
		return src.SizeBytes
	}
	bytesAt := func(bitsPerSecond int) int64 { return int64(float64(bitsPerSecond) / 8 * seconds) }

	streamByIndex := map[int]Stream{}
	for _, stream := range src.Streams {
		streamByIndex[stream.Index] = stream
	}
	var audioSource, audioOut int64
	for _, track := range plan.Audio {
		stream := streamByIndex[track.SourceIndex]
		sourceRate := stream.BitRate
		if sourceRate <= 0 {
			sourceRate = guessAudioBitRate(track.Codec, track.Channels)
		}
		audioSource += bytesAt(sourceRate)
		if track.Converted {
			audioOut += bytesAt(track.BitRateKbps * 1000)
		} else {
			audioOut += bytesAt(sourceRate)
		}
	}

	videoStream := streamByIndex[plan.Video.SourceIndex]
	video := int64(0)
	switch {
	case videoStream.BitRate > 0:
		video = bytesAt(videoStream.BitRate)
	case src.SizeBytes > 0:
		video = src.SizeBytes - audioSource
		if video < src.SizeBytes/5 {
			video = src.SizeBytes * 4 / 5
		}
	}
	if plan.Video.Converted {
		video = int64(float64(video) * conversionFactor(plan.Video.Codec))
		video = min(video, bytesAt(conversionCeiling(plan.Video.Width, plan.Video.Height)))
	}

	text := int64(0)
	for _, track := range plan.Subtitles {
		if track.Available {
			text += 50 << 10
		}
	}
	total := video + audioOut + text
	return total + total/200 + 64<<10
}

// conversionFactor is how large a picture comes out in H.264 at the constant
// quality the conversion uses, against the same picture in the codec it was in.
// AV1 is about twice as efficient: a 24-minute AV1 anime episode of 266 MB came
// out at 367 MB with libx264 and 442 MB with NVENC (measured). Older codecs are
// less efficient than H.264, so those come out smaller.
func conversionFactor(codec string) float64 {
	switch strings.ToLower(codec) {
	case "av1":
		return 1.7
	case "vp9", "vp8":
		return 1.4
	case "hevc", "h265":
		return 1.5
	case "mpeg4", "msmpeg4v1", "msmpeg4v2", "msmpeg4v3", "h263":
		return 0.9
	case "mpeg2video", "mpeg1video":
		return 0.7
	case "h264", "vc1", "wmv3", "wmv2", "wmv1":
		return 1.0
	default:
		return 1.2
	}
}

// A converted video is written at constant quality, which for the sources that
// need converting (old Xvid, VC-1, 10-bit H.264) lands near these.
func conversionCeiling(width, height int) int {
	switch {
	case width*height <= 1280*720:
		return 4_000_000
	case width*height <= 1920*1080:
		return 8_000_000
	default:
		return 20_000_000
	}
}

func guessAudioBitRate(codec string, channels int) int {
	surround := channels > 2
	switch strings.ToLower(codec) {
	case "ac3":
		if surround {
			return 448_000
		}
		return 192_000
	case "eac3":
		if surround {
			return 640_000
		}
		return 192_000
	case "aac":
		if surround {
			return 384_000
		}
		return 192_000
	case "mp3":
		return 192_000
	case "dts":
		return 1_200_000 // between the half-rate 754 kb/s and the full-rate 1509 kb/s core
	case "truehd":
		return 3_000_000
	case "flac":
		return 1_000_000
	default:
		if surround {
			return 384_000
		}
		return 192_000
	}
}
