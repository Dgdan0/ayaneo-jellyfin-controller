package reading

import (
	"archive/zip"
	"encoding/json"
	"encoding/xml"
	"fmt"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// ReadalongPackOptions shapes a generated read-along pack (#66) for an edition made by
// GenerateAlignedEPUB. The zero value is a pack that holds: both sets, passing and re-timed, built
// from the edition as it is, written an hour ago.
type ReadalongPackOptions struct {
	UUID string
	// ShiftMs is how much later than the edition says each sentence begins in the pack (never past
	// its end): the correction a pack exists for. Ends are the edition's, so each audio piece's last
	// clipEnd stays its own.
	ShiftMs int64
	// Unspoken are sentences (fragments) the pack does not narrate: a dramatization's "X said". None
	// may be the last of its audio piece.
	Unspoken []string
	// GapMs is the silence after each word but the last of a sentence.
	GapMs int64
	// Failed, NotRetimed and Stale make a manifest that must not be used: passes false, retimed
	// false, a source that is not the edition (its size one more).
	Failed, NotRetimed, Stale bool
	// Granularities are the sets written and listed; empty is both.
	Granularities []string
	// Written is when every file was last written; zero is an hour ago.
	Written time.Time
	// Extra are files put in a set besides the pack's own, by set and path inside it.
	Extra map[string]map[string][]byte
	// WordStart puts the word spans of the text at this number instead of 0.
	WordStart int
}

// FixtureWord is one timed word of a generated pack's word set.
type FixtureWord struct {
	FixturePar
	// Sentence is the fragment of the sentence it is a word of.
	Sentence string
}

// ReadalongPackFixture is a generated pack and what it says: the sentence set's pars and the
// word set's, as they are to be read.
type ReadalongPackFixture struct {
	Dir       string
	Manifest  PackManifest
	Sentences []FixturePar
	Words     []FixtureWord
}

var fixtureSentenceSpan = regexp.MustCompile(`<span id="([^"]+)">([^<]*)</span>`)
var fixtureWord = regexp.MustCompile(`[\p{L}\p{N}]+`)

// GenerateReadalongPack writes the pack of edition to dir (dir is <readalong>/<uuid>) as wordsync
// writes one: manifest.json, sentence/<each SMIL> and word/<each SMIL and each text document>.
func GenerateReadalongPack(dir string, edition AlignedEPUBFixture, options ReadalongPackOptions) (ReadalongPackFixture, error) {
	reader, err := zip.OpenReader(edition.Path)
	if err != nil {
		return ReadalongPackFixture{}, err
	}
	defer reader.Close()
	byName := map[string]*zip.File{}
	for _, entry := range reader.File {
		byName[entry.Name] = entry
	}
	// Which overlay narrates which text document, from the package.
	packageData, err := readXMLEntry(byName, edition.Package)
	if err != nil {
		return ReadalongPackFixture{}, err
	}
	hrefs, overlays := map[string]string{}, map[string]string{}
	scanLenientXML(packageData, func(start xml.StartElement, _ string) {
		if start.Name.Local != "item" {
			return
		}
		if href, _, ok := resolveRef(edition.Package, attribute(start, "href")); ok {
			hrefs[attribute(start, "id")] = href
			if overlay := attribute(start, "media-overlay"); overlay != "" {
				overlays[href] = overlay
			}
		}
	})
	smilOf := map[string]string{}
	for text, overlayID := range overlays {
		smilOf[text] = hrefs[overlayID]
	}
	encoded := strings.Contains(string(packageData), "%20") || strings.Contains(string(packageData), "%5B")
	ref := func(from, to string) string {
		relative := relativeRef(path.Dir(from), to)
		if !encoded {
			return relative
		}
		parts := strings.Split(relative, "/")
		for i, part := range parts {
			if part != ".." {
				parts[i] = url.PathEscape(part)
			}
		}
		return strings.Join(parts, "/")
	}

	granularities := options.Granularities
	if len(granularities) == 0 {
		granularities = []string{GranularitySentence, GranularityWord}
	}
	unspoken := map[string]bool{}
	for _, fragment := range options.Unspoken {
		unspoken[fragment] = true
	}
	fixture := ReadalongPackFixture{Dir: dir}
	// The sentences of each text document in the order the edition lists them, re-timed.
	type spoken struct {
		par   FixturePar
		words []FixtureWord
	}
	byText := map[string][]spoken{}
	var textOrder []string
	texts := map[string]string{}
	for _, par := range edition.Pars {
		if unspoken[par.Fragment] {
			continue
		}
		begin := min(par.BeginMs+options.ShiftMs, par.EndMs-1)
		timed := par
		timed.BeginMs = begin
		if _, seen := byText[par.Text]; !seen {
			textOrder = append(textOrder, par.Text)
			data, err := readXMLEntry(byName, par.Text)
			if err != nil {
				return ReadalongPackFixture{}, err
			}
			texts[par.Text] = string(data)
		}
		sentenceText := ""
		for _, match := range fixtureSentenceSpan.FindAllStringSubmatch(texts[par.Text], -1) {
			if match[1] == par.Fragment {
				sentenceText = match[2]
			}
		}
		words := fixtureWord.FindAllString(sentenceText, -1)
		if len(words) == 0 {
			return ReadalongPackFixture{}, fmt.Errorf("sentence %s has no words", par.Fragment)
		}
		step := (par.EndMs - begin) / int64(len(words))
		var timedWords []FixtureWord
		for k := range words {
			wordBegin := begin + int64(k)*step
			wordEnd := wordBegin + step - options.GapMs
			if k == len(words)-1 {
				wordEnd = par.EndMs
			}
			if wordEnd <= wordBegin {
				wordEnd = wordBegin + 1
			}
			timedWords = append(timedWords, FixtureWord{
				FixturePar: FixturePar{Text: par.Text, Fragment: fmt.Sprintf("%s-w%d", par.Fragment, k+options.WordStart), Audio: par.Audio, BeginMs: wordBegin, EndMs: wordEnd},
				Sentence:   par.Fragment,
			})
		}
		byText[par.Text] = append(byText[par.Text], spoken{timed, timedWords})
		fixture.Sentences = append(fixture.Sentences, timed)
		fixture.Words = append(fixture.Words, timedWords...)
	}

	files := map[string]map[string][]byte{GranularitySentence: {}, GranularityWord: {}}
	clock := func(ms int64) string { return fmt.Sprintf("%d.%03ds", ms/1000, ms%1000) }
	for i, text := range textOrder {
		smil := smilOf[text]
		if smil == "" {
			return ReadalongPackFixture{}, fmt.Errorf("no overlay narrates %s", text)
		}
		head := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>`+"\n"+`<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0">`+"\n"+`  <body>`+"\n"+`    <seq id="o%d_overlay" epub:textref="%s" epub:type="chapter">`+"\n", i, ref(smil, text))
		tail := "    </seq>\n  </body>\n</smil>\n"
		var sentenceSMIL, wordSMIL strings.Builder
		sentenceSMIL.WriteString(head)
		wordSMIL.WriteString(head)
		for _, sentence := range byText[text] {
			par := sentence.par
			fmt.Fprintf(&sentenceSMIL, "      <par id=\"%s\">\n        <text src=\"%s#%s\"/>\n        <audio src=\"%s\" clipBegin=\"%s\" clipEnd=\"%s\"/>\n      </par>\n",
				par.Fragment, ref(smil, text), par.Fragment, ref(smil, par.Audio), clock(par.BeginMs), clock(par.EndMs))
			fmt.Fprintf(&wordSMIL, "      <seq id=\"%s-seq\" epub:textref=\"%s#%s\">\n", par.Fragment, ref(smil, text), par.Fragment)
			for _, word := range sentence.words {
				fmt.Fprintf(&wordSMIL, "        <par id=\"%s\">\n          <text src=\"%s#%s\"/>\n          <audio src=\"%s\" clipBegin=\"%s\" clipEnd=\"%s\"/>\n        </par>\n",
					word.Fragment, ref(smil, text), word.Fragment, ref(smil, word.Audio), clock(word.BeginMs), clock(word.EndMs))
			}
			wordSMIL.WriteString("      </seq>\n")
		}
		sentenceSMIL.WriteString(tail)
		wordSMIL.WriteString(tail)
		files[GranularitySentence][smil] = []byte(sentenceSMIL.String())
		files[GranularityWord][smil] = []byte(wordSMIL.String())
		// The text with each spoken sentence's words in their own spans.
		spokenHere := map[string]bool{}
		for _, sentence := range byText[text] {
			spokenHere[sentence.par.Fragment] = true
		}
		wrapped := fixtureSentenceSpan.ReplaceAllStringFunc(texts[text], func(span string) string {
			match := fixtureSentenceSpan.FindStringSubmatch(span)
			if !spokenHere[match[1]] {
				return span
			}
			n := options.WordStart - 1
			inner := fixtureWord.ReplaceAllStringFunc(match[2], func(word string) string {
				n++
				return fmt.Sprintf(`<span id="%s-w%d">%s</span>`, match[1], n, word)
			})
			return fmt.Sprintf(`<span id="%s">%s</span>`, match[1], inner)
		})
		files[GranularityWord][text] = []byte(wrapped)
	}
	for set, extra := range options.Extra {
		for name, data := range extra {
			files[set][name] = data
		}
	}

	info, err := os.Stat(edition.Path)
	if err != nil {
		return ReadalongPackFixture{}, err
	}
	uuid := options.UUID
	if uuid == "" {
		uuid = filepath.Base(dir)
	}
	manifest := PackManifest{
		Tool: "wordsync-0.2", Built: "2026-10-09T12:00:00", UUID: uuid, Title: "Aligned fixture",
		Source:        PackSource{Path: "/data/assets/Aligned fixture/aligned/Aligned fixture.epub", Size: info.Size(), Mtime: info.ModTime().Unix()},
		Granularities: granularities, Retimed: !options.NotRetimed, Passes: !options.Failed,
	}
	if options.Stale {
		manifest.Source.Size++
	}
	fixture.Manifest = manifest
	written := options.Written
	if written.IsZero() {
		written = time.Now().Add(-time.Hour)
	}
	write := func(name string, data []byte) error {
		target := filepath.Join(dir, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(target, data, 0o644); err != nil {
			return err
		}
		return os.Chtimes(target, written, written)
	}
	for _, set := range granularities {
		names := make([]string, 0, len(files[set]))
		for name := range files[set] {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			if err := write(set+"/"+name, files[set][name]); err != nil {
				return ReadalongPackFixture{}, err
			}
		}
	}
	data, err := json.MarshalIndent(manifest, "", " ")
	if err != nil {
		return ReadalongPackFixture{}, err
	}
	if err := write("manifest.json", data); err != nil {
		return ReadalongPackFixture{}, err
	}
	return fixture, nil
}

// relativeRef is the reference from a document in folder from to the zip path to.
func relativeRef(from, to string) string {
	if from == "." {
		return to
	}
	fromParts, toParts := strings.Split(from, "/"), strings.Split(to, "/")
	common := 0
	for common < len(fromParts) && common < len(toParts)-1 && fromParts[common] == toParts[common] {
		common++
	}
	var out []string
	for range fromParts[common:] {
		out = append(out, "..")
	}
	return strings.Join(append(out, toParts[common:]...), "/")
}
