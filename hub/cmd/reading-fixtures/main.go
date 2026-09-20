package main

import (
	"flag"
	"fmt"
	"log"
	"path/filepath"

	"ayaneohub/internal/reading"
)

func main() {
	output := flag.String("out", "", "directory to receive the generated fixture set")
	flag.Parse()
	if *output == "" {
		log.Fatal("-out is required")
	}
	absolute, err := filepath.Abs(*output)
	if err != nil {
		log.Fatal(err)
	}
	manifest, err := reading.GenerateFixtureSet(absolute)
	if err != nil {
		log.Fatal(err)
	}
	fmt.Printf("generated %d reading fixtures in %s\n", len(manifest.Assets), absolute)
}
