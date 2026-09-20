package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"

	"ayaneohub/internal/reading"
)

func main() {
	payloadPath := flag.String("payload", "", "generated fixture file to distribute")
	outputPath := flag.String("out", "", "path for the generated .torrent file")
	webSeedURL := flag.String("url", "", "HTTP(S) URL serving the fixture")
	fileName := flag.String("name", "", "file name stored in the torrent (defaults to payload base name)")
	pieceLength := flag.Int("piece-length", 32*1024, "torrent piece length in bytes")
	flag.Parse()
	if *payloadPath == "" || *outputPath == "" || *webSeedURL == "" {
		log.Fatal("-payload, -out, and -url are required")
	}
	payload, err := os.ReadFile(*payloadPath)
	if err != nil {
		log.Fatal(err)
	}
	name := *fileName
	if name == "" {
		name = filepath.Base(*payloadPath)
	}
	torrent, infoHash, err := reading.BuildSingleFileWebSeedTorrent(name, payload, *webSeedURL, *pieceLength)
	if err != nil {
		log.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(*outputPath), 0o755); err != nil {
		log.Fatal(err)
	}
	if err := os.WriteFile(*outputPath, torrent, 0o644); err != nil {
		log.Fatal(err)
	}
	fmt.Printf("generated acquisition torrent %s with info hash %s\n", *outputPath, infoHash)
}
