package config

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"math"
	"net"
	"net/url"
	"strings"
)

// ExitConfig is sysexits.h EX_CONFIG. A supervisor restarting a service that is
// misconfigured will restart it forever; a distinct code says "do not bother".
const ExitConfig = 78

// Error is a configuration problem stated in terms of what to do about it.
type Error struct {
	Path    string // which setting, e.g. "auth.tokens[0].sha256"
	Problem string
	Fix     string
}

func (e *Error) Error() string {
	msg := fmt.Sprintf("config: %s: %s", e.Path, e.Problem)
	if e.Fix != "" {
		msg += "\n  try: " + e.Fix
	}
	return msg
}

// HashToken is the one place the token digest is computed, so the hub and
// hubctl cannot disagree about it.
func HashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

// placeholders are the values people leave in place. They are compared as
// hashes as well as raw, because copying the example file's sha256 line is
// exactly as bad as copying its token line -- and rather more likely to be
// missed in review, since it looks like a real secret.
var placeholders = []string{
	"changeme", "change_me", "CHANGE_ME", "changethis",
	"secret", "password", "token", "hunter2",
	"REPLACE_ME", "your-token-here", "example",
}

const (
	minTokenLength  = 32
	minTokenEntropy = 3.5 // bits per character
)

func (c *Config) Validate() error {
	if err := c.validateServer(); err != nil {
		return err
	}
	if err := c.validateAuth(); err != nil {
		return err
	}
	return c.validateServices()
}

func (c *Config) validateServer() error {
	// SplitHostPort rather than cutting at the first colon, which mangles IPv6:
	// "[::1]:8791" has four of them and only the last one separates the port.
	host, _, err := net.SplitHostPort(c.Server.Listen)
	if err != nil {
		return &Error{
			Path:    "server.listen",
			Problem: fmt.Sprintf("%q is not host:port (%v)", c.Server.Listen, err),
			Fix:     `set it to "127.0.0.1:8791"`,
		}
	}

	loopback := host == "127.0.0.1" || host == "localhost" || host == "::1"
	if loopback {
		return nil
	}

	// Binding beyond loopback means something on the network can reach the hub
	// directly. That is only acceptable behind TLS or a proxy we trust to have
	// terminated it, because the bearer token would otherwise cross the wire in
	// clear text.
	if !c.Server.AllowPublicBind {
		return &Error{
			Path:    "server.listen",
			Problem: fmt.Sprintf("binds %q, which is not loopback", host),
			Fix:     "keep 127.0.0.1 and let Caddy reach it, or set server.allow_public_bind: true and read what that means",
		}
	}
	if len(c.Server.TrustProxyCIDRs) == 0 {
		return &Error{
			Path:    "server.allow_public_bind",
			Problem: "is set, but no TLS terminator is declared in server.trust_proxy_cidrs",
			Fix:     `put Caddy in front and set trust_proxy_cidrs: ["127.0.0.1/32"]`,
		}
	}
	return nil
}

func (c *Config) validateAuth() error {
	if len(c.Auth.Tokens) == 0 {
		return &Error{
			Path:    "auth.tokens",
			Problem: "no tokens configured, so nothing could ever authenticate",
			Fix:     "hubctl token new --label pocketds",
		}
	}

	seenLabels := map[string]bool{}
	placeholderHashes := map[string]string{}
	for _, p := range placeholders {
		placeholderHashes[HashToken(p)] = p
	}

	for i, token := range c.Auth.Tokens {
		path := fmt.Sprintf("auth.tokens[%d]", i)

		if token.Label == "" {
			return &Error{
				Path:    path + ".label",
				Problem: "is empty, so this token cannot be revoked by name",
				Fix:     `give it a label, e.g. "pocketds"`,
			}
		}
		if seenLabels[token.Label] {
			return &Error{
				Path:    path + ".label",
				Problem: fmt.Sprintf("%q is used more than once", token.Label),
				Fix:     "labels identify a device; give each one its own",
			}
		}
		seenLabels[token.Label] = true

		hash := strings.ToLower(strings.TrimSpace(token.SHA256))
		switch {
		case hash == "" && !token.Raw.IsSet():
			return &Error{
				Path:    path,
				Problem: "has neither sha256 nor token",
				Fix:     "hubctl token new --label " + token.Label,
			}

		case hash != "":
			if len(hash) != 64 {
				return &Error{
					Path:    path + ".sha256",
					Problem: fmt.Sprintf("is %d characters; a SHA-256 hex digest is 64", len(hash)),
					Fix:     "hubctl token new --label " + token.Label,
				}
			}
			if _, err := hex.DecodeString(hash); err != nil {
				return &Error{
					Path:    path + ".sha256",
					Problem: "is not hexadecimal",
					Fix:     "hubctl token new --label " + token.Label,
				}
			}
			if plain, isPlaceholder := placeholderHashes[hash]; isPlaceholder {
				return &Error{
					Path:    path + ".sha256",
					Problem: fmt.Sprintf("is the hash of %q, a placeholder", plain),
					Fix:     "hubctl token new --label " + token.Label,
				}
			}

		default: // a raw token, which has to earn its place
			raw := token.Raw.Reveal()
			if isPlaceholder(raw) {
				return &Error{
					Path:    path + ".token",
					Problem: fmt.Sprintf("%q is a placeholder", raw),
					Fix:     "hubctl token new --label " + token.Label,
				}
			}
			if len(raw) < minTokenLength {
				return &Error{
					Path:    path + ".token",
					Problem: fmt.Sprintf("is %d characters; %d is the minimum", len(raw), minTokenLength),
					Fix:     "hubctl token new --label " + token.Label,
				}
			}
			if e := shannonEntropy(raw); e < minTokenEntropy {
				return &Error{
					Path:    path + ".token",
					Problem: fmt.Sprintf("has %.2f bits of entropy per character; %.1f is the minimum", e, minTokenEntropy),
					Fix:     "hubctl token new --label " + token.Label,
				}
			}
		}
	}
	return nil
}

func (c *Config) validateServices() error {
	known := map[string]bool{}
	for _, name := range KnownServices {
		known[name] = true
	}

	for name, svc := range c.Services {
		if !known[name] {
			return &Error{
				Path:    "services." + name,
				Problem: "is not a service this hub understands",
				Fix:     "one of: " + strings.Join(KnownServices, ", "),
			}
		}
		if svc.WebURL != "" {
			parsed, err := url.Parse(svc.WebURL)
			if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
				return &Error{
					Path:    "services." + name + ".web_url",
					Problem: fmt.Sprintf("%q is not an absolute http(s) URL", svc.WebURL),
					Fix:     "set the address that opens from the handheld browser, including http:// or https://",
				}
			}
			if parsed.User != nil {
				return &Error{
					Path:    "services." + name + ".web_url",
					Problem: "must not contain a username or password",
					Fix:     "use the service login page and let the browser store its own session",
				}
			}
		}

		if !svc.Enabled {
			continue
		}

		path := "services." + name
		if svc.BaseURL == "" {
			return &Error{
				Path:    path + ".base_url",
				Problem: "is empty but the service is enabled",
				Fix:     fmt.Sprintf(`set it, e.g. "http://127.0.0.1:%s"`, defaultPort(name)),
			}
		}
		parsed, err := url.Parse(svc.BaseURL)
		if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
			return &Error{
				Path:    path + ".base_url",
				Problem: fmt.Sprintf("%q is not an absolute http(s) URL", svc.BaseURL),
				Fix:     fmt.Sprintf(`include the scheme, e.g. "http://127.0.0.1:%s"`, defaultPort(name)),
			}
		}

		if svc.InsecureSkipVerify && !isPrivateHost(parsed.Hostname()) {
			return &Error{
				Path:    path + ".insecure_skip_verify",
				Problem: fmt.Sprintf("is set for %q, which is not a loopback or private address", parsed.Hostname()),
				Fix:     "only ever skip verification for a service on this machine or your LAN",
			}
		}

		// qBittorrent is the odd one twice over. It takes a bearer key on newer
		// builds and a username/password session on older ones -- and it has a
		// "bypass authentication for clients on localhost" setting that is on by
		// default, under which a loopback connection needs no credential at all.
		//
		// So credentials are only required when we are not on loopback. Demanding
		// them regardless would make the common, working setup fail to start.
		if name == "qbittorrent" {
			hasCredentials := svc.APIKey.IsSet() || (svc.Username != "" && svc.Password.IsSet())
			if !hasCredentials && !isPrivateHost(parsed.Hostname()) {
				return &Error{
					Path:    path,
					Problem: "needs either api_key or username and password when it is not on this machine",
					Fix:     "newer builds support an API key under Web UI settings; older ones need the login",
				}
			}
			continue
		}
		if !svc.APIKey.IsSet() {
			return &Error{
				Path:    path + ".api_key",
				Problem: "is empty but the service is enabled",
				Fix:     "use ${env:" + strings.ToUpper(name) + "_API_KEY} and set it in the environment",
			}
		}
	}
	return nil
}

// isPrivateHost reports whether a host is somewhere an attacker would already
// have to be inside to intercept.
func isPrivateHost(host string) bool {
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}

func defaultPort(service string) string {
	switch service {
	case "jellyfin":
		return "8096"
	case "jellyseerr":
		return "5055"
	case "radarr":
		return "7878"
	case "sonarr":
		return "8989"
	case "bazarr":
		return "6767"
	case "qbittorrent":
		return "8080"
	}
	return "8080"
}

func isPlaceholder(value string) bool {
	lower := strings.ToLower(strings.TrimSpace(value))
	for _, p := range placeholders {
		if lower == strings.ToLower(p) {
			return true
		}
	}
	return false
}

// shannonEntropy measures bits per character, which catches the tokens people
// type by hand -- "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" is long enough and still
// worthless. It is a smell test, not a security proof.
func shannonEntropy(s string) float64 {
	if s == "" {
		return 0
	}
	counts := map[rune]int{}
	total := 0
	for _, r := range s {
		counts[r]++
		total++
	}
	entropy := 0.0
	for _, n := range counts {
		p := float64(n) / float64(total)
		entropy -= p * math.Log2(p)
	}
	return entropy
}
