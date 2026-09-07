// Command hub is the media-stack aggregator that the Pocket DS talks to.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"ayaneohub/internal/api"
	"ayaneohub/internal/config"
)

func main() {
	configPath := flag.String("config", "", "path to hub.yaml (default: %ProgramData%\\AyaneoHub\\hub.yaml, then ./hub.yaml)")
	checkOnly := flag.Bool("check", false, "validate the config and exit")
	flag.Parse()

	path := resolveConfigPath(*configPath)
	cfg, err := config.Load(path)
	if err != nil {
		// A misconfigured service restarted by a supervisor will fail forever.
		// EX_CONFIG says "this will not fix itself" so the restart loop stops,
		// and the message says what to change.
		fmt.Fprintf(os.Stderr, "\n%v\n\n", err)
		fmt.Fprintf(os.Stderr, "config file: %s\n", path)
		os.Exit(config.ExitConfig)
	}

	setupLogging(cfg.Log.Level)
	if *checkOnly {
		fmt.Printf("config OK: %s\n", path)
		fmt.Printf("  listen   : %s\n", cfg.Server.Listen)
		fmt.Printf("  tokens   : %d\n", len(cfg.Auth.Tokens))
		fmt.Printf("  services : %v\n", cfg.EnabledServices())
		return
	}

	if err := run(cfg); err != nil {
		slog.Error("hub stopped", "error", err)
		os.Exit(1)
	}
}

func run(cfg *config.Config) error {
	// Cancelled on shutdown, which stops the background sweeper.
	background, stopBackground := context.WithCancel(context.Background())
	defer stopBackground()

	server := api.NewServer(cfg)
	// The Jellyfin provider-id index sweeps on a timer. Started before the
	// listener so the first request is not answered from an empty index.
	server.StartBackground(background)
	httpServer := &http.Server{
		Addr:    cfg.Server.Listen,
		Handler: server.Handler(),
		// Generous read and write budgets, tight header budget: the clients are
		// on a phone connection, but a slowloris should not tie up a goroutine.
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      120 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	slog.Info("hub starting",
		"version", api.Version,
		"listen", cfg.Server.Listen,
		"tokens", len(cfg.Auth.Tokens),
		"services", cfg.EnabledServices(),
	)

	errs := make(chan error, 1)
	go func() {
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			errs <- err
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	select {
	case err := <-errs:
		return err
	case sig := <-stop:
		slog.Info("shutting down", "signal", sig.String())
	}

	// Let in-flight requests finish rather than cutting the handheld off
	// mid-response during a service restart.
	ctx, cancel := context.WithTimeout(
		context.Background(), cfg.Server.ShutdownGrace.OrDefault(10*time.Second))
	defer cancel()
	return httpServer.Shutdown(ctx)
}

// resolveConfigPath prefers the flag, then the machine-wide location, then the
// working directory -- so a service and a hand-run binary find the same file.
func resolveConfigPath(flagValue string) string {
	if flagValue != "" {
		return flagValue
	}
	if programData := os.Getenv("ProgramData"); programData != "" {
		candidate := filepath.Join(programData, "AyaneoHub", "hub.yaml")
		if _, err := os.Stat(candidate); err == nil {
			return candidate
		}
	}
	return "hub.yaml"
}

func setupLogging(level string) {
	var l slog.Level
	switch level {
	case "debug":
		l = slog.LevelDebug
	case "warn":
		l = slog.LevelWarn
	case "error":
		l = slog.LevelError
	default:
		l = slog.LevelInfo
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: l})))
}
