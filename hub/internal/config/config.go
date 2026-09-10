package config

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// Duration is a time.Duration that reads "8s" from YAML.
type Duration time.Duration

func (d *Duration) UnmarshalYAML(node *yaml.Node) error {
	var text string
	if err := node.Decode(&text); err != nil {
		return err
	}
	parsed, err := time.ParseDuration(text)
	if err != nil {
		return fmt.Errorf("%q is not a duration like 8s or 2m: %w", text, err)
	}
	*d = Duration(parsed)
	return nil
}

func (d Duration) Std() time.Duration { return time.Duration(d) }

func (d Duration) OrDefault(fallback time.Duration) time.Duration {
	if d == 0 {
		return fallback
	}
	return time.Duration(d)
}

type Config struct {
	Server   ServerConfig             `yaml:"server"`
	Auth     AuthConfig               `yaml:"auth"`
	Services map[string]ServiceConfig `yaml:"services"`
	Log      LogConfig                `yaml:"log"`
}

type ServerConfig struct {
	// Loopback by default. Binding anywhere else requires AllowPublicBind and
	// either TLS in front or a trusted proxy -- see validate.go.
	Listen string `yaml:"listen"`
	// How the handheld reaches us, for building absolute URLs.
	PublicURL       string   `yaml:"public_url"`
	AllowPublicBind bool     `yaml:"allow_public_bind"`
	TrustProxyCIDRs []string `yaml:"trust_proxy_cidrs"`
	RequestTimeout  Duration `yaml:"request_timeout"`
	ShutdownGrace   Duration `yaml:"shutdown_grace"`
	// OfflineRegistry persists short-lived download grants and progress-sync
	// receipts. Relative paths are resolved beside the main config file.
	OfflineRegistry string `yaml:"offline_registry"`
}

type AuthConfig struct {
	Tokens         []TokenConfig   `yaml:"tokens"`
	RateLimit      RateLimitConfig `yaml:"rate_limit"`
	AuthFailureBan BanConfig       `yaml:"auth_failure_ban"`
}

// TokenConfig stores only the SHA-256 of a token, never the token.
//
// A config file that leaks -- backed up, screenshotted, pasted into a chat while
// debugging -- then costs nothing. Raw is accepted for a first run and refused
// unless it is strong, because the alternative is people leaving the example
// value in place.
type TokenConfig struct {
	Label  string   `yaml:"label"`
	SHA256 string   `yaml:"sha256"`
	Raw    Secret   `yaml:"token"`
	Scopes []string `yaml:"scopes"`
}

type RateLimitConfig struct {
	RPM   int `yaml:"rpm"`
	Burst int `yaml:"burst"`
}

type BanConfig struct {
	Attempts int      `yaml:"attempts"`
	Window   Duration `yaml:"window"`
	Ban      Duration `yaml:"ban"`
}

type ServiceConfig struct {
	Enabled bool   `yaml:"enabled"`
	BaseURL string `yaml:"base_url"`
	// WebURL is the address a handheld browser can open. It is deliberately
	// separate from BaseURL: the hub often reaches a service over loopback or a
	// container-only hostname while the browser needs a LAN or HTTPS address.
	// When empty, the health response falls back to a sanitized BaseURL.
	WebURL string `yaml:"web_url"`
	APIKey Secret `yaml:"api_key"`
	// qBittorrent's fallback when the build has no bearer-key support.
	Username string   `yaml:"username"`
	Password Secret   `yaml:"password"`
	Timeout  Duration `yaml:"timeout"`
	// Jellyfin: whose views and watch state we surface.
	UserID string `yaml:"user_id"`
	// Jellyseerr: sent as X-API-User so requests are attributed to the real
	// account rather than to admin user 1.
	ActAsUserID int `yaml:"act_as_user_id"`
	// Bazarr: "" means probe for it. Its base_url setting prefixes every route.
	APIBasePath string `yaml:"api_base_path"`
	// Which Jellyseerr serviceId maps to this Radarr/Sonarr instance.
	JellyseerrServiceID int `yaml:"jellyseerr_service_id"`
	// Accept a self-signed certificate from this service.
	//
	// Jellyfin ships with HTTPS forced and a self-signed cert, so reaching it on
	// the same machine otherwise fails outright. Validation refuses this for any
	// host that is not loopback or RFC1918: on loopback there is no position from
	// which to intercept the connection, so verification buys nothing, whereas
	// over a real network it would buy everything.
	InsecureSkipVerify bool `yaml:"insecure_skip_verify"`
}

type LogConfig struct {
	Level      string `yaml:"level"`
	Dir        string `yaml:"dir"`
	RetainDays int    `yaml:"retain_days"`
}

// KnownServices is the fixed set the hub understands. Anything else in the
// config is a typo, and a silently ignored typo in a base_url is a service that
// mysteriously never works.
var KnownServices = []string{
	"jellyfin", "jellyseerr", "radarr", "sonarr", "bazarr", "qbittorrent",
}

var envPattern = regexp.MustCompile(`\$\{env:([A-Za-z_][A-Za-z0-9_]*)\}`)

// Load reads the config, merges any sibling secrets file, expands ${env:NAME},
// applies defaults and validates.
//
// The merge is a genuine deep merge rather than a concatenation or a sequential
// decode. Both of those look like they work and quietly do the wrong thing: a
// secrets file saying
//
//	services: { radarr: { api_key: "..." } }
//
// would replace the whole services map and take base_url with it, leaving a
// service that is configured, enabled, and pointing nowhere.
func Load(path string) (*Config, error) {
	merged, err := readYAMLMap(path)
	if err != nil {
		return nil, err
	}

	secretsPath := strings.TrimSuffix(path, filepath.Ext(path)) + ".secrets.yaml"
	if _, statErr := os.Stat(secretsPath); statErr == nil {
		overlay, err := readYAMLMap(secretsPath)
		if err != nil {
			return nil, err
		}
		deepMerge(merged, overlay)
	}

	// Round-trip so the struct decode sees one document, and so KnownFields
	// still catches a typo in either file.
	normalised, err := yaml.Marshal(merged)
	if err != nil {
		return nil, fmt.Errorf("re-encoding config: %w", err)
	}

	cfg := &Config{}
	decoder := yaml.NewDecoder(strings.NewReader(string(normalised)))
	// A misspelled key is a setting that silently does nothing, which is the
	// worst possible outcome for something like allow_public_bind.
	decoder.KnownFields(true)
	if err := decoder.Decode(cfg); err != nil && err != io.EOF {
		return nil, fmt.Errorf("parsing %s: %w", path, err)
	}

	cfg.applyDefaults()
	if cfg.Server.OfflineRegistry == "" {
		cfg.Server.OfflineRegistry = filepath.Join(filepath.Dir(path), "offline-grants.json")
	} else if !filepath.IsAbs(cfg.Server.OfflineRegistry) {
		cfg.Server.OfflineRegistry = filepath.Join(filepath.Dir(path), cfg.Server.OfflineRegistry)
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func readYAMLMap(path string) (map[string]any, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("reading %s: %w", path, err)
	}
	expanded, err := expandEnv(string(raw))
	if err != nil {
		return nil, err
	}
	out := map[string]any{}
	if err := yaml.Unmarshal([]byte(expanded), &out); err != nil {
		return nil, fmt.Errorf("parsing %s: %w", path, err)
	}
	if out == nil {
		out = map[string]any{}
	}
	return out, nil
}

// deepMerge copies src over dst, recursing into maps so that setting one leaf
// does not discard its siblings.
func deepMerge(dst, src map[string]any) {
	for key, value := range src {
		if srcMap, ok := value.(map[string]any); ok {
			if dstMap, ok := dst[key].(map[string]any); ok {
				deepMerge(dstMap, srcMap)
				continue
			}
		}
		dst[key] = value
	}
}

func expandEnv(in string) (string, error) {
	var missing []string
	out := envPattern.ReplaceAllStringFunc(in, func(match string) string {
		name := envPattern.FindStringSubmatch(match)[1]
		value, ok := os.LookupEnv(name)
		if !ok {
			missing = append(missing, name)
			return match
		}
		return value
	})
	if len(missing) > 0 {
		return "", fmt.Errorf("environment variables not set: %s", strings.Join(missing, ", "))
	}
	return out, nil
}

func (c *Config) applyDefaults() {
	if c.Server.Listen == "" {
		c.Server.Listen = "127.0.0.1:8791"
	}
	if c.Server.RequestTimeout == 0 {
		c.Server.RequestTimeout = Duration(20 * time.Second)
	}
	if c.Server.ShutdownGrace == 0 {
		c.Server.ShutdownGrace = Duration(10 * time.Second)
	}
	if c.Auth.RateLimit.RPM == 0 {
		c.Auth.RateLimit.RPM = 90
	}
	if c.Auth.RateLimit.Burst == 0 {
		c.Auth.RateLimit.Burst = 30
	}
	if c.Auth.AuthFailureBan.Attempts == 0 {
		c.Auth.AuthFailureBan.Attempts = 5
	}
	if c.Auth.AuthFailureBan.Window == 0 {
		c.Auth.AuthFailureBan.Window = Duration(time.Minute)
	}
	if c.Auth.AuthFailureBan.Ban == 0 {
		c.Auth.AuthFailureBan.Ban = Duration(15 * time.Minute)
	}
	if c.Log.Level == "" {
		c.Log.Level = "info"
	}
	for name, svc := range c.Services {
		if svc.Timeout == 0 {
			svc.Timeout = Duration(8 * time.Second)
			c.Services[name] = svc
		}
	}
}

// EnabledServices returns the configured, enabled services in a stable order.
func (c *Config) EnabledServices() []string {
	var out []string
	for _, name := range KnownServices {
		if svc, ok := c.Services[name]; ok && svc.Enabled {
			out = append(out, name)
		}
	}
	return out
}
