package api

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log/slog"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"ayaneohub/internal/config"
	readingdomain "ayaneohub/internal/reading"
)

// The ebook as the reading copy (#41): the same book with its font sizes made
// relative and two columns within reach of a narrow screen, built from the file on
// this PC, or Storyteller's own file when that cannot be.

const ebookStorytellerPath = "/library/Books/Fixture Odyssey/Fixture Odyssey.epub"

type ebookOptions struct {
	// edit changes the generated file before the hub sees it (to break it).
	edit func(path string)
	// json overrides the "ebook" Storyteller reports.
	json string
	// roots overrides the media mapping.
	roots func(root string) []config.MediaRemovalRoot
	// audioBytes is the size of each stored audio entry (300 KB by default).
	audioBytes int
}

// newEbookEnv is a book whose ebook is an EPUB on this PC: two narrated chapters
// with a stylesheet of keyword sizes in the first, and 300 KB of stored filler per
// audio file, which is more than a plan keeps in memory and so is read from the
// file when it is sent.
func newEbookEnv(t *testing.T, options ebookOptions) *audioEnv {
	t.Helper()
	if options.audioBytes == 0 {
		options.audioBytes = 300 << 10
	}
	env := newAudioEnv(t, audioEnvOptions{roots: options.roots}, func(root string) audioBuild {
		path := filepath.Join(root, "Books", "Fixture Odyssey", "Fixture Odyssey.epub")
		fixture, err := readingdomain.GenerateAlignedEPUB(path, readingdomain.AlignedEPUBOptions{
			PackageDir: "OEBPS", AudioBytes: options.audioBytes,
			Narrations: []readingdomain.FixtureNarration{
				{ChunkMs: []int64{100_000}, Sentences: []int{8}},
				{ChunkMs: []int64{50_000}, Sentences: []int{5}},
			},
		})
		if err != nil {
			t.Fatal(err)
		}
		rewriteAlignedEPUBEntry(t, path, "OEBPS/text/part0001.xhtml", func(content string) string {
			content = strings.Replace(content, "<title>Part 1</title></head>", `<title>Part 1</title><style type="text/css">p { font-size: medium } .x { font-size: 12px; line-height: 18px } h1 { font-size: 1.5em; line-height: 1.4 }</style></head>`, 1)
			return strings.Replace(content, "<p>", `<p style="font-size:small">`, 1)
		})
		if options.edit != nil {
			options.edit(path)
		}
		ebook := options.json
		if ebook == "" {
			ebook = `{"uuid":"ebook-12","filepath":"` + ebookStorytellerPath + `","pageCount":400}`
		}
		return audioBuild{epub: fixture, ebook: ebook}
	})
	return env
}

func (e *audioEnv) ebookAt(method, query string, headers map[string]string) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.trackAt(method, "/v1/reading/works/"+e.child+"/publications/12/file"+query, headers)
}

func (e *audioEnv) ebook(headers map[string]string) *httptest.ResponseRecorder {
	e.t.Helper()
	return e.ebookAt(http.MethodGet, "", headers)
}

func TestEbookIsServedAsTheReadingCopyOfTheFileOnThisPC(t *testing.T) {
	env := newEbookEnv(t, ebookOptions{})
	originalBytes, err := os.ReadFile(env.build.epub.Path)
	if err != nil {
		t.Fatal(err)
	}
	originalNames, original := zipNames(t, originalBytes)

	var first []byte
	for _, query := range []string{"", "?format=ebook"} {
		response := env.ebookAt(http.MethodGet, query, nil)
		if response.Code != http.StatusOK {
			t.Fatalf("ebook%s = %d: %s", query, response.Code, response.Body.String())
		}
		body := response.Body.Bytes()
		if first == nil {
			first = body
		} else if !bytes.Equal(first, body) {
			t.Fatal("the two spellings of the ebook route differ")
		}
		names, copied := zipNames(t, body)
		// The same entries in the same order, audio filler and all: this is the ebook
		// route, and nothing here is left out.
		if strings.Join(names, "\n") != strings.Join(originalNames, "\n") {
			t.Fatalf("entries\n%q\nwant\n%q", names, originalNames)
		}
		if copied.File[0].Name != "mimetype" || copied.File[0].Method != zip.Store || string(zipEntry(t, copied, "mimetype")) != "application/epub+zip" {
			t.Fatalf("the archive does not open on a stored mimetype: %+v", copied.File[0].FileHeader)
		}
		for _, name := range names {
			got, was := zipEntry(t, copied, name), zipEntry(t, original, name)
			if strings.HasSuffix(name, ".xhtml") {
				if !columnStyleElement.Match(got) {
					t.Errorf("%s has no column style", name)
				}
				got = columnStyleElement.ReplaceAll(got, nil)
			}
			if name == "OEBPS/text/part0001.xhtml" {
				// The keyword, the pixels, the line height and the inline size; ems, the element and its
				// ids as they were.
				for _, want := range []string{`p { font-size: 1rem }`, `.x { font-size: .75rem; line-height: 1.125rem }`, `h1 { font-size: 1.5em; line-height: 1.4 }`, `<p style="font-size:.8125rem">`, `id="id1-s0"`} {
					if !strings.Contains(string(got), want) {
						t.Errorf("%s lacks %s:\n%s", name, want, got)
					}
				}
				continue
			}
			if !bytes.Equal(got, was) {
				t.Errorf("%s changed", name)
			}
		}

		sum := sha256.Sum256(body)
		header := response.Header()
		if header.Get("Content-Type") != "application/epub+zip" || header.Get("Content-Length") != itoa(len(body)) || header.Get("Accept-Ranges") != "bytes" {
			t.Errorf("type %q length %q ranges %q", header.Get("Content-Type"), header.Get("Content-Length"), header.Get("Accept-Ranges"))
		}
		if !regexp.MustCompile(`^"[0-9a-f]{32}"$`).MatchString(header.Get("ETag")) || header.Get("Last-Modified") == "" {
			t.Errorf("etag %q, last modified %q", header.Get("ETag"), header.Get("Last-Modified"))
		}
		if header.Get("X-Reading-Content-Hash") != "sha256:"+hex.EncodeToString(sum[:]) {
			t.Errorf("hash %q", header.Get("X-Reading-Content-Hash"))
		}
		if header.Get("ETag") != `"`+hex.EncodeToString(sum[:])[:32]+`"` {
			t.Errorf("the etag %q is not of the bytes sent", header.Get("ETag"))
		}
		if header.Get("Cache-Control") != "private, no-store" || header.Get("X-Content-Type-Options") != "nosniff" {
			t.Errorf("cache %q nosniff %q", header.Get("Cache-Control"), header.Get("X-Content-Type-Options"))
		}
		var everything strings.Builder
		for name, values := range header {
			everything.WriteString(name + ": " + strings.Join(values, ",") + "\n")
		}
		for _, bad := range []string{env.root, filepath.Base(filepath.Dir(env.root)), "Fixture Odyssey", ".epub", "/library"} {
			if strings.Contains(everything.String(), bad) {
				t.Errorf("a header holds %q:\n%s", bad, everything.String())
			}
		}
	}
	if env.state.fileCalls != 0 {
		t.Fatalf("Storyteller's own file route was called %d times", env.state.fileCalls)
	}
}

func TestEbookCopyAnswersRangesAndConditionsLikeAFile(t *testing.T) {
	env := newEbookEnv(t, ebookOptions{})
	whole := env.ebook(nil)
	body, etag := whole.Body.Bytes(), whole.Header().Get("ETag")
	if len(body) < 600<<10 {
		t.Fatalf("the copy is %d bytes: the audio filler that is read from the file is not in it", len(body))
	}

	// Resumed in three pieces it is the file it began as, across the entries that
	// are in memory and the ones read from the file.
	cut := len(body) / 3
	pieces := [][]byte{}
	for _, window := range []string{"bytes=0-" + itoa(cut-1), "bytes=" + itoa(cut) + "-" + itoa(2*cut-1), "bytes=" + itoa(2*cut) + "-"} {
		piece := env.ebook(map[string]string{"Range": window})
		if piece.Code != http.StatusPartialContent || piece.Header().Get("ETag") != etag {
			t.Fatalf("%s = %d (etag %q)", window, piece.Code, piece.Header().Get("ETag"))
		}
		pieces = append(pieces, piece.Body.Bytes())
	}
	if !bytes.Equal(bytes.Join(pieces, nil), body) {
		t.Fatal("the pieces are not the whole")
	}
	random := rand.New(rand.NewSource(5))
	for i := 0; i < 40; i++ {
		from := random.Intn(len(body) - 1)
		to := from + random.Intn(len(body)-from)
		got := env.ebook(map[string]string{"Range": "bytes=" + itoa(from) + "-" + itoa(to)})
		if got.Code != http.StatusPartialContent || !bytes.Equal(got.Body.Bytes(), body[from:to+1]) {
			t.Fatalf("bytes %d-%d = %d, %d bytes", from, to, got.Code, got.Body.Len())
		}
	}
	// The end of an archive is where its directory is.
	if tail := env.ebook(map[string]string{"Range": "bytes=-22"}); tail.Code != http.StatusPartialContent || !bytes.HasPrefix(tail.Body.Bytes(), []byte("PK\x05\x06")) {
		t.Fatalf("the last 22 bytes = %d %q", tail.Code, tail.Body.Bytes())
	}
	if got := env.ebook(map[string]string{"Range": "bytes=99999999-"}); got.Code != http.StatusRequestedRangeNotSatisfiable {
		t.Fatalf("past the end = %d", got.Code)
	}
	if got := env.ebook(map[string]string{"Range": "bytes=0-1,5-6"}); got.Code != http.StatusBadRequest {
		t.Fatalf("two ranges = %d", got.Code)
	}
	if got := env.ebook(map[string]string{"Range": "bytes=0-9", "If-Range": `"edition-12"`}); got.Code != http.StatusOK || !bytes.Equal(got.Body.Bytes(), body) {
		t.Fatalf("an If-Range that is Storyteller's tag, not this copy's = %d", got.Code)
	}
	if got := env.ebook(map[string]string{"Range": "bytes=0-9", "If-Range": etag}); got.Code != http.StatusPartialContent {
		t.Fatalf("a current If-Range = %d", got.Code)
	}
	if got := env.ebook(map[string]string{"If-None-Match": etag}); got.Code != http.StatusNotModified || got.Body.Len() != 0 {
		t.Fatalf("not modified = %d with %d bytes", got.Code, got.Body.Len())
	}
	head := env.ebookAt(http.MethodHead, "", nil)
	if head.Code != http.StatusOK || head.Body.Len() != 0 || head.Header().Get("Content-Length") != itoa(len(body)) || head.Header().Get("ETag") != etag {
		t.Fatalf("HEAD = %d, %d bytes, length %q", head.Code, head.Body.Len(), head.Header().Get("Content-Length"))
	}
	if env.state.fileCalls != 0 {
		t.Fatal("a request reached Storyteller")
	}
}

// What it costs: one pass over the file for as long as it is the same, and then the
// pages of the book that are kept in memory are served without touching the disk.
func TestEbookCopyIsPlannedOnceAndOnlyTheLargeEntriesAreReadAgain(t *testing.T) {
	env := newEbookEnv(t, ebookOptions{})
	watch := env.watchEPUB()
	info, err := os.Stat(env.build.epub.Path)
	if err != nil {
		t.Fatal(err)
	}
	first := env.ebook(nil)
	if first.Code != http.StatusOK {
		t.Fatalf("ebook = %d", first.Code)
	}
	planned := watch.bytes.Load()
	if planned < info.Size() || planned > 2*info.Size() {
		t.Fatalf("planning read %d bytes of a %d byte file (and sending it read the rest)", planned, info.Size())
	}

	// The head of the archive is in memory.
	for i := 0; i < 3; i++ {
		if got := env.ebook(map[string]string{"Range": "bytes=0-99"}); got.Code != http.StatusPartialContent {
			t.Fatalf("range = %d", got.Code)
		}
	}
	if got := watch.bytes.Load(); got != planned {
		t.Fatalf("three small ranges read %d more bytes: the copy was planned again, or its head is read from the file", got-planned)
	}
	// A window inside an audio entry is read from the file, and only that window.
	body := first.Body.Bytes()
	middle := bytes.Index(body, []byte("OEBPS/Audio/00001-00001.mp3")) + 400<<10
	if got := env.ebook(map[string]string{"Range": "bytes=" + itoa(middle) + "-" + itoa(middle+999)}); got.Code != http.StatusPartialContent || got.Body.Len() != 1000 {
		t.Fatalf("a window in the audio = %d, %d bytes", got.Code, got.Body.Len())
	}
	if read := watch.bytes.Load() - planned; read < 1000 || read > 64<<10 {
		t.Fatalf("1000 bytes of audio cost %d bytes of reading", read)
	}
	if watch.opened.Load() != watch.closed.Load() || watch.opened.Load() != 5 {
		t.Fatalf("opened %d and closed %d for five requests", watch.opened.Load(), watch.closed.Load())
	}

	// A new file under the same name is a new copy.
	rewriteAlignedEPUBEntry(t, env.build.epub.Path, "OEBPS/text/part0002.xhtml", func(content string) string {
		return strings.Replace(content, "Part 2", "Part 2, second printing", 1)
	})
	second := env.ebook(nil)
	if second.Code != http.StatusOK || second.Header().Get("ETag") == first.Header().Get("ETag") || bytes.Equal(second.Body.Bytes(), first.Body.Bytes()) {
		t.Fatalf("a changed file was served as it was: %d %q", second.Code, second.Header().Get("ETag"))
	}
	if !bytes.Contains(zipEntry(t, mustZip(t, second.Body.Bytes()), "OEBPS/text/part0002.xhtml"), []byte("second printing")) {
		t.Fatal("the new text is not in the copy")
	}
}

// When the file cannot be made into a copy, the book is Storyteller's file, exactly
// as it was before there was a copy, and the log says why in words that name no path.
func TestEbookFallsBackToStorytellersFileWhenThereIsNoCopyToServe(t *testing.T) {
	large := func(t *testing.T) {
		previous := maxEPUBCopyBytes
		t.Cleanup(func() { maxEPUBCopyBytes = previous })
		maxEPUBCopyBytes = 1000
	}
	for _, test := range []struct {
		name    string
		options ebookOptions
		prepare func(t *testing.T)
		reason  string
	}{
		{"the file is not on the disk", ebookOptions{edit: func(path string) { os.Remove(path) }}, nil, "missing_file"},
		{"its folder is mapped nowhere", ebookOptions{json: `{"uuid":"ebook-12","filepath":"/data/assets/Book/Book.epub"}`}, nil, "unmapped_root"},
		{"no folder is mapped at all", ebookOptions{roots: func(string) []config.MediaRemovalRoot { return nil }}, nil, "unmapped_root"},
		{"Storyteller reports no path", ebookOptions{json: `{"uuid":"ebook-12","pageCount":400}`}, nil, "no_path"},
		{"its path climbs out of its folder", ebookOptions{json: `{"uuid":"ebook-12","filepath":"/library/../../etc/Book.epub"}`}, nil, "unmapped_root"},
		{"it is not an EPUB by name", ebookOptions{json: `{"uuid":"ebook-12","filepath":"/library/Books/Fixture Odyssey/Fixture Odyssey.pdf"}`}, nil, "unreadable"},
		{"it is not an archive", ebookOptions{edit: func(path string) { os.WriteFile(path, []byte("not a zip archive"), 0o644) }}, nil, "not_an_epub"},
		{"the copy would be more than the hub holds", ebookOptions{}, large, "too_large"},
	} {
		t.Run(test.name, func(t *testing.T) {
			if test.prepare != nil {
				test.prepare(t)
			}
			var logged bytes.Buffer
			previous := slog.Default()
			slog.SetDefault(slog.New(slog.NewTextHandler(&logged, &slog.HandlerOptions{Level: slog.LevelDebug})))
			t.Cleanup(func() { slog.SetDefault(previous) })

			env := newEbookEnv(t, test.options)
			env.state.noFiles = false
			watch := env.watchEPUB()
			response := env.ebook(nil)
			// Storyteller's stand-in answers a 206 of four bytes with its own headers.
			if response.Code != http.StatusPartialContent || response.Body.String() != "4567" ||
				response.Header().Get("ETag") != `"edition-12"` || response.Header().Get("X-Reading-Content-Hash") != "sha256:content-hash" {
				t.Fatalf("pass-through = %d %q (etag %q)", response.Code, response.Body.String(), response.Header().Get("ETag"))
			}
			if env.state.fileCalls != 1 || env.state.formatSeen != "ebook" {
				t.Fatalf("Storyteller was asked %d times for %q", env.state.fileCalls, env.state.formatSeen)
			}
			if watch.opened.Load() != watch.closed.Load() {
				t.Fatalf("opened %d and closed %d", watch.opened.Load(), watch.closed.Load())
			}
			log := logged.String()
			if !strings.Contains(log, "reason="+test.reason) || !strings.Contains(log, "level=WARN") {
				t.Fatalf("the log does not warn with %q:\n%s", test.reason, log)
			}
			for _, bad := range []string{env.root, "Fixture Odyssey", ".epub", "/library", "etc", "zip archive"} {
				if strings.Contains(strings.ToLower(strings.ReplaceAll(log, "ebook reading copy unavailable, passing Storyteller's file through", "")), strings.ToLower(bad)) {
					t.Errorf("the log holds %q:\n%s", bad, log)
				}
			}
		})
	}
}

// A book of illustrations is a hundred megabytes and the reader may be a phone on a
// slow link. Over a real connection, with the server's write timeout far shorter than
// the transfer, as the track tests do (reading_audio_track_test.go).
func stallingEbookEnv(t *testing.T, window time.Duration) (env *audioEnv, url string, watch epubWatch) {
	t.Helper()
	env = newEbookEnv(t, ebookOptions{audioBytes: 4 << 20}) // 8 MiB of audio entries, read from the file
	env.server.audioStall = stallPolicy{Window: window, Step: 64 << 10}
	watch = env.watchEPUB()
	server := httptest.NewUnstartedServer(env.handler)
	server.Config.WriteTimeout = stallingServerWriteTimeout
	server.Config.ConnState = smallBuffers
	server.Start()
	t.Cleanup(server.Close)
	return env, server.URL + "/v1/reading/works/" + env.child + "/publications/12/file", watch
}

func TestEbookCopyToASlowButMovingReaderOutlivesTheServersWriteTimeout(t *testing.T) {
	_, url, watch := stallingEbookEnv(t, patient)
	response := getTrack(t, url)
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("status = %d", response.StatusCode)
	}
	hash := sha256.New()
	buffer := make([]byte, 256<<10)
	total, reads := int64(0), 0
	for {
		n, err := response.Body.Read(buffer)
		hash.Write(buffer[:n])
		total += int64(n)
		reads++
		if err != nil {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if total < 8<<20 || response.ContentLength != total {
		t.Fatalf("read %d bytes of %d", total, response.ContentLength)
	}
	if "sha256:"+hex.EncodeToString(hash.Sum(nil)) != response.Header.Get("X-Reading-Content-Hash") {
		t.Fatal("the bytes received are not the bytes the hash names")
	}
	if pacing := time.Duration(reads-1) * 5 * time.Millisecond; pacing <= stallingServerWriteTimeout {
		t.Fatalf("%d reads is too few to outlive the server's %v write timeout", reads, stallingServerWriteTimeout)
	}
	eventually(t, "the file to be let go", func() bool { return watch.opened.Load() == watch.closed.Load() })
}

func TestEbookCopyToAReaderWhoStoppedReadingIsLetGoWithItsFile(t *testing.T) {
	_, url, watch := stallingEbookEnv(t, 300*time.Millisecond)
	response := getTrack(t, url)
	defer response.Body.Close()
	if _, err := io.ReadFull(response.Body, make([]byte, 64<<10)); err != nil {
		t.Fatal(err)
	}
	// Nothing more is read, and the connection stays open and quiet.
	eventually(t, "the file to be let go", func() bool { return watch.opened.Load() == watch.closed.Load() && watch.opened.Load() > 0 })
}

// A request for the full read-along edition or the audiobook is not a request for the
// ebook, and Storyteller still answers it, as it did.
func TestTheOtherFormatsAreStillStorytellers(t *testing.T) {
	env := newEbookEnv(t, ebookOptions{})
	env.state.noFiles = false
	env.state.readaloud = true
	env.state.audiobook = true
	// The default book record has the ebook and these two only by name.
	for _, format := range []string{"readaloud", "audiobook"} {
		before := env.state.fileCalls
		response := env.ebookAt(http.MethodGet, "?format="+format, nil)
		if response.Code != http.StatusPartialContent || response.Body.String() != "4567" || env.state.formatSeen != format || env.state.fileCalls != before+1 {
			t.Fatalf("%s = %d %q (format seen %q)", format, response.Code, response.Body.String(), env.state.formatSeen)
		}
	}
}
