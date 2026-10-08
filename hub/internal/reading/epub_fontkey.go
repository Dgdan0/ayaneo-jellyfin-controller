package reading

import (
	"encoding/binary"
	"math/bits"
)

// A key that is not in the book.
//
// A font can be scrambled with a UUID that the package no longer lists: Dark Age was
// converted twice with Calibre, the second time giving the package a new UUID while
// the fonts kept the first, so no identifier of the book unscrambles them (the key is
// 3d527742-… and the package says 01ac2674-…). Adobe's algorithm is a repeating
// 16-byte XOR over the first 1024 bytes, and the start of a font is the most
// predictable thing in it, so the key can be worked out from the font itself:
//
//   - The 12-byte header of an sfnt file gives 12 key bytes. After the version (`OTTO`,
//     0x00010000 or `true`) it holds the table count n and three numbers that follow
//     from n alone (searchRange, entrySelector, rangeShift). n is tried from 1 up, and
//     each n that leaves every table record's offset and length (which lie under those
//     12 key bytes) sane is a candidate.
//   - The other 4 key bytes are under the tag of every table record (`CFF `, `OS/2`,
//     `cmap`, `head`, …). A key byte for which every tag in its column is a letter,
//     digit, space or slash leaves a few candidates each.
//   - The key is the candidate under which the tags come in order and every table's
//     checksum, which the directory records, is right. That is not a thing a wrong
//     key does by chance for ten tables of 32 bits each.
//
// This is only for Adobe's algorithm. IDPF's key is a 20-byte SHA-1 of an identifier,
// whose column structure does not leave the same few candidates, and no book here
// is scrambled with one.

var sfntVersions = [...][4]byte{{'O', 'T', 'T', 'O'}, {0, 1, 0, 0}, {'t', 'r', 'u', 'e'}}

const (
	// A font has ten to forty tables; a directory that claims more is not one.
	maxSFNTTables = 128
	// The most key candidates one table count may leave. Fonts leave a few hundred.
	maxKeyCandidates = 1 << 16
)

func tagByte(b byte) bool {
	return b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9' || b == ' ' || b == '/'
}

// recoverAdobeKey works out the Adobe key of a font from the font. It answers false
// for anything that is not an sfnt font scrambled with one, including a font that is
// damaged.
func recoverAdobeKey(font []byte) (fontKey, bool) {
	masked := min(len(font), adobeMaskBytes)
	// Records that lie wholly under the mask.
	for _, version := range sfntVersions {
		for tables := 1; tables <= maxSFNTTables; tables++ {
			directoryEnd := 12 + 16*tables
			if directoryEnd > len(font) {
				break
			}
			full := min(tables, (masked-12)/16)
			if full < 4 {
				continue
			}
			selector := bits.Len(uint(tables)) - 1
			search := 16 << selector
			var plain [12]byte
			copy(plain[:], version[:])
			binary.BigEndian.PutUint16(plain[4:], uint16(tables))
			binary.BigEndian.PutUint16(plain[6:], uint16(search))
			binary.BigEndian.PutUint16(plain[8:], uint16(selector))
			binary.BigEndian.PutUint16(plain[10:], uint16(16*tables-search))
			var key [16]byte
			for i := range plain {
				key[i] = font[i] ^ plain[i]
			}
			if !recordsAreSane(font, &key, full, directoryEnd) {
				continue
			}
			// The four bytes still unknown are under the tags.
			var candidates [4][]byte
			combinations := 1
			for column := 0; column < 4; column++ {
				for x := 0; x < 256; x++ {
					fits := true
					for record := 0; record < full && fits; record++ {
						fits = tagByte(font[12+16*record+column] ^ byte(x))
					}
					if fits {
						candidates[column] = append(candidates[column], byte(x))
					}
				}
				combinations *= len(candidates[column])
				if combinations == 0 || combinations > maxKeyCandidates {
					combinations = 0
					break
				}
			}
			if combinations == 0 {
				continue
			}
			for _, a := range candidates[0] {
				for _, b := range candidates[1] {
					for _, c := range candidates[2] {
						for _, d := range candidates[3] {
							key[12], key[13], key[14], key[15] = a, b, c, d
							if fontKeyChecks(font, key[:], tables) {
								return fontKey{append([]byte(nil), key[:]...), adobeMaskBytes}, true
							}
						}
					}
				}
			}
		}
	}
	return fontKey{}, false
}

// recordsAreSane: under the key's first 12 bytes, which are all that the offset and
// length of a record are under, each of the first full records points inside the font,
// to a place after the directory, on a 4-byte boundary.
func recordsAreSane(font []byte, key *[16]byte, full, directoryEnd int) bool {
	for record := 0; record < full; record++ {
		base := 12 + 16*record
		var field [8]byte
		for i := range field {
			field[i] = font[base+8+i] ^ key[(base+8+i)%16]
		}
		offset, length := binary.BigEndian.Uint32(field[:4]), binary.BigEndian.Uint32(field[4:])
		if offset%4 != 0 || int64(offset) < int64(directoryEnd) || length == 0 || int64(offset)+int64(length) > int64(len(font)) {
			return false
		}
	}
	return true
}

// fontKeyChecks: with this key the font's table directory is in order and every
// table has the checksum its record gives.
func fontKeyChecks(font, key []byte, tables int) bool {
	masked := min(len(font), adobeMaskBytes)
	var head [adobeMaskBytes]byte
	for i := 0; i < masked; i++ {
		head[i] = font[i] ^ key[i%16]
	}
	at := func(i int) byte {
		if i < masked {
			return head[i]
		}
		return font[i]
	}
	var previous [4]byte
	for record := 0; record < tables; record++ {
		base := 12 + 16*record
		var tag [4]byte
		for i := range tag {
			tag[i] = at(base + i)
			if !tagByte(tag[i]) {
				return false
			}
		}
		if record > 0 && string(tag[:]) <= string(previous[:]) {
			return false
		}
		previous = tag
		word := func(i int) uint32 {
			return uint32(at(i))<<24 | uint32(at(i+1))<<16 | uint32(at(i+2))<<8 | uint32(at(i+3))
		}
		checksum, offset, length := word(base+4), int(word(base+8)), int(word(base+12))
		if offset%4 != 0 || offset < 12+16*tables || length <= 0 || offset > len(font) || length > len(font)-offset {
			return false
		}
		var sum uint32
		for i := 0; i < length; i += 4 {
			var w uint32
			for k := 0; k < 4; k++ {
				var b byte
				if i+k < length {
					b = at(offset + i + k)
					// A head table is summed with its own adjustment taken as zero.
					if tag == [4]byte{'h', 'e', 'a', 'd'} && i+k >= 8 && i+k < 12 {
						b = 0
					}
				}
				w = w<<8 | uint32(b)
			}
			sum += w
		}
		if sum != checksum {
			return false
		}
	}
	return true
}
