package reading

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"strings"
	"testing"
)

func TestBuildSingleFileWebSeedTorrentIsDeterministic(t *testing.T) {
	payload := bytes.Repeat([]byte("Pocket DS acquisition fixture\n"), 4000)
	first, firstHash, err := BuildSingleFileWebSeedTorrent("M3 Fixture Book v01.epub", payload, "http://host.docker.internal:18081/M3%20Fixture%20Book%20v01.epub", 32*1024)
	if err != nil {
		t.Fatal(err)
	}
	second, secondHash, err := BuildSingleFileWebSeedTorrent("M3 Fixture Book v01.epub", payload, "http://host.docker.internal:18081/M3%20Fixture%20Book%20v01.epub", 32*1024)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first, second) || firstHash != secondHash {
		t.Fatal("torrent fixture is not deterministic")
	}
	if len(firstHash) != 40 {
		t.Fatalf("info hash = %q", firstHash)
	}
	if _, err := hex.DecodeString(firstHash); err != nil {
		t.Fatalf("info hash is not hex: %v", err)
	}
	for offset := 0; offset < len(payload); offset += 32 * 1024 {
		end := offset + 32*1024
		if end > len(payload) {
			end = len(payload)
		}
		piece := sha1.Sum(payload[offset:end])
		if !bytes.Contains(first, piece[:]) {
			t.Fatalf("torrent omitted piece digest at offset %d", offset)
		}
	}
	for _, marker := range []string{"8:url-list", "4:infod", "6:lengthi", "4:name", "12:piece lengthi", "6:pieces"} {
		if !bytes.Contains(first, []byte(marker)) {
			t.Fatalf("torrent omitted %q", marker)
		}
	}
}

func TestBuildSingleFileWebSeedTorrentRejectsUnsafeInput(t *testing.T) {
	tests := []struct {
		name    string
		file    string
		url     string
		payload []byte
		piece   int
		want    string
	}{
		{name: "path", file: "../book.epub", url: "https://example.invalid/book.epub", payload: []byte("book"), piece: 16384, want: "file name"},
		{name: "scheme", file: "book.epub", url: "file:///tmp/book.epub", payload: []byte("book"), piece: 16384, want: "HTTP"},
		{name: "empty payload", file: "book.epub", url: "https://example.invalid/book.epub", piece: 16384, want: "payload"},
		{name: "piece length", file: "book.epub", url: "https://example.invalid/book.epub", payload: []byte("book"), piece: 1000, want: "piece length"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, _, err := BuildSingleFileWebSeedTorrent(test.file, test.payload, test.url, test.piece)
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error = %v, want %q", err, test.want)
			}
		})
	}
}
