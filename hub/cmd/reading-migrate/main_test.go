package main

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRunCreatesDryRunAndPerUserKomgaExportWithoutSecrets(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	output := t.TempDir()
	book := filepath.Join(source, "Series", "Issue.cbz")
	if err := os.MkdirAll(filepath.Dir(book), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(book, []byte("fixture"), 0o644); err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(book)
	if err != nil {
		t.Fatal(err)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		username, password, ok := r.BasicAuth()
		if !ok || username != "reader" || password != "command-secret" {
			t.Fatalf("unexpected credentials")
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v2/users/me":
			_, _ = w.Write([]byte(`{"id":"komga-user","email":"reader@example.test"}`))
		case "/api/v1/books/list":
			_, _ = w.Write([]byte(`{"content":[],"last":true,"number":0,"totalPages":1}`))
		case "/api/v1/readlists":
			_, _ = w.Write([]byte(`[]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	configPath := filepath.Join(t.TempDir(), "migration.json")
	config := commandConfig{
		SchemaVersion: 1,
		Sources:       []commandSource{{ID: "komga-comics", Kind: "comic", Root: source}},
		Destinations:  map[string]string{"comic": destination},
		KomgaExports: []komgaExportConfig{{
			ID: "primary", BaseURL: server.URL,
			UsernameEnv: "TEST_KOMGA_USERNAME", PasswordEnv: "TEST_KOMGA_PASSWORD",
		}},
	}
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	environment := map[string]string{
		"TEST_KOMGA_USERNAME": "reader",
		"TEST_KOMGA_PASSWORD": "command-secret",
	}
	var stdout bytes.Buffer
	if err := run(context.Background(), []string{"-config", configPath, "-out", output}, &stdout, func(name string) string { return environment[name] }, server.Client()); err != nil {
		t.Fatal(err)
	}

	if !strings.Contains(stdout.String(), "dry run complete") {
		t.Fatalf("stdout = %q", stdout.String())
	}
	for _, name := range []string{"config.json", "inventory.json", "plan.json", "rollback.json", "summary.txt", "komga-state.json"} {
		report, err := os.ReadFile(filepath.Join(output, name))
		if err != nil {
			t.Errorf("missing report %s: %v", name, err)
			continue
		}
		if bytes.Contains(report, []byte("command-secret")) {
			t.Errorf("credential leaked into %s", name)
		}
	}
	after, err := os.ReadFile(book)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(before, after) {
		t.Fatal("dry-run command changed source media")
	}
}

func TestRunRequiresEnvironmentCredentialsBeforeWritingBundle(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	output := filepath.Join(t.TempDir(), "not-created")
	configPath := filepath.Join(t.TempDir(), "migration.json")
	config := commandConfig{
		SchemaVersion: 1,
		Sources:       []commandSource{{ID: "comics", Kind: "comic", Root: source}},
		Destinations:  map[string]string{"comic": destination},
		KomgaExports: []komgaExportConfig{{
			ID: "primary", BaseURL: "https://example.test",
			UsernameEnv: "MISSING_USER", PasswordEnv: "MISSING_PASSWORD",
		}},
	}
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}

	err = run(context.Background(), []string{"-config", configPath, "-out", output}, &bytes.Buffer{}, func(string) string { return "" }, http.DefaultClient)
	if err == nil || !strings.Contains(err.Error(), "MISSING_USER") {
		t.Fatalf("missing credential error = %v", err)
	}
	if _, statErr := os.Stat(output); !os.IsNotExist(statErr) {
		t.Fatalf("output created before credentials were validated: %v", statErr)
	}
}

func TestRunSupportsKomgaAPIKeyFromEnvironment(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	output := t.TempDir()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-API-Key") != "command-api-key" || r.Header.Get("Authorization") != "" {
			t.Fatalf("unexpected API authentication headers")
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v2/users/me":
			_, _ = w.Write([]byte(`{"id":"api-user"}`))
		case "/api/v1/books/list":
			_, _ = w.Write([]byte(`{"content":[],"last":true,"number":0,"totalPages":1}`))
		case "/api/v1/readlists":
			_, _ = w.Write([]byte(`[]`))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	configPath := filepath.Join(t.TempDir(), "migration.json")
	config := commandConfig{
		SchemaVersion: 1,
		Sources:       []commandSource{{ID: "comics", Kind: "comic", Root: source}},
		Destinations:  map[string]string{"comic": destination},
		KomgaExports: []komgaExportConfig{{
			ID: "primary", BaseURL: server.URL, APIKeyEnv: "TEST_KOMGA_API_KEY",
		}},
	}
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	environment := map[string]string{"TEST_KOMGA_API_KEY": "command-api-key"}
	if err := run(context.Background(), []string{"-config", configPath, "-out", output}, &bytes.Buffer{}, func(name string) string { return environment[name] }, server.Client()); err != nil {
		t.Fatal(err)
	}
	report, err := os.ReadFile(filepath.Join(output, "komga-state.json"))
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(report, []byte("command-api-key")) {
		t.Fatalf("API key leaked into report")
	}
}

func TestRunRejectsMixedKomgaAuthentication(t *testing.T) {
	source := t.TempDir()
	destination := t.TempDir()
	output := filepath.Join(t.TempDir(), "not-created")
	configPath := filepath.Join(t.TempDir(), "migration.json")
	config := commandConfig{
		SchemaVersion: 1,
		Sources:       []commandSource{{ID: "comics", Kind: "comic", Root: source}},
		Destinations:  map[string]string{"comic": destination},
		KomgaExports: []komgaExportConfig{{
			ID: "primary", BaseURL: "https://example.test", APIKeyEnv: "KOMGA_KEY",
			UsernameEnv: "KOMGA_USER", PasswordEnv: "KOMGA_PASSWORD",
		}},
	}
	raw, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(configPath, raw, 0o600); err != nil {
		t.Fatal(err)
	}
	err = run(context.Background(), []string{"-config", configPath, "-out", output}, &bytes.Buffer{}, func(string) string { return "set" }, http.DefaultClient)
	if err == nil || !strings.Contains(err.Error(), "either api_key_env or username_env/password_env") {
		t.Fatalf("mixed authentication error = %v", err)
	}
	if _, statErr := os.Stat(output); !os.IsNotExist(statErr) {
		t.Fatalf("output created for mixed authentication: %v", statErr)
	}
}

func TestRunRejectsUnknownConfigurationFields(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "migration.yml")
	if err := os.WriteFile(configPath, []byte("schema_version: 1\nunknown_field: true\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	err := run(context.Background(), []string{"-config", configPath, "-out", t.TempDir()}, &bytes.Buffer{}, func(string) string { return "" }, http.DefaultClient)
	if err == nil || !strings.Contains(err.Error(), "unknown_field") {
		t.Fatalf("unknown field error = %v", err)
	}
}

func TestExampleConfigurationStaysParseable(t *testing.T) {
	config, err := loadCommandConfig(filepath.Join("..", "..", "..", "deploy", "reading", "migration.example.yml"))
	if err != nil {
		t.Fatal(err)
	}
	if len(config.Sources) != 4 || len(config.Destinations) != 4 || len(config.KomgaExports) != 1 {
		t.Fatalf("example configuration = %+v", config)
	}
}
