package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"

	"ayaneohub/internal/config"
	"ayaneohub/internal/httpx"
)

// probeCmd fetches one raw upstream path through a service's configured
// credential and prints what came back.
//
// The point is to answer "what does this endpoint actually return on *this*
// install" without pasting an API key into a shell -- which would put it in
// the command history, and from there into a screen share or a bug report. The
// key is read from hub.secrets.yaml, used, and never printed: config.Secret
// only reveals itself through .Reveal(), which happens once, inside the auth
// header.
//
//	hubctl probe radarr /api/v3/qualityprofile
//	hubctl probe jellyseerr /api/v1/service/radarr/0
//	hubctl probe radarr /api/v3/release --query movieId=123 --timeout 120s
func probeCmd(args []string) {
	fs := flag.NewFlagSet("probe", flag.ExitOnError)
	path := fs.String("config", "hub.yaml", "path to hub.yaml")
	query := fs.String("query", "", "query string, e.g. movieId=123&foo=bar")
	timeout := fs.Duration("timeout", 30*time.Second, "how long to wait")
	keys := fs.String("keys", "", "comma-separated top-level fields to keep (default: all)")
	limit := fs.Int("limit", 0, "if the response is an array, print at most this many entries")

	if len(args) < 2 || strings.HasPrefix(args[0], "-") {
		fmt.Fprintln(os.Stderr, "usage: hubctl probe <service> <path> [--query k=v] [--limit n]")
		os.Exit(2)
	}
	service, upstreamPath := args[0], args[1]
	_ = fs.Parse(args[2:])

	cfg, err := config.Load(*path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "\n%v\n\nconfig file: %s\n", err, *path)
		os.Exit(config.ExitConfig)
	}
	svc, ok := cfg.Services[service]
	if !ok {
		fmt.Fprintf(os.Stderr, "%q is not configured; known: %s\n",
			service, strings.Join(config.KnownServices, ", "))
		os.Exit(2)
	}

	base, err := httpx.New(httpx.Options{
		Name:               service,
		BaseURL:            svc.BaseURL,
		Auth:               authFor(service, svc),
		Timeout:            *timeout,
		InsecureSkipVerify: svc.InsecureSkipVerify,
	})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	values, err := url.ParseQuery(*query)
	if err != nil {
		fmt.Fprintf(os.Stderr, "--query: %v\n", err)
		os.Exit(2)
	}

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	fmt.Fprintf(os.Stderr, "GET %s%s\n", base.BaseURL(), upstreamPath)

	started := time.Now()
	var out any
	if err := base.GetJSON(ctx, upstreamPath, values, &out); err != nil {
		// Show what actually came back. A service answering with HTML means the
		// request never reached its API at all, and the decode error on its own
		// does not say that.
		var text string
		if textErr := base.GetText(ctx, upstreamPath, &text); textErr == nil {
			fmt.Fprintf(os.Stderr, "%v\n\nbody was:\n%s\n", err, first(text, 300))
		} else {
			fmt.Fprintf(os.Stderr, "%v\n", err)
		}
		os.Exit(1)
	}
	fmt.Fprintf(os.Stderr, "%s %s -> %dms\n\n", service, upstreamPath,
		time.Since(started).Milliseconds())

	out = trim(out, *limit, splitKeys(*keys))
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetIndent("", "  ")
	_ = encoder.Encode(out)
}

// authFor mirrors what each adapter does, so a probe sees exactly what the hub
// sees. The header names are not interchangeable: Bazarr's is uppercase KEY,
// and Jellyfin's is not an api-key header at all.
func authFor(service string, svc config.ServiceConfig) httpx.Authenticator {
	key := svc.APIKey.Reveal()
	if key == "" {
		return httpx.NoAuth{}
	}
	switch service {
	case "bazarr":
		return httpx.HeaderAuth{Headers: map[string]string{"X-API-KEY": key}}
	case "jellyfin":
		return httpx.HeaderAuth{Headers: map[string]string{"X-Emby-Token": key}}
	default:
		return httpx.HeaderAuth{Headers: map[string]string{"X-Api-Key": key}}
	}
}

func first(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}

func splitKeys(raw string) []string {
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	for i := range parts {
		parts[i] = strings.TrimSpace(parts[i])
	}
	return parts
}

// trim keeps the output readable. Radarr's release list is megabytes and
// Jellyfin's /System/Info embeds every plugin changelog; dumping either in full
// is how you lose the thing you were looking for.
func trim(value any, limit int, keys []string) any {
	switch typed := value.(type) {
	case []any:
		if limit > 0 && len(typed) > limit {
			kept := make([]any, 0, limit+1)
			for _, item := range typed[:limit] {
				kept = append(kept, trim(item, 0, keys))
			}
			return append(kept, fmt.Sprintf("... %d more", len(typed)-limit))
		}
		for i := range typed {
			typed[i] = trim(typed[i], 0, keys)
		}
		return typed
	case map[string]any:
		if len(keys) == 0 {
			return typed
		}
		kept := map[string]any{}
		for _, key := range keys {
			if got, ok := typed[key]; ok {
				kept[key] = got
			}
		}
		return kept
	}
	return value
}
