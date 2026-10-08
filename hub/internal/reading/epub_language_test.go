package reading

import (
	"strings"
	"testing"
)

// The language of the book, from its package, on each document that names none.
// Without one WebKit and Chrome do not hyphenate, and the apps are turning
// hyphenation on by default; A Game of Thrones' documents carry no lang at all.

func TestValidLanguageIsAPlausibleTagAndNothingElse(t *testing.T) {
	for value, want := range map[string]string{
		"en": "en", "EN": "EN", "en-US": "en-US", "en-GB": "en-GB", "eng": "eng", "pt-BR": "pt-BR", "he": "he",
		"zh-Hant-TW": "zh-Hant-TW", "sr-Latn-RS": "sr-Latn-RS", "de-CH-1996": "de-CH-1996", "en-US-x-private": "en-US-x-private",
		" en-GB \n": "en-GB",
		"":          "", "  ": "", "english": "", "en_US": "", "e": "", "x-klingon": "", "i-klingon": "", "en-": "", "-en": "",
		"en--US": "", "en US": "", `en-US"`: "", "en-<": "", "en-&amp;": "", "123": "", "12": "", "en-abcdefghi": "", "en-ü": "",
		"und": "", "und-Latn": "", "zxx": "", "mul": "", "UND": "",
		"en-" + strings.Repeat("a", 33): "",
	} {
		if got := validLanguage(value); got != want {
			t.Errorf("validLanguage(%q) = %q, want %q", value, got, want)
		}
	}
}

func TestRestyleDocumentPutsTheLanguageOnTheRootElementOnly(t *testing.T) {
	const xhtml = `xmlns="http://www.w3.org/1999/xhtml"`
	for name, test := range map[string]struct{ doc, want string }{
		"a plain root":        {`<html><head><title>x</title></head><body/></html>`, `<html lang="en-GB" xml:lang="en-GB"><head><title>x</title></head><body/></html>`},
		"namespaces after":    {`<html ` + xhtml + ` xmlns:epub="http://www.idpf.org/2007/ops"><head/></html>`, `<html lang="en-GB" xml:lang="en-GB" ` + xhtml + ` xmlns:epub="http://www.idpf.org/2007/ops"><head/></html>`},
		"a root in capitals":  {`<HTML><HEAD></HEAD></HTML>`, `<HTML lang="en-GB" xml:lang="en-GB"><HEAD></HEAD></HTML>`},
		"attributes on lines": {"<html\n  " + xhtml + "\n  dir='ltr'>\n<head/></html>", "<html lang=\"en-GB\" xml:lang=\"en-GB\"\n  " + xhtml + "\n  dir='ltr'>\n<head/></html>"},
		"a self-closed root":  {`<html/>`, `<html lang="en-GB" xml:lang="en-GB"/>`},
		"after a declaration, a doctype, a comment and an instruction": {
			"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\"\n  \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n<!-- <html> -->\n<?pi <html> ?>\n<html " + xhtml + "><head/></html>",
			"<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\"\n  \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">\n<!-- <html> -->\n<?pi <html> ?>\n<html lang=\"en-GB\" xml:lang=\"en-GB\" " + xhtml + "><head/></html>"},
		"a byte-order mark": {"\xEF\xBB\xBF<html><head/></html>", "\xEF\xBB\xBF<html lang=\"en-GB\" xml:lang=\"en-GB\"><head/></html>"},
		// The publisher's word stands, whatever it is, whichever of the two it gave.
		"a lang":                {`<html lang="fr"><head/></html>`, `<html lang="fr"><head/></html>`},
		"an xml:lang":           {`<html xml:lang="fr"><head/></html>`, `<html xml:lang="fr"><head/></html>`},
		"both":                  {`<html lang="fr" xml:lang="fr"><head/></html>`, `<html lang="fr" xml:lang="fr"><head/></html>`},
		"an empty lang":         {`<html lang=""><head/></html>`, `<html lang=""><head/></html>`},
		"a lang in capitals":    {`<HTML LANG="fr"><HEAD/></HTML>`, `<HTML LANG="fr"><HEAD/></HTML>`},
		"a lang among others":   {`<html ` + xhtml + ` dir="ltr" lang='fr'><head/></html>`, `<html ` + xhtml + ` dir="ltr" lang='fr'><head/></html>`},
		"a language elsewhere":  {`<html><head/><body lang="fr"><p xml:lang="he">x</p></body></html>`, `<html lang="en-GB" xml:lang="en-GB"><head/><body lang="fr"><p xml:lang="he">x</p></body></html>`},
		"a data-lang attribute": {`<html data-lang="fr"><head/></html>`, `<html lang="en-GB" xml:lang="en-GB" data-lang="fr"><head/></html>`},
		// Not an HTML root: nothing to say a language of.
		"a prefixed root":         {`<h:html xmlns:h="http://www.w3.org/1999/xhtml"><h:head/></h:html>`, `<h:html xmlns:h="http://www.w3.org/1999/xhtml"><h:head/></h:html>`},
		"a root that is svg":      {`<svg xmlns="http://www.w3.org/2000/svg"><html/></svg>`, `<svg xmlns="http://www.w3.org/2000/svg"><html/></svg>`},
		"a document with no root": {`just text <html>`, `just text <html>`},
		"an unfinished root":      {`<html xmlns="http://www.w3.org/1999/xhtml"`, `<html xmlns="http://www.w3.org/1999/xhtml"`},
		"an html in a comment":    {`<!-- <html> --><div><html/></div>`, `<!-- <html> --><div><html/></div>`},
		"empty":                   {``, ``},
	} {
		t.Run(name, func(t *testing.T) {
			out, result := restyleDocument([]byte(test.doc), "en-GB")
			// The column style is a separate matter, tested elsewhere.
			got := strings.Replace(string(out), columnStyleElement, "", 1)
			if got != test.want {
				t.Fatalf("\n got %q\nwant %q", got, test.want)
			}
			if result.language != (test.want != test.doc) {
				t.Fatalf("language = %v for %q", result.language, test.doc)
			}
			if wellFormed([]byte(test.doc)) && !wellFormed(out) {
				t.Fatalf("a well-formed document is not any more: %s", out)
			}
			// A second pass finds the language there.
			again, second := restyleDocument(out, "en-GB")
			if second.language || string(again) != string(out) {
				t.Fatalf("a second pass changed it: %q", again)
			}
			// And without a language nothing is added.
			if plain, none := restyleDocument([]byte(test.doc), ""); none.language || strings.Contains(string(plain), "lang=") != strings.Contains(test.doc, "lang=") {
				t.Fatalf("a language came from nowhere: %q", plain)
			}
		})
	}
}

// A document in an encoding the pass will not edit is not given a language either.
func TestRestyleDocumentLeavesAnEncodingItCannotEditWithoutALanguage(t *testing.T) {
	doc := `<?xml version="1.0" encoding="ISO-8859-1"?><html><head/><body/></html>`
	out, result := restyleDocument([]byte(doc), "en")
	if string(out) != doc || result.language || result.left != leftEncoding {
		t.Fatalf("result = %+v: %q", result, out)
	}
}

// bookIn is a book whose package gives the languages as written and whose one
// chapter says nothing of its own.
func bookIn(t *testing.T, languages string, extraMeta string) string {
	t.Helper()
	return makeBook(t, []bookFile{
		{"META-INF/container.xml", bookContainer, false},
		{"OEBPS/content.opf", bookPackageIn(languages, extraMeta,
			`<item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>`,
			`<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>`), false},
		{"OEBPS/ch1.xhtml", `<html xmlns="http://www.w3.org/1999/xhtml"><head><title>One</title></head><body><p>x</p></body></html>`, false},
		{"OEBPS/nav.xhtml", `<html xmlns="http://www.w3.org/1999/xhtml" lang="fr"><head><title>Nav</title></head><body><nav/></body></html>`, false},
		{"OEBPS/stray.xhtml", `<html><head/><body/></html>`, false},
	})
}

func TestReadingCopyTakesTheLanguageFromThePackagesFirstDCLanguage(t *testing.T) {
	for name, test := range map[string]struct {
		languages string
		want      string // the language the chapters are given, or empty
	}{
		"one language":             {`<dc:language>en</dc:language>`, "en"},
		"a region":                 {`<dc:language>en-GB</dc:language>`, "en-GB"},
		"three letters as written": {`<dc:language>eng</dc:language>`, "eng"},
		"spaces around it":         {"<dc:language>\n  de-AT \n</dc:language>", "de-AT"},
		"the first of two":         {`<dc:language>fr</dc:language><dc:language>en</dc:language>`, "fr"},
		"a first that is no tag, then a good one": {`<dc:language>en_US</dc:language><dc:language>en</dc:language>`, ""},
		"undetermined":                       {`<dc:language>und</dc:language>`, ""},
		"empty":                              {`<dc:language></dc:language>`, ""},
		"self-closed":                        {`<dc:language/>`, ""},
		"a name":                             {`<dc:language>English</dc:language>`, ""},
		"none":                               {``, ""},
		"a language only in another element": {`<dc:subject>en</dc:subject>`, ""},
	} {
		t.Run(name, func(t *testing.T) {
			path := bookIn(t, test.languages, "")
			data, report := copyOf(t, path, CopyOptions{Restyle: true})
			_, copied := archiveOf(t, data)
			chapter, nav, stray := string(copied["OEBPS/ch1.xhtml"]), string(copied["OEBPS/nav.xhtml"]), string(copied["OEBPS/stray.xhtml"])
			attributes := ""
			if test.want != "" {
				attributes = ` lang="` + test.want + `" xml:lang="` + test.want + `"`
			}
			if !strings.HasPrefix(chapter, `<html`+attributes+` xmlns=`) {
				t.Errorf("the chapter is %.120s, want a root with%s", chapter, attributes)
			}
			// The navigation document names French and is left so; the stray document
			// is not in the manifest and is taken by its extension.
			if !strings.HasPrefix(nav, `<html xmlns="http://www.w3.org/1999/xhtml" lang="fr">`) {
				t.Errorf("the navigation document is %.120s", nav)
			}
			if !strings.HasPrefix(stray, `<html`+attributes+`>`) {
				t.Errorf("the stray document is %.120s", stray)
			}
			want := 0
			if test.want != "" {
				want = 2
			}
			if report.Languages != want {
				t.Errorf("Languages = %d, want %d", report.Languages, want)
			}
			for _, document := range []string{chapter, nav, stray} {
				if !wellFormed([]byte(document)) {
					t.Errorf("not well-formed: %s", document)
				}
			}
		})
	}
}

// Nothing is added to a book that cannot be restyled, and nothing without Restyle.
func TestReadingCopyGivesNoLanguageWhenNothingIsRestyled(t *testing.T) {
	path := bookIn(t, `<dc:language>en</dc:language>`, `<meta property="rendition:layout">pre-paginated</meta>`)
	for _, options := range []CopyOptions{{}, {OmitAudio: true}, {Restyle: true}} {
		data, report := copyOf(t, path, options)
		_, copied := archiveOf(t, data)
		if strings.Contains(string(copied["OEBPS/ch1.xhtml"]), "lang=") || report.Languages != 0 {
			t.Errorf("%+v: %s (%d)", options, copied["OEBPS/ch1.xhtml"], report.Languages)
		}
	}
	reflowable := bookIn(t, `<dc:language>en</dc:language>`, "")
	if data, report := copyOf(t, reflowable, CopyOptions{}); report.Languages != 0 || strings.Contains(string(readFromZip(t, data, "OEBPS/ch1.xhtml")), "lang=") {
		t.Errorf("a copy without Restyle: %d", report.Languages)
	}
}

func readFromZip(t *testing.T, data []byte, name string) []byte {
	t.Helper()
	_, contents := archiveOf(t, data)
	return contents[name]
}
