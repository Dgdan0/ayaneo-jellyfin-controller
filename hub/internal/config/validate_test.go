package config

import (
	"strings"
	"testing"
)

// A token with real entropy, for the cases that are supposed to pass.
const goodToken = "Kp7wZ2xQ9mR4tYbN6vC1sD8jH3gL5aF0"

func base() *Config {
	c := &Config{
		Server: ServerConfig{Listen: "127.0.0.1:8791"},
		Auth: AuthConfig{
			Tokens: []TokenConfig{{Label: "pocketds", SHA256: HashToken(goodToken)}},
		},
		Services: map[string]ServiceConfig{},
	}
	c.applyDefaults()
	return c
}

func wantError(t *testing.T, c *Config, contains string) {
	t.Helper()
	err := c.Validate()
	if err == nil {
		t.Fatalf("expected a config error mentioning %q, got none", contains)
	}
	if !strings.Contains(err.Error(), contains) {
		t.Fatalf("error %q does not mention %q", err.Error(), contains)
	}
}

func wantOK(t *testing.T, c *Config) {
	t.Helper()
	if err := c.Validate(); err != nil {
		t.Fatalf("expected valid, got: %v", err)
	}
}

// --- tokens ---------------------------------------------------------------

func TestRefusesToStartWithNoTokens(t *testing.T) {
	c := base()
	c.Auth.Tokens = nil
	wantError(t, c, "no tokens configured")
}

func TestRefusesAPlaceholderToken(t *testing.T) {
	for _, placeholder := range []string{"changeme", "CHANGE_ME", "password", "secret", "  changeme  "} {
		c := base()
		c.Auth.Tokens = []TokenConfig{{Label: "x", Raw: Secret(placeholder)}}
		wantError(t, c, "placeholder")
	}
}

func TestRefusesTheHashOfAPlaceholder(t *testing.T) {
	// Copying the example file's sha256 line looks like a real secret in review
	// and is exactly as bad as copying its token line.
	c := base()
	c.Auth.Tokens = []TokenConfig{{Label: "x", SHA256: HashToken("changeme")}}
	wantError(t, c, "placeholder")
}

func TestRefusesAShortToken(t *testing.T) {
	c := base()
	c.Auth.Tokens = []TokenConfig{{Label: "x", Raw: Secret("Kp7wZ2xQ9mR4tYb")}}
	wantError(t, c, "minimum")
}

func TestRefusesALongButWorthlessToken(t *testing.T) {
	// Long enough to pass a length check and still trivially guessable.
	c := base()
	c.Auth.Tokens = []TokenConfig{{Label: "x", Raw: Secret(strings.Repeat("a", 64))}}
	wantError(t, c, "entropy")
}

func TestRefusesAMalformedHash(t *testing.T) {
	c := base()
	c.Auth.Tokens = []TokenConfig{{Label: "x", SHA256: "abc123"}}
	wantError(t, c, "64")

	c = base()
	c.Auth.Tokens = []TokenConfig{{Label: "x", SHA256: strings.Repeat("z", 64)}}
	wantError(t, c, "hexadecimal")
}

func TestRefusesATokenWithNeitherHashNorValue(t *testing.T) {
	c := base()
	c.Auth.Tokens = []TokenConfig{{Label: "x"}}
	wantError(t, c, "neither")
}

func TestRefusesAnUnlabelledToken(t *testing.T) {
	// Without a label there is no way to revoke one device.
	c := base()
	c.Auth.Tokens = []TokenConfig{{SHA256: HashToken(goodToken)}}
	wantError(t, c, "label")
}

func TestRefusesDuplicateLabels(t *testing.T) {
	c := base()
	c.Auth.Tokens = []TokenConfig{
		{Label: "pocketds", SHA256: HashToken(goodToken)},
		{Label: "pocketds", SHA256: HashToken(goodToken + "2")},
	}
	wantError(t, c, "more than once")
}

func TestAcceptsAStrongToken(t *testing.T) {
	wantOK(t, base())

	c := base()
	c.Auth.Tokens = []TokenConfig{{Label: "x", Raw: Secret(goodToken)}}
	wantOK(t, c)
}

func TestEveryTokenErrorSuggestsTheFix(t *testing.T) {
	// A refusal to start is only useful if it says what to do next.
	c := base()
	c.Auth.Tokens = nil
	err := c.Validate()
	if !strings.Contains(err.Error(), "hubctl token new") {
		t.Fatalf("no suggested fix in: %v", err)
	}
}

// --- binding --------------------------------------------------------------

func TestLoopbackNeedsNoPermission(t *testing.T) {
	// IPv6 must be bracketed; "::1:8791" is not a valid host:port at all.
	for _, listen := range []string{"127.0.0.1:8791", "localhost:8791", "[::1]:8791"} {
		c := base()
		c.Server.Listen = listen
		wantOK(t, c)
	}
}

func TestRefusesANonLoopbackBindByDefault(t *testing.T) {
	// Binding 0.0.0.0 by accident is how the bearer token ends up crossing a
	// LAN in clear text.
	c := base()
	c.Server.Listen = "0.0.0.0:8791"
	wantError(t, c, "not loopback")
}

func TestRefusesAPublicBindWithNoTLSInFront(t *testing.T) {
	c := base()
	c.Server.Listen = "0.0.0.0:8791"
	c.Server.AllowPublicBind = true
	wantError(t, c, "trust_proxy_cidrs")
}

func TestAcceptsAPublicBindBehindATrustedProxy(t *testing.T) {
	c := base()
	c.Server.Listen = "0.0.0.0:8791"
	c.Server.AllowPublicBind = true
	c.Server.TrustProxyCIDRs = []string{"127.0.0.1/32"}
	wantOK(t, c)
}

func TestRefusesAListenWithoutAPort(t *testing.T) {
	c := base()
	c.Server.Listen = "127.0.0.1"
	wantError(t, c, "host:port")
}

// --- services -------------------------------------------------------------

func TestRefusesAnUnknownService(t *testing.T) {
	// A typo'd service name is a service that silently never works.
	c := base()
	c.Services["radar"] = ServiceConfig{Enabled: true, BaseURL: "http://x:7878", APIKey: "k"}
	wantError(t, c, "not a service this hub understands")
}

func TestDisabledServicesAreNotChecked(t *testing.T) {
	c := base()
	c.Services["radarr"] = ServiceConfig{Enabled: false}
	wantOK(t, c)
}

func TestRefusesAnEnabledServiceWithNoBaseURL(t *testing.T) {
	c := base()
	c.Services["radarr"] = ServiceConfig{Enabled: true, APIKey: "k"}
	wantError(t, c, "base_url")
}

func TestRefusesABaseURLWithoutAScheme(t *testing.T) {
	c := base()
	c.Services["radarr"] = ServiceConfig{Enabled: true, BaseURL: "127.0.0.1:7878", APIKey: "k"}
	wantError(t, c, "absolute http(s) URL")
}

func TestValidatesOptionalWebURL(t *testing.T) {
	c := base()
	c.Services["radarr"] = ServiceConfig{
		Enabled: true, BaseURL: "http://127.0.0.1:7878", WebURL: "radarr.local:7878", APIKey: "k",
	}
	wantError(t, c, "web_url")

	c.Services["radarr"] = ServiceConfig{
		Enabled: true, BaseURL: "http://127.0.0.1:7878", WebURL: "https://radarr.example.test", APIKey: "k",
	}
	wantOK(t, c)
}

func TestWebURLRefusesEmbeddedCredentials(t *testing.T) {
	c := base()
	c.Services["radarr"] = ServiceConfig{
		Enabled: true, BaseURL: "http://127.0.0.1:7878", WebURL: "https://user:secret@radarr.example.test", APIKey: "k",
	}
	wantError(t, c, "username or password")
}

func TestRefusesAnEnabledServiceWithNoKey(t *testing.T) {
	c := base()
	c.Services["sonarr"] = ServiceConfig{Enabled: true, BaseURL: "http://127.0.0.1:8989"}
	wantError(t, c, "api_key")
}

func TestQBittorrentAcceptsEitherCredentialStyle(t *testing.T) {
	// Newer builds take a bearer key; older ones need the login session.
	withKey := base()
	withKey.Services["qbittorrent"] = ServiceConfig{
		Enabled: true, BaseURL: "http://127.0.0.1:8080", APIKey: "k",
	}
	wantOK(t, withKey)

	withLogin := base()
	withLogin.Services["qbittorrent"] = ServiceConfig{
		Enabled: true, BaseURL: "http://127.0.0.1:8080", Username: "admin", Password: "pw",
	}
	wantOK(t, withLogin)

	// On loopback, qBittorrent's "bypass authentication for clients on
	// localhost" is on by default and no credential is needed. Measured on this
	// machine: WebUI\LocalHostAuth=false, and the API answers unauthenticated.
	onLoopback := base()
	onLoopback.Services["qbittorrent"] = ServiceConfig{
		Enabled: true, BaseURL: "http://127.0.0.1:8080",
	}
	wantOK(t, onLoopback)

	// Off-machine there is no such bypass, so credentials become mandatory.
	remote := base()
	remote.Services["qbittorrent"] = ServiceConfig{
		Enabled: true, BaseURL: "http://qbit.example.com:8080",
	}
	wantError(t, remote, "when it is not on this machine")
}

func TestEnabledServicesAreReturnedInAStableOrder(t *testing.T) {
	c := base()
	c.Services["sonarr"] = ServiceConfig{Enabled: true, BaseURL: "http://x:8989", APIKey: "k"}
	c.Services["jellyfin"] = ServiceConfig{Enabled: true, BaseURL: "http://x:8096", APIKey: "k"}
	c.Services["bazarr"] = ServiceConfig{Enabled: false}

	got := strings.Join(c.EnabledServices(), ",")
	if got != "jellyfin,sonarr" {
		t.Fatalf("EnabledServices() = %q, want jellyfin,sonarr", got)
	}
}

// --- entropy helper -------------------------------------------------------

func TestShannonEntropy(t *testing.T) {
	if e := shannonEntropy(""); e != 0 {
		t.Errorf("empty string entropy = %v, want 0", e)
	}
	if e := shannonEntropy("aaaa"); e != 0 {
		t.Errorf("single-character entropy = %v, want 0", e)
	}
	random := shannonEntropy(goodToken)
	repeated := shannonEntropy(strings.Repeat("ab", 16))
	if random <= repeated {
		t.Errorf("a random token (%.2f) should score above a repeated one (%.2f)", random, repeated)
	}
}

// --- insecure_skip_verify -------------------------------------------------

func TestInsecureSkipVerifyIsAllowedOnlyForPrivateHosts(t *testing.T) {
	// Jellyfin forces HTTPS with a self-signed cert, so reaching it on the same
	// machine needs this. Allowing it for a public host would hand away the
	// entire point of TLS, so validation refuses that outright.
	for _, host := range []string{"127.0.0.1", "localhost", "192.168.1.20", "10.0.0.5"} {
		c := base()
		c.Services["jellyfin"] = ServiceConfig{
			Enabled: true, BaseURL: "https://" + host + ":8920", APIKey: "k",
			InsecureSkipVerify: true,
		}
		wantOK(t, c)
	}

	for _, host := range []string{"myjellydan.duckdns.org", "8.8.8.8", "example.com"} {
		c := base()
		c.Services["jellyfin"] = ServiceConfig{
			Enabled: true, BaseURL: "https://" + host + ":8920", APIKey: "k",
			InsecureSkipVerify: true,
		}
		wantError(t, c, "not a loopback or private address")
	}
}

func TestAPublicHostIsFineWithVerificationOn(t *testing.T) {
	c := base()
	c.Services["jellyfin"] = ServiceConfig{
		Enabled: true, BaseURL: "https://myjellydan.duckdns.org", APIKey: "k",
	}
	wantOK(t, c)
}
