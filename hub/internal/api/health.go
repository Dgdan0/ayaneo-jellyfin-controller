package api

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"sync"
	"time"

	"ayaneohub/internal/config"
)

// ServiceHealth is one backing service, as the hub currently sees it.
type ServiceHealth struct {
	Name         string    `json:"name"`
	State        string    `json:"state"` // up | down | misconfigured | disabled
	DashboardURL string    `json:"dashboardUrl,omitempty"`
	LatencyMS    int64     `json:"latencyMs,omitempty"`
	Version      string    `json:"version,omitempty"`
	Notes        []string  `json:"notes,omitempty"`
	LastError    string    `json:"lastError,omitempty"`
	CheckedAt    time.Time `json:"checkedAt"`
}

type HubHealth struct {
	Hub struct {
		Version       string `json:"version"`
		UptimeSeconds int64  `json:"uptimeSeconds"`
		TokenCount    int    `json:"tokenCount"`
	} `json:"hub"`
	Services []ServiceHealth `json:"services"`
}

// probeSpec is how to ask one service whether it is alive.
//
// Kept as data rather than as six near-identical functions, because the only
// things that actually differ are the path, where the credential goes, and
// which JSON field holds the version.
type probeSpec struct {
	path         string
	authHeader   string // "" means the key goes in a query parameter
	authQuery    string
	versionField string
	// unauthenticated liveness check tried first, if the service has one
	pingPath string
}

var probes = map[string]probeSpec{
	"jellyfin": {
		path: "/System/Info", authHeader: "X-Emby-Token", versionField: "Version",
	},
	"jellyseerr": {
		path: "/api/v1/status", authHeader: "X-Api-Key", versionField: "version",
	},
	"radarr": {
		path: "/api/v3/system/status", authHeader: "X-Api-Key", versionField: "version",
	},
	"sonarr": {
		path: "/api/v3/system/status", authHeader: "X-Api-Key", versionField: "version",
	},
	"bazarr": {
		// Verified against source: the blueprint is registered at /api with
		// namespaces added flat, and the header really is spelled X-API-KEY.
		// /api/system/ping needs no credential, which makes it a free liveness
		// probe that separates "unreachable" from "wrong key".
		// Bazarr wraps its status in a "data" object, unlike the *arr apps.
		path: "/api/system/status", authHeader: "X-API-KEY", versionField: "data.bazarr_version",
		pingPath: "/api/system/ping",
	},
	"qbittorrent": {
		// Newer builds accept a bearer key; older ones want a cookie session.
		// Either way this endpoint answers 403 rather than 404 when we are
		// unauthenticated, which is enough to tell "reachable" from "down".
		path: "/api/v2/app/version",
	},
}

// Prober checks every configured service concurrently.
type Prober struct {
	cfg      *config.Config
	client   *http.Client
	once     sync.Once
	insecure *http.Client
}

func NewProber(cfg *config.Config) *Prober {
	return &Prober{
		cfg: cfg,
		client: &http.Client{
			// Deliberately short: this endpoint is what someone hits when
			// something is already wrong, and it must answer quickly enough to
			// be useful rather than inheriting a slow service's timeout.
			Timeout: 6 * time.Second,
		},
	}
}

// insecureClient is used only for services validation has confirmed are on
// loopback or a private address.
func (p *Prober) insecureClient() *http.Client {
	p.once.Do(func() {
		p.insecure = &http.Client{
			Timeout: p.client.Timeout,
			Transport: &http.Transport{
				TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
			},
		}
	})
	return p.insecure
}

func (p *Prober) ProbeAll(ctx context.Context) []ServiceHealth {
	names := make([]string, 0, len(config.KnownServices))
	for _, name := range config.KnownServices {
		if _, configured := p.cfg.Services[name]; configured {
			names = append(names, name)
		}
	}

	results := make([]ServiceHealth, len(names))
	var wg sync.WaitGroup
	for i, name := range names {
		wg.Add(1)
		go func(i int, name string) {
			defer wg.Done()
			results[i] = p.probe(ctx, name)
		}(i, name)
	}
	wg.Wait()

	sort.Slice(results, func(a, b int) bool { return results[a].Name < results[b].Name })
	return results
}

func (p *Prober) probe(ctx context.Context, name string) ServiceHealth {
	health := ServiceHealth{Name: name, CheckedAt: time.Now().UTC()}
	svc := p.cfg.Services[name]
	health.DashboardURL = serviceDashboardURL(svc)

	if !svc.Enabled {
		health.State = "disabled"
		return health
	}
	spec, known := probes[name]
	if !known {
		health.State = "misconfigured"
		health.LastError = "no probe defined for this service"
		return health
	}

	base := strings.TrimRight(svc.BaseURL, "/")
	// Bazarr's base_url setting prefixes every route, so the configured
	// override wins over the default when it is set.
	if name == "bazarr" && svc.APIBasePath != "" {
		spec.path = strings.TrimRight(svc.APIBasePath, "/") + "/system/status"
		spec.pingPath = strings.TrimRight(svc.APIBasePath, "/") + "/system/ping"
	}

	// An unauthenticated ping first, where one exists: it separates "the box is
	// not answering" from "the box is fine and the key is wrong", which are very
	// different problems with very different fixes.
	reachable := true
	if spec.pingPath != "" {
		if _, _, err := p.get(ctx, base+spec.pingPath, "", "", svc); err != nil {
			reachable = false
		}
	}

	start := time.Now()
	status, body, err := p.get(ctx, base+spec.path, spec.authHeader, spec.authQuery, svc)
	health.LatencyMS = time.Since(start).Milliseconds()

	switch {
	case err != nil:
		health.State = "down"
		health.LastError = classify(err)
		if spec.pingPath != "" && reachable {
			health.Notes = append(health.Notes, "ping succeeded but status did not; check the API key")
		}
		return health

	case status == http.StatusUnauthorized || status == http.StatusForbidden:
		// Not "down": the service is answering, we are simply not welcome.
		// Retrying a bad key forever is how qBittorrent bans your own IP.
		health.State = "misconfigured"
		health.LastError = fmt.Sprintf("HTTP %d -- the credential was rejected", status)
		if name == "qbittorrent" {
			health.State = "up"
			health.LastError = ""
			health.Notes = append(health.Notes, "reachable; session auth not yet established")
		}
		return health

	case status >= 400:
		health.State = "down"
		health.LastError = fmt.Sprintf("HTTP %d", status)
		return health
	}

	health.State = "up"
	if spec.versionField != "" {
		health.Version = extractString(body, spec.versionField)
		if health.Version == "" {
			// Reachable and authenticated, but the payload was not what we
			// expected. Say so rather than showing a blank column, which reads
			// as "no version" instead of "we could not find it".
			health.Notes = append(health.Notes, "version not found in the response")
		}
	} else {
		health.Version = strings.TrimSpace(string(body))
	}
	return health
}

// serviceDashboardURL exposes only a browser address. Authentication material,
// query strings and fragments never belong in the health document. WebURL is
// already validated; BaseURL still gets parsed defensively because this helper
// is also used directly by unit tests.
func serviceDashboardURL(svc config.ServiceConfig) string {
	raw := strings.TrimSpace(svc.WebURL)
	if raw == "" {
		raw = strings.TrimSpace(svc.BaseURL)
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return ""
	}
	parsed.User = nil
	parsed.RawQuery = ""
	parsed.ForceQuery = false
	parsed.Fragment = ""
	return strings.TrimRight(parsed.String(), "/")
}

func (p *Prober) get(
	ctx context.Context, rawURL, authHeader, authQuery string, svc config.ServiceConfig,
) (int, []byte, error) {
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return 0, nil, err
	}
	if authQuery != "" && svc.APIKey.IsSet() {
		q := parsed.Query()
		q.Set(authQuery, svc.APIKey.Reveal())
		parsed.RawQuery = q.Encode()
	}

	ctx, cancel := context.WithTimeout(ctx, svc.Timeout.OrDefault(8*time.Second))
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, parsed.String(), nil)
	if err != nil {
		return 0, nil, err
	}
	if authHeader != "" && svc.APIKey.IsSet() {
		req.Header.Set(authHeader, svc.APIKey.Reveal())
	}
	req.Header.Set("Accept", "application/json")

	client := p.client
	if svc.InsecureSkipVerify {
		client = p.insecureClient()
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	// 1 MB, not 64 KB. Jellyfin's /System/Info embeds the full changelog of every
	// installed plugin, which runs to hundreds of kilobytes -- and a truncated
	// body is not a short answer, it is invalid JSON that parses to nothing and
	// blanks the version for no visible reason.
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, body, nil
}

// classify turns a transport error into something a person can act on, without
// leaking the URL -- which carries the API key when it went in a query.
func classify(err error) string {
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case strings.Contains(err.Error(), "no such host"):
		return "dns: no such host"
	case strings.Contains(err.Error(), "connection refused"),
		strings.Contains(err.Error(), "No connection could be made"):
		return "connection refused -- is the service running?"
	case strings.Contains(err.Error(), "certificate"):
		return "tls: certificate rejected"
	}
	return "unreachable"
}

// extractString walks a dotted path, because the services do not agree on
// whether to wrap their payload: the *arr apps put version at the top level,
// Bazarr nests it under "data".
func extractString(body []byte, path string) string {
	var current any
	if err := json.Unmarshal(body, &current); err != nil {
		return ""
	}
	for _, segment := range strings.Split(path, ".") {
		object, ok := current.(map[string]any)
		if !ok {
			return ""
		}
		current, ok = object[segment]
		if !ok {
			return ""
		}
	}
	if value, ok := current.(string); ok {
		return value
	}
	return ""
}
