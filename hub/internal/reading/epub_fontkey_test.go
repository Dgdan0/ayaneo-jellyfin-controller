package reading

import (
	"bytes"
	"encoding/binary"
	"math/rand"
	"sort"
	"testing"
)

// miniSFNT builds a font file the way a font tool does: the header, a directory of
// 16-byte records in tag order, then the tables on 4-byte boundaries, each record
// carrying its table's checksum, and a head table whose adjustment makes the whole
// file sum to 0xB1B0AFBA. The tables hold random bytes; sizes[i] is the size of the
// i-th table in tag order.
func miniSFNT(version string, tags []string, sizes []int, seed int64) []byte {
	random := rand.New(rand.NewSource(seed))
	sorted := append([]string(nil), tags...)
	sort.Strings(sorted)
	n := len(sorted)
	entrySelector := 0
	for 1<<(entrySelector+1) <= n {
		entrySelector++
	}
	searchRange := 16 << entrySelector

	header := make([]byte, 12, 12+16*n)
	copy(header, version)
	binary.BigEndian.PutUint16(header[4:], uint16(n))
	binary.BigEndian.PutUint16(header[6:], uint16(searchRange))
	binary.BigEndian.PutUint16(header[8:], uint16(entrySelector))
	binary.BigEndian.PutUint16(header[10:], uint16(16*n-searchRange))

	sum := func(b []byte) uint32 {
		var total uint32
		for i := 0; i < len(b); i += 4 {
			var w [4]byte
			copy(w[:], b[i:])
			total += binary.BigEndian.Uint32(w[:])
		}
		return total
	}
	directory := make([]byte, 0, 16*n)
	var body []byte
	headAt := -1
	for i, tag := range sorted {
		size := sizes[i%len(sizes)]
		table := make([]byte, size)
		random.Read(table)
		offset := 12 + 16*n + len(body)
		if tag == "head" {
			if size < 54 {
				table = append(table, make([]byte, 54-size)...)
				size = 54
			}
			headAt = offset
			copy(table[8:12], []byte{0, 0, 0, 0})
		}
		record := make([]byte, 16)
		copy(record, tag)
		binary.BigEndian.PutUint32(record[4:], sum(table))
		binary.BigEndian.PutUint32(record[8:], uint32(offset))
		binary.BigEndian.PutUint32(record[12:], uint32(size))
		directory = append(directory, record...)
		body = append(body, table...)
		body = append(body, make([]byte, (4-size%4)%4)...)
	}
	font := append(append(header, directory...), body...)
	if headAt >= 0 {
		binary.BigEndian.PutUint32(font[headAt+8:], 0xB1B0AFBA-sum(font))
	}
	return font
}

var (
	otfTags = []string{"BASE", "CFF ", "DSIG", "GDEF", "GPOS", "GSUB", "OS/2", "cmap", "head", "hhea", "hmtx", "maxp", "name", "post"}
	ttfTags = []string{"DSIG", "GDEF", "GPOS", "GSUB", "OS/2", "cmap", "cvt ", "fpgm", "gasp", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name", "post", "prep"}
	// What a subsetting tool leaves: nine tables.
	smallTags = []string{"OS/2", "cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "name"}
)

// manyTags are n different tags of letters and digits, as private tables are named,
// with a head among them.
func manyTags(n int) []string {
	random := rand.New(rand.NewSource(int64(n)))
	const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
	seen := map[string]bool{"head": true}
	tags := []string{"head"}
	for len(tags) < n {
		tag := make([]byte, 4)
		for i := range tag {
			tag[i] = alphabet[random.Intn(len(alphabet))]
		}
		if !seen[string(tag)] {
			seen[string(tag)] = true
			tags = append(tags, string(tag))
		}
	}
	return tags
}

func randomKey(random *rand.Rand) []byte {
	key := make([]byte, 16)
	random.Read(key)
	return key
}

func scrambleWith(font, key []byte) []byte {
	out := append([]byte(nil), font...)
	for i := 0; i < len(out) && i < 1024; i++ {
		out[i] ^= key[i%16]
	}
	return out
}

// sfntChecksums reads a font the way a font validator starts: whether the whole file
// sums to 0xB1B0AFBA (head's adjustment) and whether every table sums to the checksum
// of its record (a head with its adjustment as zero).
func sfntChecksums(font []byte) (whole, tables bool) {
	sum := func(b []byte) uint32 {
		var total uint32
		for i := 0; i < len(b); i += 4 {
			var w [4]byte
			copy(w[:], b[i:])
			total += binary.BigEndian.Uint32(w[:])
		}
		return total
	}
	whole = sum(font) == 0xB1B0AFBA
	tables = true
	for i := 0; i < int(binary.BigEndian.Uint16(font[4:])); i++ {
		record := font[12+16*i:]
		body := append([]byte(nil), font[binary.BigEndian.Uint32(record[8:]):][:binary.BigEndian.Uint32(record[12:])]...)
		if string(record[:4]) == "head" {
			copy(body[8:12], []byte{0, 0, 0, 0})
		}
		tables = tables && sum(body) == binary.BigEndian.Uint32(record[4:])
	}
	return whole, tables
}

// The test fonts are fonts: their checksums, which the recovery relies on, are right.
func TestMiniSFNTHasTheChecksumsOfAFont(t *testing.T) {
	for _, tags := range [][]string{otfTags, ttfTags, smallTags, manyTags(70)} {
		font := miniSFNT("OTTO", tags, []int{54, 700, 3001, 90, 1500}, 1)
		if whole, tables := sfntChecksums(font); !whole || !tables {
			t.Errorf("%d tables: whole=%v tables=%v", len(tags), whole, tables)
		}
	}
}

// The key of a scrambled font is found from the font alone, whatever it is: OpenType
// or TrueType, ten tables or seventy (whose directory runs past the scrambled bytes).
func TestAdobeKeyIsRecoveredFromTheFont(t *testing.T) {
	random := rand.New(rand.NewSource(5))
	sizes := []int{54, 700, 3001, 90, 1500, 12, 2200}
	for _, version := range []string{"OTTO", "\x00\x01\x00\x00", "true"} {
		for _, tags := range [][]string{otfTags, ttfTags, smallTags, manyTags(70), manyTags(10)} {
			for round := 0; round < 5; round++ {
				key := randomKey(random)
				font := miniSFNT(version, tags, sizes, int64(round))
				got, ok := recoverAdobeKey(scrambleWith(font, key))
				if !ok || !bytes.Equal(got.key, key) || got.mask != 1024 {
					t.Fatalf("%q with %d tables, key %x: got %x, %v", version, len(tags), key, got.key, ok)
				}
			}
		}
	}
}

// A font that is in the clear is its own answer: the key is all zeros.
func TestAdobeKeyOfAFontInTheClearIsNothing(t *testing.T) {
	font := miniSFNT("OTTO", otfTags, []int{500, 1200, 90}, 3)
	got, ok := recoverAdobeKey(font)
	if !ok || !bytes.Equal(got.key, make([]byte, 16)) {
		t.Fatalf("got %x, %v", got.key, ok)
	}
}

// Nothing is guessed: a font with a table that does not match its record, or with
// too little to go on, or that is not a font, gives no key.
func TestAdobeKeyIsNotMadeUpForWhatIsNotAScrambledFont(t *testing.T) {
	random := rand.New(rand.NewSource(6))
	key := randomKey(random)
	font := miniSFNT("OTTO", otfTags, []int{54, 700, 3001, 90, 1500}, 4)

	damaged := append([]byte(nil), font...)
	damaged[len(damaged)-5] ^= 0x01 // inside the last table, past the scrambled bytes
	if _, ok := recoverAdobeKey(scrambleWith(damaged, key)); ok {
		t.Error("a key was found for a font with a bad table")
	}
	if _, ok := recoverAdobeKey(scrambleWith(font[:len(font)/2], key)); ok {
		t.Error("a key was found for a font cut in half")
	}
	few := miniSFNT("OTTO", []string{"CFF ", "head", "name"}, []int{200, 300}, 7)
	if _, ok := recoverAdobeKey(scrambleWith(few, key)); ok {
		t.Error("a key was found from three tables")
	}
	noise := make([]byte, 5000)
	random.Read(noise)
	if _, ok := recoverAdobeKey(noise); ok {
		t.Error("a key was found for noise")
	}
	if _, ok := recoverAdobeKey(scrambleWith(bytes.Repeat([]byte("not a font at all "), 300), key)); ok {
		t.Error("a key was found for text")
	}
	for _, tiny := range [][]byte{nil, {}, {1}, make([]byte, 11), make([]byte, 12), make([]byte, 40)} {
		if _, ok := recoverAdobeKey(tiny); ok {
			t.Errorf("a key was found for %d bytes", len(tiny))
		}
	}
}

// Whatever it is given, it answers rather than panics.
func TestAdobeKeyRecoveryTakesAnyBytes(t *testing.T) {
	random := rand.New(rand.NewSource(7))
	font := miniSFNT("OTTO", otfTags, []int{54, 700, 3001, 90, 1500}, 8)
	key := randomKey(random)
	scrambled := scrambleWith(font, key)
	for length := 0; length <= len(scrambled); length += 7 {
		recoverAdobeKey(scrambled[:length])
	}
	for round := 0; round < 300; round++ {
		mutated := append([]byte(nil), scrambled...)
		for flips := 1 + random.Intn(6); flips > 0; flips-- {
			mutated[random.Intn(min(len(mutated), 1300))] ^= byte(1 + random.Intn(255))
		}
		recoverAdobeKey(mutated[:random.Intn(len(mutated)+1)])
	}
	for round := 0; round < 300; round++ {
		noise := make([]byte, random.Intn(3000))
		random.Read(noise)
		if _, ok := recoverAdobeKey(noise); ok {
			t.Fatal("a key for noise")
		}
	}
}

// Dark Age: the fonts are scrambled with a UUID that the package does not list, and
// all three share it. The first gives the key away and the others use it.
func TestFontsWithAKeyThatIsNoIdentifierAreUnscrambledFromThemselves(t *testing.T) {
	random := rand.New(rand.NewSource(8))
	key := randomKey(random)
	sizes := []int{54, 700, 3001, 90, 1500}
	a := miniSFNT("OTTO", otfTags, sizes, 11)
	b := miniSFNT("OTTO", smallTags, sizes, 12)
	c := miniSFNT("OTTO", ttfTags, sizes, 13)
	path := fontBook{
		identifiers: `<dc:identifier id="uid">251773235</dc:identifier><dc:identifier opf:scheme="calibre">01ac2674-b708-43cf-9aef-960e2cb49e83</dc:identifier><dc:identifier opf:scheme="ISBN">9780425285947</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "f/a.otf"), encryptedFont(obfuscationAdobe, "f/b.otf"), encryptedFont(obfuscationAdobe, "f/c.otf")),
		files: []bookFile{
			{"f/a.otf", string(scrambleWith(a, key)), false},
			{"f/b.otf", string(scrambleWith(b, key)), false},
			{"f/c.otf", string(scrambleWith(c, key)), true},
		},
	}.write(t)
	_, source, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["f/a.otf"], a) || !bytes.Equal(copied["f/b.otf"], b) || !bytes.Equal(copied["f/c.otf"], c) {
		t.Fatalf("the fonts are not as they were; report = %+v", report)
	}
	if bytes.Equal(source["f/a.otf"], a) {
		t.Fatal("the test font was never scrambled")
	}
	if _, there := copied["META-INF/encryption.xml"]; there {
		t.Error("encryption.xml is still there")
	}
	if report.FontsDecoded != 3 || report.KeysRecovered != 1 || len(report.Left) != 0 {
		t.Errorf("report = %+v", report)
	}
}

// A book's fonts may have different keys: one is an identifier of the book, one is
// found from the font, one is neither and cannot be, and only the last stays listed.
func TestFontsKeysFromIdentifiersFromTheFontAndNeitherSideBySide(t *testing.T) {
	random := rand.New(rand.NewSource(9))
	sizes := []int{54, 700, 3001, 90, 1500}
	known := miniSFNT("OTTO", otfTags, sizes, 21)
	lost := miniSFNT("OTTO", ttfTags, sizes, 22)
	garbage := fakeFont("OTTO", 4000, 23) // a "font" with no tables to speak of
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "known.otf"), encryptedFont(obfuscationAdobe, "lost.otf"), encryptedFont(obfuscationAdobe, "garbage.otf")),
		files: []bookFile{
			{"known.otf", string(scrambleAdobe(t, known, lightBringerUUID)), false},
			{"lost.otf", string(scrambleWith(lost, randomKey(random))), false},
			{"garbage.otf", string(scrambleAdobe(t, garbage, strangerUUID)), false},
		},
	}.write(t)
	_, source, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["known.otf"], known) || !bytes.Equal(copied["lost.otf"], lost) || !bytes.Equal(copied["garbage.otf"], source["garbage.otf"]) {
		t.Fatalf("report = %+v", report)
	}
	if got, want := string(copied["META-INF/encryption.xml"]), encryptionXML(encryptedFont(obfuscationAdobe, "garbage.otf")); got != want {
		t.Errorf("encryption.xml\n%s\nwant\n%s", got, want)
	}
	if report.FontsDecoded != 2 || report.KeysRecovered != 1 || len(report.Left) != 1 || report.Left[0].Name != "garbage.otf" {
		t.Errorf("report = %+v", report)
	}
}

// The recovery is Adobe's: an IDPF key is a SHA-1 of an identifier, which the font
// does not give away, so such a font stays as it was.
func TestFontsIDPFWithAnIdentifierThatIsNotListedStaysScrambled(t *testing.T) {
	font := miniSFNT("OTTO", otfTags, []int{54, 700, 3001, 90, 1500}, 31)
	scrambled := scrambleIDPF(font, "an identifier the package lost")
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationIDPF, "f.otf")),
		files:      []bookFile{{"f.otf", string(scrambled), false}},
	}.write(t)
	_, _, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["f.otf"], scrambled) || report.FontsDecoded != 0 || report.KeysRecovered != 0 {
		t.Fatalf("report = %+v", report)
	}
}

// A font listed as scrambled that is not: the reader would scramble it, so the entry
// goes and the font is written as it was.
func TestFontsListedAsScrambledThatAreNotLoseTheirEntry(t *testing.T) {
	font := miniSFNT("\x00\x01\x00\x00", ttfTags, []int{54, 700, 3001, 90, 1500}, 41)
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "f.ttf")),
		files:      []bookFile{{"f.ttf", string(font), false}},
	}.write(t)
	_, _, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if _, there := copied["META-INF/encryption.xml"]; there || !bytes.Equal(copied["f.ttf"], font) || report.FontsDecoded != 1 {
		t.Fatalf("report = %+v", report)
	}
}
