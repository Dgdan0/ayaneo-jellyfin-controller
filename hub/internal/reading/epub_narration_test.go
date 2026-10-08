package reading

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"strings"
	"testing"
)

// The SMIL says what the narration is read as, and nothing else of it changes.
func TestMendSMILChangesOnlyTheEndsPastTheEndOfTheAudio(t *testing.T) {
	const smil = `<?xml version="1.0" encoding="UTF-8"?>
<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:s="http://www.w3.org/ns/SMIL" version="3.0"><body><seq>
<par id="a"><text src="../t.xhtml#s0"/><audio src="../Audio/00001-00001.mp3" clipBegin="2395.000s" clipEnd="2399.150s"/></par>
<par id="b"><text src="../t.xhtml#s1"/><audio  clipEnd = '2402.160s'  src="../Audio/00001-00001.mp3" clipBegin="2399.150s" /></par>
<par id="c"><text src="../t.xhtml#s2"/><audio src="../Audio/00001-00001.mp3" clipBegin="2402.160s" s:clipEnd="2399.488s"/></par>
<par id="d"><text src="../t.xhtml#s3"/><audio src="../Audio/00002-00001.mp3" clipBegin="0s" clipEnd="5s"/><audio src="../Audio/00001-00001.mp3" clipBegin="2402s" clipEnd="2405s"/></par>
<audio src="../Audio/00001-00001.mp3" clipBegin="2402s" clipEnd="2405s"/>
<par id="e"><text src="../t.xhtml#s4"/><audio src="../Audio/00001-00001.mp3" clipEnd="2500s"/></par>
</seq></body></smil>`
	ends := map[string]int64{"OEBPS/Audio/00001-00001.mp3": 2_399_488}
	got, count := mendSMIL([]byte(smil), "OEBPS/smil/c.smil", ends)
	want := strings.NewReplacer(
		// Runs over the end: it ends there.
		`clipEnd = '2402.160s'`, `clipEnd = "2399.488s"`,
		// Begins after the end and ends before it begins: no length.
		`s:clipEnd="2399.488s"`, `s:clipEnd="2402.160s"`,
		// Runs over the end from the start of the file, its begin left out.
		`clipEnd="2500s"`, `clipEnd="2399.488s"`,
	).Replace(smil)
	if count != 3 || string(got) != want {
		t.Fatalf("%d changed:\n%s\nwant\n%s", count, got, want)
	}
	// A file with no end, or one with nothing past it, is the same bytes.
	if same, count := mendSMIL([]byte(smil), "OEBPS/smil/c.smil", map[string]int64{"OEBPS/Audio/00009-00001.mp3": 1}); count != 0 || !bytes.Equal(same, []byte(smil)) {
		t.Fatalf("a document with nothing to change changed (%d)", count)
	}
}

// The reading copy of an edition that the aligner ran past the end of is one both
// apps read: no sentence ends before it begins, none runs over its audio, and it
// reads as the hub reads the original.
func TestReadingCopyMendsTheNarrationOfAnEditionTheAlignerRanPast(t *testing.T) {
	options := AlignedEPUBOptions{
		PackageDir: "OEBPS", AudioBytes: 1024,
		Narrations: []FixtureNarration{
			{ChunkMs: []int64{100_000}, Sentences: []int{40}, OverrunMs: []int64{5_439}},
			{ChunkMs: []int64{7_200_000, 3_300_000}, Sentences: []int{60, 40}, OverrunMs: []int64{0, 700}},
		},
	}
	fixture := generateAligned(t, options)
	source, size := openEPUB(t, fixture.Path)
	var copied bytes.Buffer
	report, err := WriteReadingEPUB(&copied, source, size, CopyOptions{MendNarration: true})
	if err != nil {
		t.Fatal(err)
	}
	if report.Mended != 4 || report.Edited != 2 {
		t.Fatalf("mended %d sentences in %d entries, want 4 in 2", report.Mended, report.Edited)
	}
	original, err := ReadAlignment(source, size)
	if err != nil {
		t.Fatal(err)
	}
	mended, err := ReadAlignment(bytes.NewReader(copied.Bytes()), int64(copied.Len()))
	if err != nil {
		t.Fatal(err)
	}
	if mended.PastEnd != 0 || mended.CutAtEnd != 0 || len(mended.Files) != len(original.Files) {
		t.Fatalf("the copy still runs past its audio: %d past, %d cut", mended.PastEnd, mended.CutAtEnd)
	}
	for i := range original.Files {
		if mended.Files[i].Entry != original.Files[i].Entry || mended.Files[i].LengthMs != original.Files[i].LengthMs ||
			len(mended.Files[i].Pars) != len(original.Files[i].Pars) {
			t.Fatalf("file %d reads otherwise in the copy", i)
		}
		for k, par := range original.Files[i].Pars {
			if mended.Files[i].Pars[k].BeginMs != par.BeginMs || mended.Files[i].Pars[k].EndMs != par.EndMs || mended.Files[i].Pars[k].Fragment != par.Fragment {
				t.Fatalf("%s sentence %d = %+v, want %+v", original.Files[i].Entry, k, mended.Files[i].Pars[k], par)
			}
		}
	}
	// What the apps refuse is not in it: every clip has a length of zero or more.
	archive, err := zip.NewReader(bytes.NewReader(copied.Bytes()), int64(copied.Len()))
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range archive.File {
		if !strings.HasSuffix(entry.Name, ".smil") {
			continue
		}
		data, _ := readXMLEntry(map[string]*zip.File{entry.Name: entry}, entry.Name)
		if err := walkXML(data, func(start xml.StartElement, _ int) {
			if start.Name.Local != "audio" {
				return
			}
			begin, _ := parseClock(attribute(start, "clipBegin"))
			end, _ := parseClock(attribute(start, "clipEnd"))
			if end < begin {
				t.Errorf("%s: a clip ends at %d before it begins at %d", entry.Name, end, begin)
			}
		}); err != nil {
			t.Fatal(err)
		}
	}
	// Without the option, the SMIL is the original's.
	var plain bytes.Buffer
	if report, err := WriteReadingEPUB(&plain, source, size, CopyOptions{}); err != nil || report.Mended != 0 || report.Edited != 0 {
		t.Fatalf("a copy not asked to mend: %+v, %v", report, err)
	}
}
