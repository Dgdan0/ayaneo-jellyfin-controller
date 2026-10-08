package reading

import (
	"archive/zip"
	"bytes"
	"crypto/sha1" //nolint:gosec // SHA-1 is the key the IDPF obfuscation algorithm defines, not a security measure.
	"encoding/hex"
	"encoding/xml"
	"io"
	"strings"
)

// Obfuscated fonts, and the key that unscrambles them.
//
// A publisher can hide an embedded font from casual copying: META-INF/encryption.xml
// lists it under one of two algorithms and the first bytes of the file are XORed
// with a key made of the book's identifier.
//
//	Adobe  http://ns.adobe.com/pdf/enc#RC           the 16 bytes of the identifier's UUID, over the first 1024 bytes
//	IDPF   http://www.idpf.org/2008/embedding       the SHA-1 of the identifier without whitespace (20 bytes), over the first 1040
//
// Readium (both apps) takes the identifier from the package's unique identifier. A
// book that went through Calibre can carry another one for the key: Light Bringer's
// unique identifier is `25103610` and its fonts are scrambled with the
// `urn:uuid:39b44fd9-…` it also lists, so its Trajan headings fell back to Times. The
// copy therefore finds the key itself, tries the unique identifier and then every other
// one, keeps the first that makes a font (a file that starts as a font does), writes
// the font unscrambled and takes its entry out of encryption.xml so that no reader
// unscrambles it a second time. A font no key unscrambles stays as it was, with its
// entry, and so does everything else the file lists (real DRM).
const (
	obfuscationAdobe = "http://ns.adobe.com/pdf/enc#RC"
	obfuscationIDPF  = "http://www.idpf.org/2008/embedding"

	encryptionEntry = "META-INF/encryption.xml"

	// leftFontKey: a font that no identifier of the book unscrambles.
	leftFontKey = "font_key"

	adobeMaskBytes = 1024
	idpfMaskBytes  = 1040
)

// fontKey is a key and how many bytes at the start of the font it covers.
type fontKey struct {
	key  []byte
	mask int
}

// fontKeys are the keys the identifiers make for an algorithm, the first identifier
// (the unique one) first, without repeats. For Adobe's the identifiers that name
// themselves UUIDs (`urn:uuid:`, `uuid:`) come before the rest after it; an
// identifier that is no UUID makes no Adobe key.
func fontKeys(algorithm string, identifiers []string) []fontKey {
	ordered := identifiers
	if algorithm == obfuscationAdobe && len(identifiers) > 1 {
		ordered = []string{identifiers[0]}
		for _, id := range identifiers[1:] {
			if uuidScheme(id) {
				ordered = append(ordered, id)
			}
		}
		for _, id := range identifiers[1:] {
			if !uuidScheme(id) {
				ordered = append(ordered, id)
			}
		}
	}
	var keys []fontKey
	seen := map[string]bool{}
	for _, id := range ordered {
		var made fontKey
		switch algorithm {
		case obfuscationAdobe:
			key, ok := adobeKey(id)
			if !ok {
				continue
			}
			made = fontKey{key, adobeMaskBytes}
		case obfuscationIDPF:
			sum := sha1.Sum([]byte(withoutSpace(id))) //nolint:gosec // see the import
			made = fontKey{sum[:], idpfMaskBytes}
		default:
			return nil
		}
		if !seen[string(made.key)] {
			seen[string(made.key)] = true
			keys = append(keys, made)
		}
	}
	return keys
}

// uuidScheme: the identifier says it is a UUID, as `urn:uuid:…` or `uuid:…`.
func uuidScheme(identifier string) bool {
	text := strings.ToLower(withoutSpace(identifier))
	return strings.HasPrefix(text, "urn:uuid:") || strings.HasPrefix(text, "uuid:")
}

// withoutSpace drops the whitespace the IDPF algorithm ignores: space, tab, CR, LF.
func withoutSpace(text string) string {
	return strings.Map(func(r rune) rune {
		switch r {
		case ' ', '\t', '\r', '\n':
			return -1
		}
		return r
	}, text)
}

// adobeKey is the 16 bytes of the UUID an identifier is: its 32 hex digits after
// whatever scheme precedes them (`urn:uuid:`, the `uuid:` that Storyteller writes
// for it, a `calibre:` that it makes of opf:scheme), with or without dashes and
// braces.
func adobeKey(identifier string) ([]byte, bool) {
	text := withoutSpace(identifier)
	text = text[strings.LastIndexByte(text, ':')+1:]
	text = strings.ReplaceAll(strings.Trim(text, "{}"), "-", "")
	if len(text) != 32 {
		return nil, false
	}
	key, err := hex.DecodeString(text)
	if err != nil {
		return nil, false
	}
	return key, true
}

// startsAsFont: the file begins with a signature of TrueType (0x00010000, `true`),
// OpenType (`OTTO`), a collection (`ttcf`) or WOFF (`wOFF`, `wOF2`).
func startsAsFont(head []byte) bool {
	if len(head) < 4 {
		return false
	}
	switch string(head[:4]) {
	case "OTTO", "\x00\x01\x00\x00", "true", "wOFF", "wOF2", "ttcf":
		return true
	}
	return false
}

// unscramble returns data with the first key that makes it a font applied, or false.
// Only four bytes are looked at to choose a key: a wrong one gives a signature by
// chance one time in 700 million.
func unscramble(data []byte, keys []fontKey) ([]byte, bool) {
	for _, candidate := range keys {
		if len(data) < 4 {
			return nil, false
		}
		var head [4]byte
		for i := range head {
			head[i] = data[i] ^ candidate.key[i%len(candidate.key)]
		}
		if !startsAsFont(head[:]) {
			continue
		}
		out := append([]byte(nil), data...)
		for i := 0; i < min(candidate.mask, len(out)); i++ {
			out[i] ^= candidate.key[i%len(candidate.key)]
		}
		return out, true
	}
	return nil, false
}

// readScrambledFont reads a font entry whole and unscrambles it. When it cannot, the
// second result says why (leftFontKey, leftTooLarge or leftUnread); the error is the
// source failing to be read.
func readScrambledFont(entry *zip.File, keys []fontKey) ([]byte, string, error) {
	if entry.UncompressedSize64 > uint64(maxRestyleBytes) {
		return nil, leftTooLarge, nil
	}
	stream, err := entry.Open()
	if err != nil {
		return nil, leftUnread, nil
	}
	data, err := io.ReadAll(io.LimitReader(stream, maxRestyleBytes+1))
	stream.Close()
	if err != nil {
		if isDamagedEntry(err) {
			return nil, leftUnread, nil
		}
		return nil, "", err
	}
	if int64(len(data)) > maxRestyleBytes {
		return nil, leftTooLarge, nil
	}
	if decoded, ok := unscramble(data, keys); ok {
		return decoded, "", nil
	}
	return nil, leftFontKey, nil
}

// encryptedData is one entry of encryption.xml: where its element lies in the file,
// the algorithm it names and the resource it is for.
type encryptedData struct {
	span      span
	algorithm string
	uri       string
}

// encryptionListing is encryption.xml read.
type encryptionListing struct {
	entries []encryptedData
	// children is how many elements the root has (EncryptedData, and anything else).
	children int
}

// parseEncryption reads encryption.xml with the byte range of each EncryptedData.
// False when it is not a well-formed UTF-8 document of the shape.
func parseEncryption(doc []byte) (encryptionListing, bool) {
	var listing encryptionListing
	if !isUTF8Text(doc) || declaresOtherCharset(doc) {
		return listing, false
	}
	decoder := xml.NewDecoder(bytes.NewReader(doc))
	var stack []string
	var current *encryptedData
	for {
		offset := int(decoder.InputOffset())
		token, err := decoder.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return listing, false
		}
		switch t := token.(type) {
		case xml.StartElement:
			stack = append(stack, t.Name.Local)
			switch depth := len(stack); {
			case depth == 1:
				if t.Name.Local != "encryption" {
					return listing, false
				}
			case depth == 2:
				listing.children++
				if t.Name.Local == "EncryptedData" {
					current = &encryptedData{span: span{from: offset}}
				}
			case current != nil && depth == 3 && t.Name.Local == "EncryptionMethod" && current.algorithm == "":
				current.algorithm = strings.TrimSpace(attribute(t, "Algorithm"))
			case current != nil && depth == 4 && t.Name.Local == "CipherReference" && stack[2] == "CipherData" && current.uri == "":
				current.uri = attribute(t, "URI")
			}
		case xml.EndElement:
			if len(stack) == 2 && current != nil {
				current.span.to = int(decoder.InputOffset())
				listing.entries = append(listing.entries, *current)
				current = nil
			}
			if len(stack) > 0 {
				stack = stack[:len(stack)-1]
			}
		}
	}
	return listing, true
}

// withoutEntries is doc without the byte ranges of the given entries, and the white
// space in front of each, so that the lines around them stay as they were. The
// ranges are in order.
func withoutEntries(doc []byte, entries []encryptedData) []byte {
	var out bytes.Buffer
	cursor := 0
	for _, entry := range entries {
		from := entry.span.from
		for from > cursor && (doc[from-1] == ' ' || doc[from-1] == '\t' || doc[from-1] == '\r' || doc[from-1] == '\n') {
			from--
		}
		out.Write(doc[cursor:from])
		cursor = entry.span.to
	}
	out.Write(doc[cursor:])
	return out.Bytes()
}

// fontPlan is what the copy does about a book's obfuscated fonts.
type fontPlan struct {
	// fonts are the entries to be written unscrambled, with the keys that do it.
	fonts map[string][]fontKey
	// encryption is encryption.xml without the fonts' entries; none when the file is
	// to go (drop).
	encryption []byte
	drop       bool
}

// planFonts decides, before any entry is written, which of the book's fonts to
// unscramble: each font that encryption.xml lists under the Adobe or IDPF method and
// that some identifier of the book unscrambles. It reads every such font whole and
// keeps none, so that a damaged font is known before encryption.xml, which may come
// first in the archive, is rewritten; memory stays at one font. It answers nil when
// there is nothing to do, and reports a font it could not unscramble, and an
// encryption.xml it could not safely edit, in report.Left. The error is the source
// failing to be read.
func planFonts(archive *zip.Reader, identifiers []string, report *CopyReport) (*fontPlan, error) {
	var listed *zip.File
	for _, entry := range archive.File {
		if entry.Name == encryptionEntry {
			listed = entry
		}
	}
	if listed == nil || len(identifiers) == 0 {
		return nil, nil
	}
	byName := make(map[string]*zip.File, len(archive.File))
	for _, entry := range archive.File {
		byName[entry.Name] = entry
	}
	doc, err := readXMLEntry(byName, encryptionEntry)
	if err != nil || !(bytes.Contains(doc, []byte(obfuscationAdobe)) || bytes.Contains(doc, []byte(obfuscationIDPF))) {
		// Nothing there that names an obfuscated font (a file this size is DRM or
		// something else the copy has no business with).
		return nil, nil
	}
	listing, ok := parseEncryption(doc)
	if !ok {
		report.Left = append(report.Left, LeftAlone{encryptionEntry, leftEncoding})
		return nil, nil
	}

	plan := &fontPlan{fonts: map[string][]fontKey{}}
	var removed []encryptedData
	tried := map[string]bool{} // entry name -> decoded
	for _, item := range listing.entries {
		if item.algorithm != obfuscationAdobe && item.algorithm != obfuscationIDPF {
			continue
		}
		font := fontEntryFor(byName, item.uri)
		if font == nil {
			continue
		}
		decoded, seen := tried[font.Name]
		if !seen {
			keys := fontKeys(item.algorithm, identifiers)
			_, left, err := readScrambledFont(font, keys)
			if err != nil {
				return nil, err
			}
			decoded = left == ""
			tried[font.Name] = decoded
			if decoded {
				plan.fonts[font.Name] = keys
			} else {
				report.Left = append(report.Left, LeftAlone{font.Name, left})
			}
		}
		if decoded {
			removed = append(removed, item)
		}
	}
	if len(removed) == 0 {
		return nil, nil
	}

	rewritten := withoutEntries(doc, removed)
	after, ok := parseEncryption(rewritten)
	if !ok || after.children != listing.children-len(removed) {
		// Unscrambling a font while its entry stays would have a reader unscramble it
		// again, so without a file that can be edited no font is touched.
		report.Left = append(report.Left, LeftAlone{encryptionEntry, leftUnread})
		return nil, nil
	}
	if after.children == 0 {
		plan.drop = true
	} else {
		plan.encryption = rewritten
	}
	return plan, nil
}

// fontEntryFor finds the archive entry a CipherReference names: its URI is a path
// from the root of the archive, escaped as a URI.
func fontEntryFor(byName map[string]*zip.File, uri string) *zip.File {
	if resolved, _, ok := resolveRef("", uri); ok {
		if entry := byName[resolved]; entry != nil {
			return entry
		}
	}
	return byName[uri]
}

// write writes the entry as the plan wants it, and says whether it did: an
// unscrambled font, encryption.xml without the fonts' entries, or nothing at all for
// an encryption.xml that listed nothing else. Every other entry is left to the caller.
func (p *fontPlan) write(writer *zip.Writer, entry *zip.File, report *CopyReport) (bool, error) {
	if entry.Name == encryptionEntry {
		if p.drop {
			return true, nil
		}
		if err := writeRewritten(writer, entry, zip.Deflate, p.encryption); err != nil {
			return false, err
		}
		report.Edited++
		return true, nil
	}
	keys, ok := p.fonts[entry.Name]
	if !ok {
		return false, nil
	}
	decoded, left, err := readScrambledFont(entry, keys)
	if err != nil {
		return false, err
	}
	if left != "" {
		// It was a font a moment ago: the file is not what the plan was made from.
		return false, ErrNotAnEPUB
	}
	method := zip.Deflate
	if entry.Method == zip.Store {
		method = zip.Store
	}
	if err := writeRewritten(writer, entry, method, decoded); err != nil {
		return false, err
	}
	report.FontsDecoded++
	report.Edited++
	return true, nil
}
