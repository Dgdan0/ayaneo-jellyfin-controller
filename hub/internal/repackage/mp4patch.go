package repackage

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
)

// ErrTracksDiffer means a finished file does not hold the tracks the plan promised,
// so it must not be offered: the manifest told the app what it would find.
var ErrTracksDiffer = errors.New("the file does not hold the tracks that were planned")

var errNotMP4 = errors.New("not a readable MP4")

const (
	// The alternate groups ffmpeg itself writes for audio and for subtitles. A
	// number only has to be the same across the tracks that are alternatives.
	audioGroup    = 1
	subtitleGroup = 3

	tkhdEnabled   = 0x000001
	maxMovieBytes = 256 << 20
)

// PatchTracks states the track headers of a finished MP4, in place.
//
// ffmpeg groups the audio tracks and the subtitle tracks into alternates and
// enables the default audio, but it always turns the first subtitle track on (the
// Jellyfin server's 7.1.3 does, whatever -disposition says), and a player then
// shows subtitles nobody asked for. So the headers are not trusted but written:
// every audio track is in one alternate group with only the default enabled, and
// every subtitle track is in another with none enabled. AVPlayer builds its audio
// and subtitle menus from those groups, and shows no subtitle until one is chosen.
//
// Only the flag bytes and the two bytes of the group are rewritten. The tracks
// found must be exactly the ones the plan promised, checked before anything is
// written.
func PatchTracks(path string, plan Plan) error {
	file, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	movieAt, movieSize, err := findMovie(file, info.Size())
	if err != nil {
		return err
	}
	movie := make([]byte, movieSize)
	if _, err := file.ReadAt(movie, movieAt); err != nil {
		return fmt.Errorf("%w: %v", errNotMP4, err)
	}
	tracks, err := readTracks(movie)
	if err != nil {
		return err
	}

	var pictures, sounds, subtitles []trackHeader
	for _, track := range tracks {
		switch track.handler {
		case "vide":
			pictures = append(pictures, track)
		case "soun":
			sounds = append(sounds, track)
		case "sbtl", "text", "subt":
			subtitles = append(subtitles, track)
		}
	}
	wantSubtitles := 0
	for _, track := range plan.Subtitles {
		if track.Available {
			wantSubtitles++
		}
	}
	if len(pictures) != 1 || len(sounds) != len(plan.Audio) || len(subtitles) != wantSubtitles {
		return fmt.Errorf("%w: found %d picture, %d audio and %d subtitle tracks, planned 1, %d and %d",
			ErrTracksDiffer, len(pictures), len(sounds), len(subtitles), len(plan.Audio), wantSubtitles)
	}

	type write struct {
		at   int64
		data []byte
	}
	var writes []write
	state := func(track trackHeader, group uint16, enabled bool) {
		flags := track.flags &^ tkhdEnabled
		if enabled {
			flags |= tkhdEnabled
		}
		writes = append(writes,
			write{movieAt + int64(track.flagsAt), []byte{byte(flags >> 16), byte(flags >> 8), byte(flags)}},
			write{movieAt + int64(track.groupAt), binary.BigEndian.AppendUint16(nil, group)})
	}
	for at, track := range sounds {
		state(track, audioGroup, plan.Audio[at].Default)
	}
	for _, track := range subtitles {
		state(track, subtitleGroup, false)
	}
	for _, change := range writes {
		if _, err := file.WriteAt(change.data, change.at); err != nil {
			return err
		}
	}
	return file.Sync()
}

// findMovie walks the top-level boxes, stepping over the media data without
// reading it, and returns where the movie box is and how long.
func findMovie(file io.ReaderAt, fileSize int64) (at, size int64, err error) {
	for position, boxes := int64(0), 0; position+8 <= fileSize && boxes < 100_000; boxes++ {
		header := make([]byte, 16)
		read, _ := file.ReadAt(header, position)
		if read < 8 {
			break
		}
		length := int64(binary.BigEndian.Uint32(header))
		kind := string(header[4:8])
		switch {
		case length == 1 && read >= 16:
			length = int64(binary.BigEndian.Uint64(header[8:16]))
		case length == 0:
			length = fileSize - position
		}
		if length < 8 || position+length > fileSize {
			return 0, 0, fmt.Errorf("%w: a box of %d bytes at %d", errNotMP4, length, position)
		}
		if kind == "moov" {
			if length > maxMovieBytes {
				return 0, 0, fmt.Errorf("%w: a movie box of %d bytes", errNotMP4, length)
			}
			return position, length, nil
		}
		position += length
	}
	return 0, 0, fmt.Errorf("%w: no movie box", errNotMP4)
}

// trackHeader is where one track's handler, flags and alternate group are.
type trackHeader struct {
	handler string
	flags   uint32
	flagsAt int // offsets into the movie box
	groupAt int
}

type boxRef struct {
	kind         string
	payload, end int
}

// childBoxes lists the boxes between two offsets of a buffer.
func childBoxes(buffer []byte, from, to int) ([]boxRef, error) {
	var out []boxRef
	for position := from; position+8 <= to; {
		length := int(binary.BigEndian.Uint32(buffer[position:]))
		header := 8
		switch {
		case length == 1:
			if position+16 > to {
				return nil, fmt.Errorf("%w: a truncated box header", errNotMP4)
			}
			wide := binary.BigEndian.Uint64(buffer[position+8:])
			if wide > uint64(to-position) {
				return nil, fmt.Errorf("%w: a box past its parent", errNotMP4)
			}
			length, header = int(wide), 16
		case length == 0:
			length = to - position
		}
		if length < header || position+length > to {
			return nil, fmt.Errorf("%w: a box of %d bytes", errNotMP4, length)
		}
		out = append(out, boxRef{kind: string(buffer[position+4 : position+8]), payload: position + header, end: position + length})
		position += length
	}
	return out, nil
}

func findBox(boxes []boxRef, kind string) (boxRef, bool) {
	for _, candidate := range boxes {
		if candidate.kind == kind {
			return candidate, true
		}
	}
	return boxRef{}, false
}

// readTracks finds each track of a movie box: its handler type and where its
// header's flags and alternate group sit.
func readTracks(movie []byte) ([]trackHeader, error) {
	top, err := childBoxes(movie, 0, len(movie))
	if err != nil || len(top) != 1 || top[0].kind != "moov" {
		return nil, fmt.Errorf("%w: the movie box", errNotMP4)
	}
	inside, err := childBoxes(movie, top[0].payload, top[0].end)
	if err != nil {
		return nil, err
	}
	var tracks []trackHeader
	for _, candidate := range inside {
		if candidate.kind != "trak" {
			continue
		}
		parts, err := childBoxes(movie, candidate.payload, candidate.end)
		if err != nil {
			return nil, err
		}
		header, hasHeader := findBox(parts, "tkhd")
		media, hasMedia := findBox(parts, "mdia")
		if !hasHeader || !hasMedia {
			return nil, fmt.Errorf("%w: a track without a header or media", errNotMP4)
		}
		mediaParts, err := childBoxes(movie, media.payload, media.end)
		if err != nil {
			return nil, err
		}
		handlerBox, hasHandler := findBox(mediaParts, "hdlr")
		if !hasHandler || handlerBox.end-handlerBox.payload < 12 {
			return nil, fmt.Errorf("%w: a track without a handler", errNotMP4)
		}
		// version and flags, then a version-dependent run of times and ids,
		// then reserved space, the layer, and the alternate group.
		groupOffset := 0
		switch version := movie[header.payload]; version {
		case 0:
			groupOffset = 4 + 4 + 4 + 4 + 4 + 4 + 8 + 2
		case 1:
			groupOffset = 4 + 8 + 8 + 4 + 4 + 8 + 8 + 2
		default:
			return nil, fmt.Errorf("%w: a track header of version %d", errNotMP4, version)
		}
		if header.payload+groupOffset+2 > header.end {
			return nil, fmt.Errorf("%w: a short track header", errNotMP4)
		}
		tracks = append(tracks, trackHeader{
			handler: string(movie[handlerBox.payload+8 : handlerBox.payload+12]),
			flags:   uint32(movie[header.payload+1])<<16 | uint32(movie[header.payload+2])<<8 | uint32(movie[header.payload+3]),
			flagsAt: header.payload + 1,
			groupAt: header.payload + groupOffset,
		})
	}
	return tracks, nil
}
