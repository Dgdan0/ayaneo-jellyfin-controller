package reading

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
)

// BuildSingleFileWebSeedTorrent creates a deterministic BitTorrent v1 fixture
// that retrieves one generated file from an HTTP web seed. It is used only by
// the isolated acquisition lab, avoiding trackers and third-party content.
func BuildSingleFileWebSeedTorrent(fileName string, payload []byte, webSeedURL string, pieceLength int) ([]byte, string, error) {
	fileName = strings.TrimSpace(fileName)
	if fileName == "" || fileName == "." || filepath.Base(fileName) != fileName || strings.ContainsAny(fileName, `/\`) {
		return nil, "", fmt.Errorf("torrent file name must be a single safe path component")
	}
	if len(payload) == 0 {
		return nil, "", fmt.Errorf("torrent payload is empty")
	}
	if pieceLength < 16*1024 || pieceLength > 4*1024*1024 || pieceLength&(pieceLength-1) != 0 {
		return nil, "", fmt.Errorf("torrent piece length must be a power of two between 16 KiB and 4 MiB")
	}
	parsedURL, err := url.Parse(strings.TrimSpace(webSeedURL))
	if err != nil || parsedURL.Host == "" || parsedURL.User != nil || parsedURL.Scheme != "http" && parsedURL.Scheme != "https" {
		return nil, "", fmt.Errorf("torrent web seed must be an HTTP or HTTPS URL without credentials")
	}

	pieces := make([]byte, 0, ((len(payload)+pieceLength-1)/pieceLength)*sha1.Size)
	for offset := 0; offset < len(payload); offset += pieceLength {
		end := offset + pieceLength
		if end > len(payload) {
			end = len(payload)
		}
		digest := sha1.Sum(payload[offset:end])
		pieces = append(pieces, digest[:]...)
	}

	var info bytes.Buffer
	info.WriteByte('d')
	writeBencodedString(&info, []byte("length"))
	writeBencodedInteger(&info, int64(len(payload)))
	writeBencodedString(&info, []byte("name"))
	writeBencodedString(&info, []byte(fileName))
	writeBencodedString(&info, []byte("piece length"))
	writeBencodedInteger(&info, int64(pieceLength))
	writeBencodedString(&info, []byte("pieces"))
	writeBencodedString(&info, pieces)
	info.WriteByte('e')

	infoDigest := sha1.Sum(info.Bytes())
	var torrent bytes.Buffer
	torrent.WriteByte('d')
	writeBencodedString(&torrent, []byte("info"))
	torrent.Write(info.Bytes())
	writeBencodedString(&torrent, []byte("url-list"))
	writeBencodedString(&torrent, []byte(parsedURL.String()))
	torrent.WriteByte('e')
	return torrent.Bytes(), hex.EncodeToString(infoDigest[:]), nil
}

func writeBencodedString(destination *bytes.Buffer, value []byte) {
	destination.WriteString(strconv.Itoa(len(value)))
	destination.WriteByte(':')
	destination.Write(value)
}

func writeBencodedInteger(destination *bytes.Buffer, value int64) {
	destination.WriteByte('i')
	destination.WriteString(strconv.FormatInt(value, 10))
	destination.WriteByte('e')
}
