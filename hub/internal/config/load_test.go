package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeFile(t *testing.T, dir, name, body string) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

const mainYAML = `
server:
  listen: "127.0.0.1:8791"
auth:
  tokens:
    - label: "pocketds"
      sha256: "` + `%s` + `"
services:
  radarr:
    enabled: true
    base_url: "http://127.0.0.1:7878"
    jellyseerr_service_id: 3
  sonarr:
    enabled: true
    base_url: "http://127.0.0.1:8989"
`

func TestSecretsAreMergedWithoutLosingSiblings(t *testing.T) {
	// The bug this exists to prevent: concatenating the two files, or decoding
	// them in sequence, replaces the whole services map. radarr would come back
	// with an api_key and no base_url -- enabled, configured, pointing nowhere.
	dir := t.TempDir()
	body := strings.Replace(mainYAML, "%s", HashToken(goodToken), 1)
	path := writeFile(t, dir, "hub.yaml", body)
	writeFile(t, dir, "hub.secrets.yaml", `
services:
  radarr:
    api_key: "radarr-key"
  sonarr:
    api_key: "sonarr-key"
`)

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	radarr := cfg.Services["radarr"]
	if radarr.APIKey.Reveal() != "radarr-key" {
		t.Errorf("api_key not merged in: %q", radarr.APIKey.Reveal())
	}
	if radarr.BaseURL != "http://127.0.0.1:7878" {
		t.Errorf("base_url was lost in the merge: %q", radarr.BaseURL)
	}
	if !radarr.Enabled {
		t.Error("enabled was lost in the merge")
	}
	if radarr.JellyseerrServiceID != 3 {
		t.Errorf("jellyseerr_service_id was lost: %d", radarr.JellyseerrServiceID)
	}
	if cfg.Services["sonarr"].BaseURL != "http://127.0.0.1:8989" {
		t.Error("the other service was disturbed by the merge")
	}
}

func TestLoadWorksWithNoSecretsFile(t *testing.T) {
	dir := t.TempDir()
	body := strings.Replace(mainYAML, "%s", HashToken(goodToken), 1)
	body = strings.Replace(body, `    base_url: "http://127.0.0.1:7878"`,
		"    base_url: \"http://127.0.0.1:7878\"\n    api_key: \"k\"", 1)
	body = strings.Replace(body, `    base_url: "http://127.0.0.1:8989"`,
		"    base_url: \"http://127.0.0.1:8989\"\n    api_key: \"k\"", 1)
	path := writeFile(t, dir, "hub.yaml", body)

	if _, err := Load(path); err != nil {
		t.Fatalf("Load without secrets: %v", err)
	}
}

func TestATypoIsRejectedInEitherFile(t *testing.T) {
	dir := t.TempDir()
	body := strings.Replace(mainYAML, "%s", HashToken(goodToken), 1)
	path := writeFile(t, dir, "hub.yaml", body)
	writeFile(t, dir, "hub.secrets.yaml", `
services:
  radarr:
    api_key: "k"
    apikey_typo: "oops"
  sonarr:
    api_key: "k"
`)
	_, err := Load(path)
	if err == nil || !strings.Contains(err.Error(), "apikey_typo") {
		t.Fatalf("a typo in the secrets file was accepted: %v", err)
	}
}

func TestEnvExpansion(t *testing.T) {
	t.Setenv("TEST_RADARR_KEY", "from-the-environment")
	dir := t.TempDir()
	body := strings.Replace(mainYAML, "%s", HashToken(goodToken), 1)
	body = strings.Replace(body, `    jellyseerr_service_id: 3`,
		"    api_key: \"${env:TEST_RADARR_KEY}\"", 1)
	body = strings.Replace(body, `    base_url: "http://127.0.0.1:8989"`,
		"    base_url: \"http://127.0.0.1:8989\"\n    api_key: \"k\"", 1)
	path := writeFile(t, dir, "hub.yaml", body)

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Services["radarr"].APIKey.Reveal() != "from-the-environment" {
		t.Errorf("env not expanded: %q", cfg.Services["radarr"].APIKey.Reveal())
	}
}

func TestAMissingEnvVarIsNamed(t *testing.T) {
	// Silently expanding to an empty string produces a service that fails to
	// authenticate for no visible reason.
	dir := t.TempDir()
	body := strings.Replace(mainYAML, "%s", HashToken(goodToken), 1)
	body = strings.Replace(body, `    jellyseerr_service_id: 3`,
		"    api_key: \"${env:DEFINITELY_NOT_SET_ANYWHERE}\"", 1)
	path := writeFile(t, dir, "hub.yaml", body)

	_, err := Load(path)
	if err == nil || !strings.Contains(err.Error(), "DEFINITELY_NOT_SET_ANYWHERE") {
		t.Fatalf("a missing env var was not reported by name: %v", err)
	}
}

func TestDeepMerge(t *testing.T) {
	dst := map[string]any{
		"a": map[string]any{"keep": 1, "override": "old"},
		"b": "untouched",
	}
	deepMerge(dst, map[string]any{
		"a": map[string]any{"override": "new", "added": 2},
		"c": "fresh",
	})

	a := dst["a"].(map[string]any)
	if a["keep"] != 1 {
		t.Error("a sibling key was lost")
	}
	if a["override"] != "new" {
		t.Error("the overlay did not win")
	}
	if a["added"] != 2 {
		t.Error("a new nested key was not added")
	}
	if dst["b"] != "untouched" || dst["c"] != "fresh" {
		t.Error("top-level merge is wrong")
	}
}
