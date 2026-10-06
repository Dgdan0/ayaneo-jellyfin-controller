package repackage

import (
	"bytes"
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// ffmpeg's MP4 muxer groups audio tracks and subtitle tracks into alternates and
// enables the default audio, but it always turns the first subtitle track on
// (measured with the Jellyfin server's ffmpeg 7.1.3: -disposition does not stop
// it), and a player then shows subtitles nobody asked for. So a finished file is
// patched in place and the state is stated rather than trusted: the audio tracks
// are one alternate group with only the default enabled, and the subtitle tracks
// another with none enabled. That is how HandBrake writes a file with several
// languages, and what AVPlayer builds its audio and subtitle menus from.

func box(kind string, parts ...[]byte) []byte {
	size := 8
	for _, part := range parts {
		size += len(part)
	}
	out := make([]byte, 8, size)
	binary.BigEndian.PutUint32(out, uint32(size))
	copy(out[4:], kind)
	for _, part := range parts {
		out = append(out, part...)
	}
	return out
}

func tkhdBox(version byte, flags uint32, trackID uint32, alternate int16) []byte {
	payload := make([]byte, 4)
	payload[0] = version
	payload[1], payload[2], payload[3] = byte(flags>>16), byte(flags>>8), byte(flags)
	if version == 1 {
		payload = append(payload, make([]byte, 8+8)...) // creation, modification
		payload = binary.BigEndian.AppendUint32(payload, trackID)
		payload = append(payload, 0, 0, 0, 0)         // reserved
		payload = append(payload, make([]byte, 8)...) // duration
	} else {
		payload = append(payload, make([]byte, 4+4)...) // creation, modification
		payload = binary.BigEndian.AppendUint32(payload, trackID)
		payload = append(payload, 0, 0, 0, 0)         // reserved
		payload = append(payload, make([]byte, 4)...) // duration
	}
	payload = append(payload, make([]byte, 8)...) // reserved
	payload = append(payload, 0, 0)               // layer
	payload = binary.BigEndian.AppendUint16(payload, uint16(alternate))
	payload = append(payload, 0, 0, 0, 0)          // volume, reserved
	payload = append(payload, make([]byte, 36)...) // matrix
	payload = append(payload, make([]byte, 8)...)  // width, height
	return box("tkhd", payload)
}

func hdlrBox(handler string) []byte {
	payload := make([]byte, 4+4)
	payload = append(payload, handler...)
	payload = append(payload, make([]byte, 12)...)
	payload = append(payload, "Handler\x00"...)
	return box("hdlr", payload)
}

type fakeTrack struct {
	handler   string
	version   byte
	flags     uint32
	alternate int16
}

func trakBox(track fakeTrack, id uint32) []byte {
	return box("trak", tkhdBox(track.version, track.flags, id, track.alternate), box("mdia", hdlrBox(track.handler)))
}

// fakeMP4 is the boxes of a file: a header, the movie, and some media data.
func fakeMP4(moovFirst bool, tracks ...fakeTrack) []byte {
	var children [][]byte
	children = append(children, box("mvhd", make([]byte, 100)))
	for index, track := range tracks {
		children = append(children, trakBox(track, uint32(index+1)))
	}
	moov := box("moov", children...)
	ftyp := box("ftyp", []byte("isom\x00\x00\x02\x00isomiso2mp41"))
	media := box("mdat", bytes.Repeat([]byte{0xAB}, 4096))
	if moovFirst {
		return bytes.Join([][]byte{ftyp, moov, media}, nil)
	}
	return bytes.Join([][]byte{ftyp, media, moov}, nil)
}

// inspectTracks is a deliberately plain reader for the checks: it finds each trak and
// then the first tkhd and hdlr inside it.
func inspectTracks(t *testing.T, data []byte) []fakeTrack {
	t.Helper()
	var tracks []fakeTrack
	for at := 0; ; {
		next := bytes.Index(data[at:], []byte("trak"))
		if next < 0 {
			return tracks
		}
		start := at + next - 4
		size := int(binary.BigEndian.Uint32(data[start:]))
		body := data[start : start+size]
		head := bytes.Index(body, []byte("tkhd")) + 4
		version := body[head]
		flags := uint32(body[head+1])<<16 | uint32(body[head+2])<<8 | uint32(body[head+3])
		alternateAt := head + 4 + 4 + 4 + 4 + 4 + 4 + 8 + 2 // version 0
		if version == 1 {
			alternateAt = head + 4 + 8 + 8 + 4 + 4 + 8 + 8 + 2
		}
		alternate := int16(binary.BigEndian.Uint16(body[alternateAt:]))
		handler := string(body[bytes.Index(body, []byte("hdlr"))+4+4+4 : bytes.Index(body, []byte("hdlr"))+4+4+4+4])
		tracks = append(tracks, fakeTrack{handler: handler, version: version, flags: flags, alternate: alternate})
		at = start + size
	}
}

func writeFake(t *testing.T, data []byte) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "out.mp4")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// threeLanguages is a film with two audio tracks (the second the default) and
// two subtitle tracks, as ffmpeg writes it: the first track of each kind on.
func threeLanguages() (Plan, []fakeTrack) {
	plan, err := PlanApple(Source{Container: "mkv", SizeBytes: 1, DurationSeconds: 60, DefaultAudio: 2,
		Streams: []Stream{
			video(0, "h264", "High", 8, "yuv420p"),
			audio(1, "aac", "eng", 2), audio(2, "ac3", "heb", 6),
			subtitle(3, "subrip", "eng"), subtitle(4, "hdmv_pgs_subtitle", "fre"), subtitle(5, "subrip", "heb"),
		}})
	if err != nil {
		panic(err)
	}
	return plan, []fakeTrack{
		{handler: "vide", flags: 3},
		{handler: "soun", flags: 3}, {handler: "soun", flags: 2},
		{handler: "sbtl", flags: 3}, {handler: "sbtl", flags: 2},
	}
}

func TestPatchGroupsAudioAndSubtitlesAndEnablesOnlyTheDefaultAudio(t *testing.T) {
	plan, tracks := threeLanguages()
	for _, moovFirst := range []bool{true, false} {
		path := writeFake(t, fakeMP4(moovFirst, tracks...))
		if err := PatchTracks(path, plan); err != nil {
			t.Fatalf("moovFirst=%v: %v", moovFirst, err)
		}
		data, _ := os.ReadFile(path)
		got := inspectTracks(t, data)
		want := []fakeTrack{
			{handler: "vide", flags: 3, alternate: 0}, // the picture is left as it was
			{handler: "soun", flags: 2, alternate: 1}, // English: off
			{handler: "soun", flags: 3, alternate: 1}, // Hebrew, the default: on
			{handler: "sbtl", flags: 2, alternate: 3}, // no subtitle on by default
			{handler: "sbtl", flags: 2, alternate: 3},
		}
		if len(got) != len(want) {
			t.Fatalf("moovFirst=%v: %d tracks", moovFirst, len(got))
		}
		for at := range want {
			if got[at] != want[at] {
				t.Errorf("moovFirst=%v: track %d = %+v, want %+v", moovFirst, at, got[at], want[at])
			}
		}
	}
}

func TestPatchChangesNothingButTheTrackHeaders(t *testing.T) {
	plan, tracks := threeLanguages()
	original := fakeMP4(true, tracks...)
	path := writeFake(t, original)
	if err := PatchTracks(path, plan); err != nil {
		t.Fatal(err)
	}
	patched, _ := os.ReadFile(path)
	if len(patched) != len(original) {
		t.Fatalf("the file changed size: %d -> %d", len(original), len(patched))
	}
	// Per track header at most the three flag bytes and the two alternate-group
	// bytes differ.
	changed := 0
	for at := range original {
		if original[at] != patched[at] {
			changed++
		}
	}
	if changed == 0 || changed > 4*(3+2) {
		t.Fatalf("%d bytes changed, want only flags and alternate groups", changed)
	}
	mdat := bytes.Index(original, []byte("mdat"))
	if !bytes.Equal(original[mdat:], patched[mdat:]) {
		t.Fatal("the media data changed")
	}
}

func TestPatchHandlesVersionOneHeadersAndOtherTrackKinds(t *testing.T) {
	plan, tracks := threeLanguages()
	tracks[1].version, tracks[3].version = 1, 1
	tracks = append(tracks, fakeTrack{handler: "tmcd", flags: 3}, fakeTrack{handler: "meta", flags: 3})
	path := writeFake(t, fakeMP4(true, tracks...))
	if err := PatchTracks(path, plan); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	got := inspectTracks(t, data)
	if got[1].alternate != 1 || got[1].flags != 2 || got[3].alternate != 3 || got[3].flags != 2 {
		t.Errorf("version 1 headers were not patched: %+v", got)
	}
	if got[5].flags != 3 || got[6].flags != 3 || got[5].alternate != 0 {
		t.Errorf("a track that is neither audio nor subtitle was touched: %+v", got[5:])
	}
}

func TestPatchRefusesAFileThatDoesNotHoldWhatWasPromised(t *testing.T) {
	plan, tracks := threeLanguages()
	for name, broken := range map[string][]fakeTrack{
		"a missing audio track":    {tracks[0], tracks[1], tracks[3], tracks[4]},
		"an extra audio track":     {tracks[0], tracks[1], tracks[2], tracks[2], tracks[3], tracks[4]},
		"a missing subtitle track": {tracks[0], tracks[1], tracks[2], tracks[3]},
		"no picture":               {tracks[1], tracks[2], tracks[3], tracks[4]},
	} {
		path := writeFake(t, fakeMP4(true, broken...))
		before, _ := os.ReadFile(path)
		err := PatchTracks(path, plan)
		if !errors.Is(err, ErrTracksDiffer) {
			t.Errorf("%s: err = %v, want ErrTracksDiffer", name, err)
		}
		if after, _ := os.ReadFile(path); !bytes.Equal(before, after) {
			t.Errorf("%s: a file that failed the check was changed", name)
		}
	}
}

func TestPatchRefusesWhatIsNotAnMP4WithoutPanicking(t *testing.T) {
	plan, tracks := threeLanguages()
	whole := fakeMP4(true, tracks...)
	for name, data := range map[string][]byte{
		"empty":             {},
		"text":              []byte("this is not a movie"),
		"no movie box":      box("ftyp", []byte("isom")),
		"truncated movie":   whole[:60],
		"a size past end":   append(append([]byte{}, whole[:32]...), 0xff, 0xff, 0xff, 0xff, 'm', 'o', 'o', 'v'),
		"a size below 8":    {0, 0, 0, 4, 'f', 't', 'y', 'p'},
		"a track with none": fakeMP4(true, fakeTrack{handler: "vide", flags: 3}),
	} {
		path := writeFake(t, data)
		if err := PatchTracks(path, plan); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestPatchFindsTheMovieBehindAHugeMediaBox(t *testing.T) {
	// A file over 4 GB writes its media data with a 64-bit size, and with the movie
	// after it the patcher must step over it without reading it.
	plan, tracks := threeLanguages()
	whole := fakeMP4(false, tracks...)
	mdat := bytes.Index(whole, []byte("mdat")) - 4
	oldSize := int(binary.BigEndian.Uint32(whole[mdat:]))
	wide := make([]byte, 0, len(whole)+8)
	wide = append(wide, whole[:mdat]...)
	header := make([]byte, 16)
	binary.BigEndian.PutUint32(header, 1)
	copy(header[4:], "mdat")
	binary.BigEndian.PutUint64(header[8:], uint64(oldSize+8))
	wide = append(wide, header...)
	wide = append(wide, whole[mdat+8:]...)
	path := writeFake(t, wide)
	if err := PatchTracks(path, plan); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(path)
	if got := inspectTracks(t, data); got[2].flags != 3 || got[2].alternate != 1 {
		t.Fatalf("not patched behind a 64-bit media box: %+v", got)
	}
}

func TestPatchOfAFileWithoutAudioOrSubtitlesStillSucceeds(t *testing.T) {
	plan, err := PlanApple(source(video(0, "h264", "High", 8, "yuv420p")))
	if err != nil {
		t.Fatal(err)
	}
	path := writeFake(t, fakeMP4(true, fakeTrack{handler: "vide", flags: 3}))
	if err := PatchTracks(path, plan); err != nil {
		t.Fatal(err)
	}
}
