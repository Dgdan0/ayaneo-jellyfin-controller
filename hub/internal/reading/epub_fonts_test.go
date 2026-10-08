package reading

import (
	"archive/zip"
	"bytes"
	"crypto/sha1"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"math/rand"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"unicode/utf16"
)

const (
	// The UUID of Light Bringer's calibre identifier: its fonts are scrambled with it
	// while the package's unique identifier is 25103610.
	lightBringerUUID = "39b44fd9-3b9a-4fbc-9a39-34147f45dfb0"
	strangerUUID     = "c1b2a3d4-0f1e-4d5c-8b7a-695847362514"
)

// fakeFont is a file that starts as a font does and is random after it, so that a
// wrong mask length or a wrong byte shows.
func fakeFont(signature string, size int, seed int64) []byte {
	font := make([]byte, size)
	rand.New(rand.NewSource(seed)).Read(font)
	copy(font, signature)
	return font
}

// scrambleAdobe is the Adobe algorithm, written the way its specification says it:
// the 16 bytes of the UUID over the first 1024 bytes.
func scrambleAdobe(t *testing.T, font []byte, uuid string) []byte {
	t.Helper()
	key, err := hex.DecodeString(strings.ReplaceAll(strings.TrimPrefix(uuid, "urn:uuid:"), "-", ""))
	if err != nil || len(key) != 16 {
		t.Fatalf("not a UUID: %q", uuid)
	}
	out := append([]byte(nil), font...)
	for i := 0; i < len(out) && i < 1024; i++ {
		out[i] ^= key[i%16]
	}
	return out
}

// scrambleIDPF is the IDPF algorithm: the SHA-1 of the identifier with its white
// space removed, over the first 1040 bytes.
func scrambleIDPF(font []byte, identifier string) []byte {
	stripped := strings.NewReplacer(" ", "", "\t", "", "\r", "", "\n", "").Replace(identifier)
	key := sha1.Sum([]byte(stripped))
	out := append([]byte(nil), font...)
	for i := 0; i < len(out) && i < 1040; i++ {
		out[i] ^= key[i%20]
	}
	return out
}

// The two algorithms against vectors made with another implementation (Python), so
// that this file's helpers and the copy cannot be wrong in the same way.
func TestFontKeysMatchKnownAnswers(t *testing.T) {
	plain, _ := hex.DecodeString("4f54544f000b0080000300300102030405060708090a0b0c")
	adobe, _ := hex.DecodeString("76e01b963b914f3c9a3a34247e47dcb43cb248d1329044b0")
	idpf, _ := hex.DecodeString("2ad5b31695f2cf96c63ccb188440849ae62657aa6c8bec55")

	got, ok := unscramble(adobe, fontKeys(obfuscationAdobe, []string{"urn:uuid:" + lightBringerUUID}))
	if !ok || !bytes.Equal(got, plain) {
		t.Errorf("adobe: %x, %v", got, ok)
	}
	got, ok = unscramble(idpf, fontKeys(obfuscationIDPF, []string{"  urn:isbn:978-0-345-53978-5 \n"}))
	if !ok || !bytes.Equal(got, plain) {
		t.Errorf("idpf: %x, %v", got, ok)
	}
	if !bytes.Equal(scrambleAdobe(t, plain, lightBringerUUID), adobe) || !bytes.Equal(scrambleIDPF(plain, "  urn:isbn:978-0-345-53978-5 \n"), idpf) {
		t.Error("the test helpers disagree with the vectors")
	}
}

// What makes an Adobe key: a UUID, with or without its urn, in any case; nothing
// else makes one. An IDPF key is made of anything.
func TestFontKeysFromIdentifiers(t *testing.T) {
	want := []byte{0x39, 0xb4, 0x4f, 0xd9, 0x3b, 0x9a, 0x4f, 0xbc, 0x9a, 0x39, 0x34, 0x14, 0x7f, 0x45, 0xdf, 0xb0}
	for _, id := range []string{
		"urn:uuid:" + lightBringerUUID, "URN:UUID:" + strings.ToUpper(lightBringerUUID), lightBringerUUID,
		"  urn:uuid:" + lightBringerUUID + "\n", strings.ReplaceAll(lightBringerUUID, "-", ""),
		// Storyteller rewrites the identifiers of a book it imports: urn:uuid: becomes
		// uuid:, and opf:scheme="calibre" a calibre: prefix.
		"uuid:" + lightBringerUUID, "calibre:" + lightBringerUUID, "{" + lightBringerUUID + "}",
	} {
		keys := fontKeys(obfuscationAdobe, []string{id})
		if len(keys) != 1 || !bytes.Equal(keys[0].key, want) || keys[0].mask != 1024 {
			t.Errorf("%q: %+v", id, keys)
		}
	}
	for _, id := range []string{"25103610", "9780425285947", "urn:isbn:978", "w/dark-age-pierce-brown/1127950377", "", "zz" + lightBringerUUID[2:], lightBringerUUID + "0"} {
		if keys := fontKeys(obfuscationAdobe, []string{id}); len(keys) != 0 {
			t.Errorf("%q made an Adobe key", id)
		}
		if keys := fontKeys(obfuscationIDPF, []string{id}); len(keys) != 1 || keys[0].mask != 1040 || len(keys[0].key) != 20 {
			t.Errorf("%q: IDPF keys %+v", id, keys)
		}
	}
	if keys := fontKeys("http://example.com/other", []string{lightBringerUUID}); len(keys) != 0 {
		t.Errorf("an unknown algorithm made keys: %+v", keys)
	}
	// The same key twice is tried once, and the unique identifier comes first, then the
	// urn:uuid ones, then the rest.
	ids := []string{"25103610", "calibre:" + strangerUUID, "uuid:" + lightBringerUUID, "urn:uuid:" + lightBringerUUID, lightBringerUUID}
	keys := fontKeys(obfuscationAdobe, ids)
	if len(keys) != 2 || hex.EncodeToString(keys[0].key) != strings.ReplaceAll(lightBringerUUID, "-", "") || hex.EncodeToString(keys[1].key) != strings.ReplaceAll(strangerUUID, "-", "") {
		t.Errorf("order: %+v", keys)
	}
}

func TestFontSignatures(t *testing.T) {
	for _, head := range []string{"OTTO", "\x00\x01\x00\x00", "true", "wOFF", "wOF2", "ttcf", "OTTO more"} {
		if !startsAsFont([]byte(head)) {
			t.Errorf("%q is a font", head)
		}
	}
	for _, head := range []string{"", "OTT", "otto", "\x00\x01\x00\x01", "PK\x03\x04", "<?xm", "\x89PNG"} {
		if startsAsFont([]byte(head)) {
			t.Errorf("%q is not a font", head)
		}
	}
}

// encryptedFont is one entry of encryption.xml as Calibre writes it.
func encryptedFont(algorithm, uri string) string {
	return "  <enc:EncryptedData>\n    <enc:EncryptionMethod Algorithm=\"" + algorithm + "\"/>\n    <enc:CipherData>\n      <enc:CipherReference URI=\"" + uri + "\"/>\n    </enc:CipherData>\n  </enc:EncryptedData>\n"
}

// encryptedDRM is an entry that is not a font's: an AES-encrypted resource whose key
// is wrapped for a reader, as a store's DRM writes it.
func encryptedDRM(uri string) string {
	return "  <enc:EncryptedData>\n    <enc:EncryptionMethod Algorithm=\"http://www.w3.org/2001/04/xmlenc#aes128-cbc\"/>\n    <ds:KeyInfo xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"><ds:RetrievalMethod URI=\"license.lcpl#/encryption/content_key\" Type=\"http://readium.org/2014/01/lcp#EncryptedContentKey\"/></ds:KeyInfo>\n    <enc:CipherData>\n      <enc:CipherReference URI=\"" + uri + "\"/>\n    </enc:CipherData>\n  </enc:EncryptedData>\n"
}

func encryptionXML(entries ...string) string {
	return "<encryption xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" xmlns:enc=\"http://www.w3.org/2001/04/xmlenc#\" xmlns:deenc=\"http://ns.adobe.com/digitaleditions/enc\">\n" + strings.Join(entries, "") + "</encryption>\n"
}

// fontBook is a hand-made book with fonts in it.
type fontBook struct {
	identifiers string // dc:identifier elements
	unique      string // the id the package names as its unique identifier
	encryption  string // META-INF/encryption.xml, or none
	files       []bookFile
	fixed       bool
}

func (b fontBook) write(t *testing.T) string {
	t.Helper()
	meta := ""
	if b.fixed {
		meta = `<meta property="rendition:layout">pre-paginated</meta>`
	}
	opf := `<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="` + b.unique +
		`"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><dc:title>T</dc:title>` + b.identifiers +
		`<dc:language>en</dc:language>` + meta + `</metadata><manifest><item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/><item id="css" href="Styles/book.css" media-type="text/css"/></manifest><spine><itemref idref="ch1"/></spine></package>`
	files := []bookFile{{"META-INF/container.xml", bookContainer, false}}
	if b.encryption != "" {
		files = append(files, bookFile{"META-INF/encryption.xml", b.encryption, false})
	}
	files = append(files,
		bookFile{"OEBPS/content.opf", opf, false},
		bookFile{"OEBPS/Styles/book.css", gameOfThronesCSS, false},
		bookFile{"OEBPS/Text/ch1.xhtml", chapterXHTML, false},
	)
	files = append(files, b.files...)
	files = append(files, bookFile{"OEBPS/images/cover.jpg", "not really a jpeg but not text either", true})
	return makeBook(t, files)
}

// unchangedByTheCopy: the entries that the restyle does not touch, as they are in the
// copy and in the source.
func unchangedByTheCopy(name string) bool {
	return name != "OEBPS/Styles/book.css" && name != "OEBPS/Text/ch1.xhtml"
}

func sourceAndCopy(t *testing.T, path string, options CopyOptions) (sourceFiles []*zip.File, source map[string][]byte, copyFiles []*zip.File, copied map[string][]byte, report CopyReport) {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	sourceFiles, source = archiveOf(t, raw)
	data, report := copyOf(t, path, options)
	copyFiles, copied = archiveOf(t, data)
	return sourceFiles, source, copyFiles, copied, report
}

func without(names []string, gone string) []string {
	var out []string
	for _, name := range names {
		if name != gone {
			out = append(out, name)
		}
	}
	return out
}

// The common case: the unique identifier is the UUID the fonts are scrambled with.
// Both fonts come out as they were before they were scrambled, in the entries they
// were in, with the method they had, and encryption.xml is gone because nothing else
// was in it.
func TestFontsAdobeAreUnscrambledUnderTheUniqueIdentifier(t *testing.T) {
	otf := fakeFont("OTTO", 5000, 1)
	ttf := fakeFont("\x00\x01\x00\x00", 1500, 2)
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "OEBPS/Fonts/a.otf"), encryptedFont(obfuscationAdobe, "OEBPS/Fonts/b.ttf")),
		files: []bookFile{
			{"OEBPS/Fonts/a.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false},
			{"OEBPS/Fonts/b.ttf", string(scrambleAdobe(t, ttf, lightBringerUUID)), true},
		},
	}.write(t)
	sourceFiles, source, copyFiles, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})

	if !bytes.Equal(copied["OEBPS/Fonts/a.otf"], otf) || !bytes.Equal(copied["OEBPS/Fonts/b.ttf"], ttf) {
		t.Fatal("the fonts are not as they were before they were scrambled")
	}
	if bytes.Equal(source["OEBPS/Fonts/a.otf"], otf) {
		t.Fatal("the test font was never scrambled")
	}
	if _, there := copied["META-INF/encryption.xml"]; there {
		t.Error("encryption.xml is still in the copy")
	}
	if got, want := namesOf(copyFiles), without(namesOf(sourceFiles), "META-INF/encryption.xml"); !reflect.DeepEqual(got, want) {
		t.Fatalf("entries %v, want %v", got, want)
	}
	if copyFiles[0].Name != "mimetype" || copyFiles[0].Method != zip.Store {
		t.Errorf("the first entry is %q, method %d", copyFiles[0].Name, copyFiles[0].Method)
	}
	methods := map[string]uint16{}
	for _, file := range copyFiles {
		methods[file.Name] = file.Method
	}
	if methods["OEBPS/Fonts/a.otf"] != zip.Deflate || methods["OEBPS/Fonts/b.ttf"] != zip.Store {
		t.Errorf("methods %v: a stored font stays stored and a compressed one compressed", methods)
	}
	for name, content := range source {
		if name == "OEBPS/Fonts/a.otf" || name == "OEBPS/Fonts/b.ttf" || name == "META-INF/encryption.xml" || !unchangedByTheCopy(name) {
			continue
		}
		if !bytes.Equal(copied[name], content) {
			t.Errorf("%s changed", name)
		}
	}
	// Two fonts, and two more entries that changed: the stylesheet and the chapter.
	if report.FontsDecoded != 2 || report.Edited != 4 || len(report.Left) != 0 {
		t.Errorf("report = %+v", report)
	}
}

func TestFontsIDPFAreUnscrambledUnderTheUniqueIdentifierWithoutItsWhiteSpace(t *testing.T) {
	// Longer than the 1040 bytes that are masked, and an identifier with white space.
	woff := fakeFont("wOFF", 4000, 3)
	id := "  urn:isbn:978-0-345-53978-5 \n"
	path := fontBook{
		identifiers: `<dc:identifier id="pub">` + id + `</dc:identifier>`, unique: "pub",
		encryption: encryptionXML(encryptedFont(obfuscationIDPF, "OEBPS/Fonts/a.woff")),
		files:      []bookFile{{"OEBPS/Fonts/a.woff", string(scrambleIDPF(woff, id)), false}},
	}.write(t)
	_, _, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["OEBPS/Fonts/a.woff"], woff) {
		t.Fatal("the font is not as it was, in its first 1040 bytes or after them")
	}
	if _, there := copied["META-INF/encryption.xml"]; there || report.FontsDecoded != 1 {
		t.Errorf("report = %+v", report)
	}
}

// Light Bringer: the unique identifier is `25103610` and the key is the UUID the book
// also lists, twice. Readium takes the first and gets Times for Trajan.
func TestFontsKeyedOnAnIdentifierThatIsNotTheUniqueOneAreUnscrambled(t *testing.T) {
	otf := fakeFont("OTTO", 3000, 4)
	identifiers := `<dc:identifier id="uid">25103610</dc:identifier>` +
		`<dc:identifier opf:scheme="UUID">urn:uuid:` + lightBringerUUID + `</dc:identifier>` +
		`<dc:source>9780425285978</dc:source>` +
		`<dc:identifier opf:scheme="calibre">urn:uuid:` + lightBringerUUID + `</dc:identifier>`
	path := fontBook{
		identifiers: identifiers, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "OEBPS/Fonts/font00498.otf")),
		files:      []bookFile{{"OEBPS/Fonts/font00498.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
	}.write(t)
	_, _, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["OEBPS/Fonts/font00498.otf"], otf) || report.FontsDecoded != 1 {
		t.Fatalf("report = %+v", report)
	}
	if _, there := copied["META-INF/encryption.xml"]; there {
		t.Error("encryption.xml is still there")
	}

	// The same book as Storyteller stores it: the identifiers carry their scheme as
	// a prefix, urn:uuid: having become uuid:.
	path = fontBook{
		identifiers: `<dc:identifier id="uid">25103610</dc:identifier><dc:identifier>uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "OEBPS/Fonts/font00498.otf")),
		files:      []bookFile{{"OEBPS/Fonts/font00498.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
	}.write(t)
	_, _, _, copied, report = sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["OEBPS/Fonts/font00498.otf"], otf) || report.FontsDecoded != 1 {
		t.Fatalf("storyteller's: report = %+v", report)
	}

	// IDPF takes any identifier, the last one here.
	woff := fakeFont("wOF2", 2500, 5)
	path = fontBook{
		identifiers: `<dc:identifier id="uid">25103610</dc:identifier><dc:identifier>first</dc:identifier><dc:identifier>the key</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationIDPF, "OEBPS/Fonts/f.woff2")),
		files:      []bookFile{{"OEBPS/Fonts/f.woff2", string(scrambleIDPF(woff, "the key")), false}},
	}.write(t)
	_, _, _, copied, report = sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["OEBPS/Fonts/f.woff2"], woff) || report.FontsDecoded != 1 {
		t.Fatalf("idpf: report = %+v", report)
	}
}

// Dark Age lists its calibre UUID with no urn:uuid: prefix. Which element is the
// unique identifier is the package's word: the font is found even when the attribute
// names no element at all.
func TestFontsAreFoundWhenTheKeyIsABareUUIDOrThePackageNamesNoUniqueIdentifier(t *testing.T) {
	otf := fakeFont("OTTO", 2200, 6)
	for name, book := range map[string]fontBook{
		"a bare UUID": {
			identifiers: `<dc:identifier id="uid">251773235</dc:identifier><dc:identifier opf:scheme="calibre">` + lightBringerUUID + `</dc:identifier><dc:identifier opf:scheme="ISBN">9780425285947</dc:identifier>`,
			unique:      "uid",
		},
		"no such unique identifier": {
			identifiers: `<dc:identifier>251773235</dc:identifier><dc:identifier>` + lightBringerUUID + `</dc:identifier>`, unique: "missing",
		},
	} {
		book.encryption = encryptionXML(encryptedFont(obfuscationAdobe, "f.otf"))
		book.files = []bookFile{{"f.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}}
		_, _, _, copied, report := sourceAndCopy(t, book.write(t), CopyOptions{Restyle: true})
		if !bytes.Equal(copied["f.otf"], otf) || report.FontsDecoded != 1 {
			t.Errorf("%s: report = %+v", name, report)
		}
	}
}

// A font that no identifier of the book unscrambles is the book's problem, not a
// reason to damage it: it stays as it was, with its entry, and the report says so.
func TestFontsNoKeyUnscramblesAreLeftAsTheyWereWithTheirEntries(t *testing.T) {
	otf := fakeFont("OTTO", 3000, 7)
	scrambled := string(scrambleAdobe(t, otf, strangerUUID))
	encryption := encryptionXML(encryptedFont(obfuscationAdobe, "OEBPS/Fonts/a.otf"), encryptedFont(obfuscationAdobe, "OEBPS/Fonts/b.otf"))
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier><dc:identifier>other</dc:identifier>`, unique: "uid",
		encryption: encryption,
		files:      []bookFile{{"OEBPS/Fonts/a.otf", scrambled, false}, {"OEBPS/Fonts/b.otf", scrambled, true}},
	}.write(t)
	sourceFiles, source, copyFiles, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !reflect.DeepEqual(namesOf(copyFiles), namesOf(sourceFiles)) {
		t.Fatalf("entries %v", namesOf(copyFiles))
	}
	for name, content := range source {
		if unchangedByTheCopy(name) && !bytes.Equal(copied[name], content) {
			t.Errorf("%s changed", name)
		}
	}
	if string(copied["META-INF/encryption.xml"]) != encryption {
		t.Errorf("encryption.xml changed:\n%s", copied["META-INF/encryption.xml"])
	}
	if report.FontsDecoded != 0 || report.Edited != 2 {
		t.Errorf("report = %+v", report)
	}
	got := map[string]string{}
	for _, left := range report.Left {
		got[left.Name] = left.Reason
	}
	if !reflect.DeepEqual(got, map[string]string{"OEBPS/Fonts/a.otf": "font_key", "OEBPS/Fonts/b.otf": "font_key"}) {
		t.Errorf("left alone: %v", got)
	}
}

// Only the fonts that were unscrambled leave the file. What it lists besides (a font
// no key unscrambles, a resource under real encryption) stays exactly as it was,
// down to the lines around it.
func TestFontsOnlyTheUnscrambledEntriesLeaveEncryptionXML(t *testing.T) {
	good := fakeFont("OTTO", 2000, 8)
	bad := fakeFont("OTTO", 2000, 9)
	drm := []byte("encrypted chapter, not a font at all")
	comment := "  <!-- kept -->\n"
	encryption := encryptionXML(
		encryptedFont(obfuscationAdobe, "OEBPS/Fonts/bad.otf"),
		encryptedDRM("OEBPS/Text/ch2.xhtml"),
		comment,
		encryptedFont(obfuscationAdobe, "OEBPS/Fonts/good.otf"),
		encryptedFont(obfuscationAdobe, "OEBPS/Fonts/good2.otf"),
	)
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryption,
		files: []bookFile{
			{"OEBPS/Fonts/bad.otf", string(scrambleAdobe(t, bad, strangerUUID)), false},
			{"OEBPS/Text/ch2.xhtml", string(drm), false},
			{"OEBPS/Fonts/good.otf", string(scrambleAdobe(t, good, lightBringerUUID)), false},
			{"OEBPS/Fonts/good2.otf", string(scrambleAdobe(t, good, lightBringerUUID)), false},
		},
	}.write(t)
	_, source, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})

	want := encryptionXML(encryptedFont(obfuscationAdobe, "OEBPS/Fonts/bad.otf"), encryptedDRM("OEBPS/Text/ch2.xhtml"), comment)
	if got := string(copied["META-INF/encryption.xml"]); got != want {
		t.Fatalf("encryption.xml\n%s\nwant\n%s", got, want)
	}
	if !wellFormed(copied["META-INF/encryption.xml"]) {
		t.Error("encryption.xml is not well-formed")
	}
	if !bytes.Equal(copied["OEBPS/Fonts/good.otf"], good) || !bytes.Equal(copied["OEBPS/Fonts/good2.otf"], good) {
		t.Error("the good fonts are not unscrambled")
	}
	for _, name := range []string{"OEBPS/Fonts/bad.otf", "OEBPS/Text/ch2.xhtml"} {
		if !bytes.Equal(copied[name], source[name]) {
			t.Errorf("%s changed", name)
		}
	}
	// Two fonts; the stylesheet, the chapter and encryption.xml itself.
	if report.FontsDecoded != 2 || report.Edited != 5 {
		t.Errorf("report = %+v", report)
	}
}

// A book whose only encryption is DRM is what it was, entry for entry.
func TestFontsDRMAloneIsNotTouched(t *testing.T) {
	encryption := encryptionXML(encryptedDRM("OEBPS/Text/ch2.xhtml"))
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryption,
		files:      []bookFile{{"OEBPS/Text/ch2.xhtml", "ciphertext", false}},
	}.write(t)
	sourceFiles, source, copyFiles, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !reflect.DeepEqual(namesOf(copyFiles), namesOf(sourceFiles)) || string(copied["META-INF/encryption.xml"]) != encryption {
		t.Fatal("a DRM book changed")
	}
	if !bytes.Equal(copied["OEBPS/Text/ch2.xhtml"], source["OEBPS/Text/ch2.xhtml"]) || report.FontsDecoded != 0 || len(report.Left) != 0 {
		t.Errorf("report = %+v", report)
	}
}

// Fixed-layout books are the publisher's pages and are not touched; nor is a copy
// that was not asked to restyle.
func TestFontsAreLeftAloneInAFixedLayoutBookAndWithoutRestyle(t *testing.T) {
	otf := fakeFont("OTTO", 2000, 10)
	book := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "f.otf")),
		files:      []bookFile{{"f.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
	}
	for name, test := range map[string]struct {
		fixed   bool
		options CopyOptions
	}{
		"fixed layout":  {true, CopyOptions{Restyle: true}},
		"no restyle":    {false, CopyOptions{OmitAudio: true}},
		"empty options": {false, CopyOptions{}},
	} {
		book.fixed = test.fixed
		sourceFiles, source, copyFiles, copied, report := sourceAndCopy(t, book.write(t), test.options)
		// None of the three restyles anything, so every entry is the source's.
		if !reflect.DeepEqual(namesOf(copyFiles), namesOf(sourceFiles)) {
			t.Errorf("%s: entries %v", name, namesOf(copyFiles))
		}
		for entry, content := range source {
			if !bytes.Equal(copied[entry], content) {
				t.Errorf("%s: %s changed", name, entry)
			}
		}
		if report.FontsDecoded != 0 || report.Edited != 0 {
			t.Errorf("%s: report = %+v", name, report)
		}
	}
}

// The same book is the same bytes every time, the served plan is the written copy,
// and a copy of a copy has nothing left to do.
func TestFontsTheCopyIsDeterministicServedAsWrittenAndFinished(t *testing.T) {
	path := fontBook{
		identifiers: `<dc:identifier id="uid">25103610</dc:identifier><dc:identifier>urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "f/a.otf"), encryptedFont(obfuscationAdobe, "f/b.otf")),
		files: []bookFile{
			{"f/a.otf", string(scrambleAdobe(t, fakeFont("OTTO", 70<<10, 11), lightBringerUUID)), false},
			{"f/b.otf", string(scrambleAdobe(t, fakeFont("OTTO", 3000, 12), lightBringerUUID)), false},
		},
	}.write(t)
	first, report := copyOf(t, path, CopyOptions{Restyle: true})
	second, _ := copyOf(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(first, second) || report.FontsDecoded != 2 {
		t.Fatalf("two runs differ, or %+v", report)
	}

	file, size := openEPUB(t, path)
	plan, err := PlanReadingEPUB(file, size, CopyOptions{Restyle: true})
	if err != nil {
		t.Fatal(err)
	}
	served, err := io.ReadAll(plan.Reader(file))
	if err != nil || !bytes.Equal(served, first) || plan.SHA256 != sha256.Sum256(first) || plan.Report.FontsDecoded != 2 {
		t.Fatalf("the plan is not the copy: %v", err)
	}

	again := filepath.Join(t.TempDir(), "again.epub")
	if err := os.WriteFile(again, first, 0o644); err != nil {
		t.Fatal(err)
	}
	third, report := copyOf(t, again, CopyOptions{Restyle: true})
	if report.FontsDecoded != 0 || report.Edited != 0 || !bytes.Equal(first, third) {
		t.Fatalf("a second pass: %+v", report)
	}
}

// What the copy must still be: a zip every reader opens, with every entry's checksum
// good, the mimetype first and stored, and XML that parses.
func TestFontsTheCopyIsAWellFormedEPUB(t *testing.T) {
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "OEBPS/Fonts/a.otf"), encryptedDRM("OEBPS/Text/x.xhtml")),
		files: []bookFile{
			{"OEBPS/Fonts/a.otf", string(scrambleAdobe(t, fakeFont("OTTO", 9000, 13), lightBringerUUID)), false},
			{"OEBPS/Text/x.xhtml", "x", false},
		},
	}.write(t)
	data, _ := copyOf(t, path, CopyOptions{Restyle: true})
	reader, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	if reader.File[0].Name != "mimetype" || reader.File[0].Method != zip.Store {
		t.Errorf("first entry %q (%d)", reader.File[0].Name, reader.File[0].Method)
	}
	for _, entry := range reader.File {
		stream, err := entry.Open()
		if err != nil {
			t.Fatal(err)
		}
		body, err := io.ReadAll(stream) // verifies the CRC
		stream.Close()
		if err != nil {
			t.Errorf("%s: %v", entry.Name, err)
		}
		if strings.HasSuffix(entry.Name, ".xml") || strings.HasSuffix(entry.Name, ".opf") || strings.HasSuffix(entry.Name, ".xhtml") {
			if entry.Name != "OEBPS/Text/x.xhtml" && !wellFormed(body) {
				t.Errorf("%s is not well-formed", entry.Name)
			}
		}
	}
}

// A CipherReference is a URI: percent escapes are spaces and non-ASCII names, and a
// listing of something the archive does not hold is left as it is.
func TestFontsReferencesAreURIsAndMissingFontsStay(t *testing.T) {
	otf := fakeFont("OTTO", 2500, 14)
	encryption := encryptionXML(
		encryptedFont(obfuscationAdobe, "OEBPS/Fonts/my%20font.otf"),
		encryptedFont(obfuscationAdobe, "OEBPS/Fonts/gone.otf"),
		encryptedFont(obfuscationAdobe, "../../etc/passwd"),
	)
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryption,
		files:      []bookFile{{"OEBPS/Fonts/my font.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
	}.write(t)
	_, _, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["OEBPS/Fonts/my font.otf"], otf) || report.FontsDecoded != 1 {
		t.Fatalf("report = %+v", report)
	}
	want := encryptionXML(encryptedFont(obfuscationAdobe, "OEBPS/Fonts/gone.otf"), encryptedFont(obfuscationAdobe, "../../etc/passwd"))
	if got := string(copied["META-INF/encryption.xml"]); got != want {
		t.Errorf("encryption.xml\n%s\nwant\n%s", got, want)
	}
}

// Listed twice, a font is read and written once and both entries go.
func TestFontsListedTwiceAreOneFont(t *testing.T) {
	otf := fakeFont("OTTO", 2500, 15)
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "f.otf"), encryptedFont(obfuscationAdobe, "f.otf")),
		files:      []bookFile{{"f.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
	}.write(t)
	_, _, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if _, there := copied["META-INF/encryption.xml"]; there || !bytes.Equal(copied["f.otf"], otf) || report.FontsDecoded != 1 {
		t.Fatalf("report = %+v", report)
	}
}

// If encryption.xml cannot be edited with certainty, no font is unscrambled: a font
// written plain while its entry stays would be scrambled a second time by the reader.
func TestFontsNoneIsUnscrambledWhenEncryptionXMLCannotBeEdited(t *testing.T) {
	otf := fakeFont("OTTO", 2500, 16)
	utf16le := func(text string) string {
		units := utf16.Encode([]rune(text))
		out := []byte{0xFF, 0xFE}
		for _, unit := range units {
			out = append(out, byte(unit), byte(unit>>8))
		}
		return string(out)
	}
	good := encryptionXML(encryptedFont(obfuscationAdobe, "f.otf"))
	for name, encryption := range map[string]string{
		"utf-16":          utf16le(good),
		"latin-1":         strings.Replace(good, "<encryption", "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><encryption", 1),
		"not well-formed": strings.Replace(good, "</enc:EncryptedData>", "</enc:Wrong>", 1),
		"wrong root":      strings.ReplaceAll(strings.ReplaceAll(good, "<encryption", "<other"), "</encryption>", "</other>"),
	} {
		path := fontBook{
			identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
			encryption: encryption,
			files:      []bookFile{{"f.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
		}.write(t)
		_, source, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
		if !bytes.Equal(copied["f.otf"], source["f.otf"]) || string(copied["META-INF/encryption.xml"]) != encryption || report.FontsDecoded != 0 {
			t.Errorf("%s: the book changed: %+v", name, report)
		}
		found := false
		for _, left := range report.Left {
			found = found || left.Name == "META-INF/encryption.xml"
		}
		// A UTF-16 file does not even show that it names a font method, so it is not
		// reported; the rest are.
		if !found && name != "utf-16" {
			t.Errorf("%s: left = %v", name, report.Left)
		}
	}
}

// A font larger than the copy will unpack is left, and says why.
func TestFontsTooLargeToUnpackAreLeftAlone(t *testing.T) {
	otf := fakeFont("OTTO", 5000, 17)
	encryption := encryptionXML(encryptedFont(obfuscationAdobe, "f.otf"))
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryption,
		files:      []bookFile{{"f.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
	}.write(t)
	previous := maxRestyleBytes
	t.Cleanup(func() { maxRestyleBytes = previous })
	maxRestyleBytes = 4000
	_, source, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["f.otf"], source["f.otf"]) || string(copied["META-INF/encryption.xml"]) != encryption || report.FontsDecoded != 0 {
		t.Fatalf("report = %+v", report)
	}
	if !reflect.DeepEqual(report.Left, []LeftAlone{{"f.otf", "too_large"}}) {
		t.Errorf("left = %v", report.Left)
	}
}

// What an unscrambled font costs in memory: it is rewritten, so the plan holds it
// where an entry copied as it was is read from the file, and the held limit counts it.
func TestFontsAreHeldByThePlanAndCountedAgainstItsLimit(t *testing.T) {
	big := fakeFont("OTTO", 300<<10, 18) // random, so it does not compress
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "f.otf")),
		files:      []bookFile{{"f.otf", string(scrambleAdobe(t, big, lightBringerUUID)), false}},
	}.write(t)
	file, size := openEPUB(t, path)
	plan, err := PlanReadingEPUB(file, size, CopyOptions{Restyle: true})
	if err != nil {
		t.Fatal(err)
	}
	if plan.Held() < 300<<10 {
		t.Errorf("a plan with a 300 KB font holds %d bytes", plan.Held())
	}
	if limited, err := PlanReadingEPUB(file, size, CopyOptions{Restyle: true, MaxHeld: 100 << 10}); limited != nil || !errors.Is(err, ErrCopyTooLarge) {
		t.Fatalf("a plan over its limit: %v", err)
	}
	if _, err := PlanReadingEPUB(file, size, CopyOptions{Restyle: true, MaxHeld: plan.Held()}); err != nil {
		t.Fatalf("a plan exactly at its limit: %v", err)
	}
}

// A source that cannot be read while the fonts are checked stops the copy with the
// reader's own error.
func TestFontsASourceThatFailsStopsTheCopy(t *testing.T) {
	path := fontBook{
		identifiers: `<dc:identifier id="uid">urn:uuid:` + lightBringerUUID + `</dc:identifier>`, unique: "uid",
		encryption: encryptionXML(encryptedFont(obfuscationAdobe, "f.otf")),
		files:      []bookFile{{"f.otf", string(scrambleAdobe(t, fakeFont("OTTO", 40<<10, 19), lightBringerUUID)), true}},
	}.write(t)
	file, size := openEPUB(t, path)
	archive, err := zip.NewReader(file, size)
	if err != nil {
		t.Fatal(err)
	}
	var from, to int64
	for _, entry := range archive.File {
		if entry.Name == "f.otf" {
			from, err = entry.DataOffset()
			if err != nil {
				t.Fatal(err)
			}
			// The first 10 KB of it: the end of the file is read to find the directory.
			to = from + 10<<10
		}
	}
	gone := errors.New("the disk went away")
	_, err = PlanReadingEPUB(&failInRangeReaderAt{ReaderAt: file, from: from, to: to, err: gone}, size, CopyOptions{Restyle: true})
	if !errors.Is(err, gone) {
		t.Fatalf("err = %v", err)
	}
}

type failInRangeReaderAt struct {
	io.ReaderAt
	from, to int64
	err      error
}

func (f *failInRangeReaderAt) ReadAt(p []byte, offset int64) (int, error) {
	if offset < f.to && offset+int64(len(p)) > f.from {
		return 0, f.err
	}
	return f.ReaderAt.ReadAt(p, offset)
}

// An entry of the package that is not a UUID cannot make an Adobe key, and a book
// that lists no identifiers at all is left as it is (nothing to try).
func TestFontsABookWithNoIdentifierIsLeftAlone(t *testing.T) {
	otf := fakeFont("OTTO", 2000, 20)
	encryption := encryptionXML(encryptedFont(obfuscationAdobe, "f.otf"))
	path := fontBook{
		identifiers: ``, unique: "uid", encryption: encryption,
		files: []bookFile{{"f.otf", string(scrambleAdobe(t, otf, lightBringerUUID)), false}},
	}.write(t)
	_, source, _, copied, report := sourceAndCopy(t, path, CopyOptions{Restyle: true})
	if !bytes.Equal(copied["f.otf"], source["f.otf"]) || string(copied["META-INF/encryption.xml"]) != encryption || report.FontsDecoded != 0 {
		t.Fatalf("report = %+v", report)
	}
}

// Whatever the identifiers, keys and sizes, a font that is scrambled with one of the
// book's identifiers comes back exactly, and the copy reads as a book.
func TestFontsRandomBooksRoundTrip(t *testing.T) {
	random := rand.New(rand.NewSource(99))
	signatures := []string{"OTTO", "\x00\x01\x00\x00", "true", "wOFF", "wOF2"}
	for round := 0; round < 60; round++ {
		uuid := make([]byte, 16)
		random.Read(uuid)
		uuidText := hex.EncodeToString(uuid[:4]) + "-" + hex.EncodeToString(uuid[4:6]) + "-" + hex.EncodeToString(uuid[6:8]) + "-" + hex.EncodeToString(uuid[8:10]) + "-" + hex.EncodeToString(uuid[10:])
		// The identifier the fonts are scrambled with, as the book writes it.
		keyText := []string{"urn:uuid:" + uuidText, uuidText, " " + uuidText + "\n"}[random.Intn(3)]
		algorithm, scramble := obfuscationAdobe, func(font []byte) []byte { return scrambleAdobe(t, font, uuidText) }
		if random.Intn(2) == 0 {
			algorithm, scramble = obfuscationIDPF, func(font []byte) []byte { return scrambleIDPF(font, keyText) }
		}
		var identifiers strings.Builder
		unique := "u0"
		count := 1 + random.Intn(4)
		keyAt := random.Intn(count)
		for i := 0; i < count; i++ {
			text := "noise-" + hex.EncodeToString([]byte{byte(random.Intn(256)), byte(i)})
			if i == keyAt {
				text = keyText
			}
			identifiers.WriteString(`<dc:identifier id="u` + string(rune('0'+i)) + `">` + text + `</dc:identifier>`)
		}
		var entries []string
		var files []bookFile
		plains := map[string][]byte{}
		fonts := 1 + random.Intn(4)
		for i := 0; i < fonts; i++ {
			name := "OEBPS/Fonts/f" + string(rune('a'+i)) + ".bin"
			plain := fakeFont(signatures[random.Intn(len(signatures))], 4+random.Intn(4000), int64(round*10+i))
			plains[name] = plain
			entries = append(entries, encryptedFont(algorithm, name))
			files = append(files, bookFile{name, string(scramble(plain)), random.Intn(2) == 0})
		}
		path := fontBook{identifiers: identifiers.String(), unique: unique, encryption: encryptionXML(entries...), files: files}.write(t)
		data, report := copyOf(t, path, CopyOptions{Restyle: true})
		_, copied := archiveOf(t, data)
		for name, plain := range plains {
			if !bytes.Equal(copied[name], plain) {
				t.Fatalf("round %d: %s (%s, key at %d of %d) is not as it was", round, name, algorithm, keyAt, count)
			}
		}
		if _, there := copied["META-INF/encryption.xml"]; there || report.FontsDecoded != len(plains) {
			t.Fatalf("round %d: report = %+v", round, report)
		}
	}
}
