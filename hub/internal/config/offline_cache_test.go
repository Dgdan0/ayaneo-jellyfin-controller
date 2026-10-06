package config

import (
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The folder, size and age of the repackaged MP4s an Apple download waits in
// (#5). The default sits under the hub's data folder, beside hub.yaml, like every
// other file the hub keeps; the owner points it at a big drive.

func loadWithServerYAML(t *testing.T, server string) (*Config, string, error) {
	t.Helper()
	dir := t.TempDir()
	body := strings.Replace(mainYAML, "%s", HashToken(goodToken), 1)
	body = strings.Replace(body, `  listen: "127.0.0.1:8791"`, `  listen: "127.0.0.1:8791"`+"\n"+server, 1)
	path := writeFile(t, dir, "hub.yaml", body)
	writeFile(t, dir, "hub.secrets.yaml", `
services:
  radarr:
    api_key: "radarr-key"
  sonarr:
    api_key: "sonarr-key"
`)
	cfg, err := Load(path)
	return cfg, dir, err
}

func TestOfflineCacheDefaultsLieBesideTheConfig(t *testing.T) {
	cfg, dir, err := loadWithServerYAML(t, "")
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(dir, "offline-cache"); cfg.Server.OfflineCache != want {
		t.Errorf("offline_cache = %q, want %q", cfg.Server.OfflineCache, want)
	}
	if want := int64(20) << 30; cfg.Server.OfflineCacheMaxBytes.Bytes() != want {
		t.Errorf("offline_cache_max_bytes = %d, want 20 GiB", cfg.Server.OfflineCacheMaxBytes.Bytes())
	}
	if cfg.Server.OfflineCacheMaxAge.Std() != 48*time.Hour {
		t.Errorf("offline_cache_max_age = %v, want 48h", cfg.Server.OfflineCacheMaxAge.Std())
	}
}

func TestOfflineCacheKeysAreReadAndRelativePathsResolveBesideTheConfig(t *testing.T) {
	cfg, dir, err := loadWithServerYAML(t, "  offline_cache: \"cache\"\n  offline_cache_max_bytes: 150GB\n  offline_cache_max_age: 72h")
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(dir, "cache"); cfg.Server.OfflineCache != want {
		t.Errorf("a relative offline_cache = %q, want %q", cfg.Server.OfflineCache, want)
	}
	if cfg.Server.OfflineCacheMaxBytes.Bytes() != 150_000_000_000 {
		t.Errorf("offline_cache_max_bytes = %d, want 150 GB", cfg.Server.OfflineCacheMaxBytes.Bytes())
	}
	if cfg.Server.OfflineCacheMaxAge.Std() != 72*time.Hour {
		t.Errorf("offline_cache_max_age = %v", cfg.Server.OfflineCacheMaxAge.Std())
	}

	absolute := filepath.Join(t.TempDir(), "elsewhere")
	cfg, _, err = loadWithServerYAML(t, "  offline_cache: '"+absolute+"'")
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Server.OfflineCache != absolute {
		t.Errorf("an absolute offline_cache = %q, want it kept as %q", cfg.Server.OfflineCache, absolute)
	}
}

func TestByteSizeReadsNumbersAndUnits(t *testing.T) {
	for text, want := range map[string]int64{
		"1073741824":  1 << 30,
		"5000MB":      5_000_000_000,
		"150 GB":      150_000_000_000,
		"1.5GiB":      3 << 29,
		"20GiB":       20 << 30,
		"2 tib":       2 << 40,
		"750000000KB": 750_000_000_000,
	} {
		cfg, _, err := loadWithServerYAML(t, "  offline_cache_max_bytes: \""+text+"\"")
		if err != nil {
			t.Errorf("%q: %v", text, err)
			continue
		}
		if got := cfg.Server.OfflineCacheMaxBytes.Bytes(); got != want {
			t.Errorf("%q = %d, want %d", text, got, want)
		}
	}
}

func TestOfflineCacheAgeTooShortIsRefused(t *testing.T) {
	if _, _, err := loadWithServerYAML(t, "  offline_cache_max_age: 5s"); err == nil || !strings.Contains(err.Error(), "offline_cache_max_age") {
		t.Fatalf("a 5 second age was accepted or the error does not name it: %v", err)
	}
}

func TestByteSizeRefusesWhatItCannotRead(t *testing.T) {
	for _, text := range []string{"lots", "-5GB", "10XB", "GB", "1.2.3GB", "9999999999TiB"} {
		_, _, err := loadWithServerYAML(t, "  offline_cache_max_bytes: \""+text+"\"")
		if err == nil {
			t.Errorf("%q was accepted", text)
		}
	}
}

// A cap of a few hundred thousand is a number of bytes mistaken for a number of
// kilobytes: it could not hold one episode.
func TestOfflineCacheCapTooSmallForAnEpisodeIsRefused(t *testing.T) {
	_, _, err := loadWithServerYAML(t, "  offline_cache_max_bytes: 150000")
	if err == nil {
		t.Fatal("a 150 KB cache was accepted")
	}
	if !strings.Contains(err.Error(), "offline_cache_max_bytes") {
		t.Fatalf("the error does not name the setting: %v", err)
	}
	if _, _, err := loadWithServerYAML(t, "  offline_cache_max_bytes: 1GB"); err != nil {
		t.Fatalf("1 GB is the floor and was refused: %v", err)
	}
}
