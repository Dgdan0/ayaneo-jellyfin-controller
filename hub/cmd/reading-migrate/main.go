package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"syscall"

	"ayaneohub/internal/adapters/komga"
	"ayaneohub/internal/reading"
	"gopkg.in/yaml.v3"
)

var exportIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`)

type commandConfig struct {
	SchemaVersion int                 `json:"schema_version" yaml:"schema_version"`
	Sources       []commandSource     `json:"sources" yaml:"sources"`
	Destinations  map[string]string   `json:"destinations" yaml:"destinations"`
	KomgaExports  []komgaExportConfig `json:"komga_exports,omitempty" yaml:"komga_exports,omitempty"`
}

type commandSource struct {
	ID   string `json:"id" yaml:"id"`
	Kind string `json:"kind" yaml:"kind"`
	Root string `json:"root" yaml:"root"`
}

type komgaExportConfig struct {
	ID          string `json:"id" yaml:"id"`
	BaseURL     string `json:"base_url" yaml:"base_url"`
	UsernameEnv string `json:"username_env" yaml:"username_env"`
	PasswordEnv string `json:"password_env" yaml:"password_env"`
}

type labelledKomgaExport struct {
	ID    string                   `json:"id"`
	State komga.ReadingStateExport `json:"state"`
}

type komgaExportBundle struct {
	SchemaVersion int                   `json:"schemaVersion"`
	Exports       []labelledKomgaExport `json:"exports"`
}

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := run(ctx, os.Args[1:], os.Stdout, os.Getenv, http.DefaultClient); err != nil {
		fmt.Fprintln(os.Stderr, "reading-migrate:", err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string, stdout io.Writer, getenv func(string) string, httpClient *http.Client) error {
	flags := flag.NewFlagSet("reading-migrate", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	var configPath string
	var outputPath string
	flags.StringVar(&configPath, "config", "", "path to the migration YAML configuration")
	flags.StringVar(&outputPath, "out", "", "directory for the dry-run evidence bundle")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments")
	}
	if strings.TrimSpace(configPath) == "" || strings.TrimSpace(outputPath) == "" {
		return fmt.Errorf("both -config and -out are required")
	}

	configuration, err := loadCommandConfig(configPath)
	if err != nil {
		return err
	}
	migrationConfig, err := configuration.migrationConfig()
	if err != nil {
		return err
	}
	type preparedExport struct {
		id     string
		client *komga.Client
	}
	prepared := make([]preparedExport, 0, len(configuration.KomgaExports))
	seenExportIDs := make(map[string]bool)
	for _, exportConfig := range configuration.KomgaExports {
		exportConfig.ID = strings.TrimSpace(exportConfig.ID)
		if !exportIDPattern.MatchString(exportConfig.ID) {
			return fmt.Errorf("Komga export id %q is invalid", exportConfig.ID)
		}
		if seenExportIDs[strings.ToLower(exportConfig.ID)] {
			return fmt.Errorf("duplicate Komga export id %q", exportConfig.ID)
		}
		seenExportIDs[strings.ToLower(exportConfig.ID)] = true
		usernameName := strings.TrimSpace(exportConfig.UsernameEnv)
		passwordName := strings.TrimSpace(exportConfig.PasswordEnv)
		if usernameName == "" || passwordName == "" {
			return fmt.Errorf("Komga export %q requires username_env and password_env", exportConfig.ID)
		}
		username := getenv(usernameName)
		if username == "" {
			return fmt.Errorf("Komga export %q environment variable %s is empty", exportConfig.ID, usernameName)
		}
		password := getenv(passwordName)
		if password == "" {
			return fmt.Errorf("Komga export %q environment variable %s is empty", exportConfig.ID, passwordName)
		}
		client, err := komga.NewClient(exportConfig.BaseURL, username, password, httpClient)
		if err != nil {
			return fmt.Errorf("Komga export %q: %w", exportConfig.ID, err)
		}
		prepared = append(prepared, preparedExport{id: exportConfig.ID, client: client})
	}

	plan, err := reading.BuildMigrationPlan(ctx, migrationConfig)
	if err != nil {
		return err
	}
	exports := komgaExportBundle{SchemaVersion: 1}
	for _, item := range prepared {
		state, err := item.client.ExportReadingState(ctx)
		if err != nil {
			return fmt.Errorf("Komga export %q: %w", item.id, err)
		}
		exports.Exports = append(exports.Exports, labelledKomgaExport{ID: item.id, State: state})
	}
	sort.Slice(exports.Exports, func(i, j int) bool { return exports.Exports[i].ID < exports.Exports[j].ID })

	if err := reading.WriteMigrationBundle(outputPath, migrationConfig, plan); err != nil {
		return err
	}
	if len(exports.Exports) > 0 {
		if err := writeJSON(filepath.Join(outputPath, "komga-state.json"), exports); err != nil {
			return err
		}
	}
	_, err = fmt.Fprintf(stdout, "reading migration dry run complete: %d files, %d ready, %d conflicts, %d manual review; plan %s\n",
		plan.Counts.Files, plan.Counts.Ready, plan.Counts.Conflicts, plan.Counts.ManualReview, plan.Digest)
	return err
}

func loadCommandConfig(name string) (commandConfig, error) {
	file, err := os.Open(name)
	if err != nil {
		return commandConfig{}, fmt.Errorf("open migration config: %w", err)
	}
	defer file.Close()
	decoder := yaml.NewDecoder(file)
	decoder.KnownFields(true)
	var config commandConfig
	if err := decoder.Decode(&config); err != nil {
		return commandConfig{}, fmt.Errorf("decode migration config: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); err != nil && !errors.Is(err, io.EOF) {
		return commandConfig{}, fmt.Errorf("decode migration config: %w", err)
	} else if err == nil {
		return commandConfig{}, fmt.Errorf("migration config contains multiple documents")
	}
	if config.SchemaVersion != 1 {
		return commandConfig{}, fmt.Errorf("unsupported migration config schema %d", config.SchemaVersion)
	}
	return config, nil
}

func (config commandConfig) migrationConfig() (reading.MigrationConfig, error) {
	result := reading.MigrationConfig{
		SchemaVersion: config.SchemaVersion,
		Destinations:  make(map[reading.MediaKind]string, len(config.Destinations)),
	}
	for rawKind, root := range config.Destinations {
		kind, err := reading.ParseMediaKind(rawKind)
		if err != nil {
			return reading.MigrationConfig{}, err
		}
		result.Destinations[kind] = root
	}
	for _, source := range config.Sources {
		kind, err := reading.ParseMediaKind(source.Kind)
		if err != nil {
			return reading.MigrationConfig{}, fmt.Errorf("source %q: %w", source.ID, err)
		}
		result.Sources = append(result.Sources, reading.MigrationSource{ID: source.ID, Kind: kind, Root: source.Root})
	}
	return result, nil
}

func writeJSON(name string, value any) error {
	raw, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	raw = append(raw, '\n')
	temporary, err := os.CreateTemp(filepath.Dir(name), ".reading-export-*")
	if err != nil {
		return err
	}
	temporaryName := temporary.Name()
	cleanup := func() {
		_ = temporary.Close()
		_ = os.Remove(temporaryName)
	}
	if err := temporary.Chmod(0o600); err != nil {
		cleanup()
		return err
	}
	if _, err := temporary.Write(raw); err != nil {
		cleanup()
		return err
	}
	if err := temporary.Close(); err != nil {
		_ = os.Remove(temporaryName)
		return err
	}
	if err := os.Remove(name); err != nil && !os.IsNotExist(err) {
		_ = os.Remove(temporaryName)
		return err
	}
	if err := os.Rename(temporaryName, name); err != nil {
		_ = os.Remove(temporaryName)
		return err
	}
	return nil
}
