// Command hubctl is the operator's side of the hub: issue tokens, and find out
// why something is not working.
package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"flag"
	"fmt"
	"os"
	"strings"
	"time"

	"ayaneohub/internal/api"
	"ayaneohub/internal/config"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "token":
		tokenCmd(os.Args[2:])
	case "doctor":
		doctorCmd(os.Args[2:])
	case "probe":
		probeCmd(os.Args[2:])
	case "help", "-h", "--help":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", os.Args[1])
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `hubctl -- operate the Ayaneo hub

  hubctl token new --label pocketds     issue a token (printed once)
  hubctl doctor [--config path]         check every configured service
  hubctl probe <service> <path>         fetch one raw upstream path

`)
}

func tokenCmd(args []string) {
	if len(args) == 0 || args[0] != "new" {
		fmt.Fprintln(os.Stderr, "usage: hubctl token new --label <name>")
		os.Exit(2)
	}
	fs := flag.NewFlagSet("token new", flag.ExitOnError)
	label := fs.String("label", "", "which device this token is for")
	_ = fs.Parse(args[1:])

	if *label == "" {
		fmt.Fprintln(os.Stderr, "a --label is required: it is how you revoke one device later")
		os.Exit(2)
	}

	// 32 bytes from the OS CSPRNG. base64url so it survives being typed into a
	// handheld and pasted into a YAML file without quoting.
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		fmt.Fprintf(os.Stderr, "could not generate a token: %v\n", err)
		os.Exit(1)
	}
	token := base64.RawURLEncoding.EncodeToString(raw)

	fmt.Printf(`
Token for %q -- copy it now, it is not stored and cannot be shown again:

  %s

Add this to hub.yaml (the hash, never the token):

  auth:
    tokens:
      - label: %q
        sha256: %q
        scopes: ["read", "request", "control", "play"]

And on the handheld, scripts/dev.env:

  HUB_TOKEN=%s

`, *label, token, *label, config.HashToken(token), token)
}

func doctorCmd(args []string) {
	fs := flag.NewFlagSet("doctor", flag.ExitOnError)
	path := fs.String("config", "hub.yaml", "path to hub.yaml")
	_ = fs.Parse(args)

	cfg, err := config.Load(*path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "\n%v\n\nconfig file: %s\n", err, *path)
		os.Exit(config.ExitConfig)
	}

	fmt.Printf("hub config : %s\n", *path)
	fmt.Printf("listen     : %s\n", cfg.Server.Listen)
	fmt.Printf("tokens     : %d\n", len(cfg.Auth.Tokens))
	fmt.Println()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	results := api.NewProber(cfg).ProbeAll(ctx)
	if len(results) == 0 {
		fmt.Println("no services configured yet")
		return
	}

	fmt.Printf("%-13s %-14s %8s  %s\n", "SERVICE", "STATE", "LATENCY", "DETAIL")
	fmt.Println(strings.Repeat("-", 76))
	problems := 0
	for _, r := range results {
		detail := r.Version
		if r.LastError != "" {
			detail = r.LastError
		}
		if len(r.Notes) > 0 {
			if detail != "" {
				detail += " | "
			}
			detail += strings.Join(r.Notes, "; ")
		}
		latency := "-"
		if r.LatencyMS > 0 {
			latency = fmt.Sprintf("%dms", r.LatencyMS)
		}
		fmt.Printf("%-13s %-14s %8s  %s\n", r.Name, r.State, latency, detail)
		if r.State == "down" || r.State == "misconfigured" {
			problems++
		}
	}

	fmt.Println()
	if problems == 0 {
		fmt.Println("all configured services answered.")
		return
	}
	fmt.Printf("%d service(s) need attention.\n", problems)
	fmt.Println("  down          the box is not answering -- is it running, is the port right?")
	fmt.Println("  misconfigured it answered and rejected the credential -- check the API key")
	os.Exit(1)
}
