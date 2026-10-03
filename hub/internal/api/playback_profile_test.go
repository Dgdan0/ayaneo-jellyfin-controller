package api

import (
	"strings"
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

// avPlayerBody is what the Apple client sends: AVPlayer opens only MP4-family
// files directly and plays HEVC in HLS only from fMP4 segments.
func avPlayerBody() PlaybackPrepareBody {
	return PlaybackPrepareBody{
		Device: PlaybackDevice{ID: "ipad-pro", Name: "iPad Pro"},
		Capabilities: PlaybackCapabilities{
			VideoCodecs: []string{"h264", "hevc"},
			AudioCodecs: []string{"aac", "ac3", "eac3", "alac", "flac", "mp3"},
			Containers:  []string{"mp4", "m4v", "mov"},
			HLSSegments: "fmp4",
		},
	}
}

func directContainers(profile jellyfin.DeviceProfile) []string {
	out := []string{}
	for _, direct := range profile.DirectPlayProfiles {
		out = append(out, strings.Split(direct.Container, ",")...)
	}
	return out
}

func TestMedia3ProfileIsUnchangedWhenTheClientNamesNoContainers(t *testing.T) {
	profile := buildDeviceProfile(PlaybackPrepareBody{Capabilities: PlaybackCapabilities{
		VideoCodecs: []string{"h264", "hevc"}, AudioCodecs: []string{"aac", "ac3"},
	}})
	if profile.Name != "Pocket DS Media3" {
		t.Fatalf("name = %q", profile.Name)
	}
	got := strings.Join(directContainers(profile), ",")
	if got != "mp4,m4v,mov,mkv,webm,ts,mpegts" {
		t.Fatalf("direct containers = %s: the Pocket DS plays MKV directly and must keep doing so", got)
	}
	if len(profile.TranscodingProfiles) != 1 {
		t.Fatalf("transcoding profiles = %d", len(profile.TranscodingProfiles))
	}
	hls := profile.TranscodingProfiles[0]
	if hls.Container != "ts" || hls.Protocol != "hls" || hls.VideoCodec != "h264" || hls.AudioCodec != "aac" {
		t.Fatalf("transcoding = %+v", hls)
	}
}

func TestAVPlayerDirectPlaysOnlyTheContainersItNames(t *testing.T) {
	profile := buildDeviceProfile(avPlayerBody())
	got := strings.Join(directContainers(profile), ",")
	if got != "mp4,m4v,mov" {
		t.Fatalf("direct containers = %s: AVPlayer cannot open MKV or WebM", got)
	}
	for _, direct := range profile.DirectPlayProfiles {
		if direct.VideoCodec != "h264,hevc" {
			t.Fatalf("direct video codecs = %q", direct.VideoCodec)
		}
	}
	if profile.Name != "iPad Pro" {
		t.Fatalf("name = %q", profile.Name)
	}
}

func TestAVPlayerStreamsHLSInFragmentedMP4(t *testing.T) {
	profile := buildDeviceProfile(avPlayerBody())
	if len(profile.TranscodingProfiles) != 1 {
		t.Fatalf("transcoding profiles = %d", len(profile.TranscodingProfiles))
	}
	hls := profile.TranscodingProfiles[0]
	// Jellyfin writes fMP4 segments when an HLS profile's container is mp4. AVPlayer
	// refuses HEVC in TS segments, so this is what lets an HEVC MKV be remuxed rather
	// than re-encoded.
	if hls.Container != "mp4" || hls.Protocol != "hls" {
		t.Fatalf("transcoding = %+v", hls)
	}
	// H.264 first, so anything that must be re-encoded becomes H.264, the cheaper
	// encode on the media PC; HEVC is listed so an HEVC source is copied.
	if hls.VideoCodec != "h264,hevc" {
		t.Fatalf("hls video = %q", hls.VideoCodec)
	}
	// AAC first for the same reason; MP3 is dropped because it has no place in
	// an fMP4 HLS stream.
	if hls.AudioCodec != "aac,ac3,eac3,alac,flac" {
		t.Fatalf("hls audio = %q", hls.AudioCodec)
	}
}

func TestTheHubNotTheClientOrderDecidesWhatAReencodeProduces(t *testing.T) {
	body := avPlayerBody()
	body.Capabilities.VideoCodecs = []string{"HEVC", "h264"}
	body.Capabilities.AudioCodecs = []string{"eac3", "aac"}
	hls := buildDeviceProfile(body).TranscodingProfiles[0]
	if hls.VideoCodec != "h264,hevc" || hls.AudioCodec != "aac,eac3" {
		t.Fatalf("hls video = %q audio = %q", hls.VideoCodec, hls.AudioCodec)
	}
}

func TestFMP4WithoutHEVCStillReencodesToH264(t *testing.T) {
	body := avPlayerBody()
	body.Capabilities.VideoCodecs = []string{"h264"}
	hls := buildDeviceProfile(body).TranscodingProfiles[0]
	if hls.VideoCodec != "h264" {
		t.Fatalf("hls video = %q", hls.VideoCodec)
	}
}

func TestUnknownContainersFallBackToTheMedia3List(t *testing.T) {
	body := avPlayerBody()
	body.Capabilities.Containers = []string{"flv", "avi"}
	got := strings.Join(directContainers(buildDeviceProfile(body)), ",")
	if got != "mp4,m4v,mov,mkv,webm,ts,mpegts" {
		t.Fatalf("direct containers = %s", got)
	}
}

func TestForcedTranscodeKeepsTheClientsSegments(t *testing.T) {
	body := avPlayerBody()
	body.ForceTranscode = true
	profile := buildDeviceProfile(body)
	if len(profile.DirectPlayProfiles) != 0 {
		t.Fatalf("direct play survived a forced transcode: %+v", profile.DirectPlayProfiles)
	}
	if profile.TranscodingProfiles[0].Container != "mp4" {
		t.Fatalf("forced transcode container = %q", profile.TranscodingProfiles[0].Container)
	}
}

func TestAVPlayerTextSubtitlesAreNeverBurnedIn(t *testing.T) {
	methods := map[string]string{}
	for _, sub := range buildDeviceProfile(avPlayerBody()).SubtitleProfiles {
		methods[sub.Format] = sub.Method
	}
	for _, format := range []string{"srt", "subrip", "vtt", "webvtt", "ass", "ssa"} {
		if methods[format] != "External" {
			t.Fatalf("%s is %q: the Apple client draws text subtitles itself", format, methods[format])
		}
	}
}

func TestPrepareRejectsAnUnknownSegmentFormat(t *testing.T) {
	for _, value := range []string{"", "ts", "fmp4", "TS", "fMP4"} {
		body := PlaybackPrepareBody{Capabilities: PlaybackCapabilities{HLSSegments: value}}
		if err := validatePrepare(&body); err != nil {
			t.Fatalf("hlsSegments %q rejected: %v", value, err)
		}
	}
	body := PlaybackPrepareBody{Capabilities: PlaybackCapabilities{HLSSegments: "flv"}}
	if err := validatePrepare(&body); err == nil {
		t.Fatal("hlsSegments flv accepted")
	}
}
