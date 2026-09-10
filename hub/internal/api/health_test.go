package api

import (
	"testing"

	"ayaneohub/internal/config"
)

func TestServiceDashboardURLPrefersExplicitBrowserAddress(t *testing.T) {
	svc := config.ServiceConfig{
		BaseURL: "http://127.0.0.1:7878",
		WebURL:  "https://media.example.test/radarr/?from=manage#queue",
	}
	if got, want := serviceDashboardURL(svc), "https://media.example.test/radarr"; got != want {
		t.Fatalf("serviceDashboardURL() = %q, want %q", got, want)
	}
}

func TestServiceDashboardURLSanitizesFallback(t *testing.T) {
	svc := config.ServiceConfig{BaseURL: "http://admin:secret@127.0.0.1:8080/ui/?token=nope#top"}
	if got, want := serviceDashboardURL(svc), "http://127.0.0.1:8080/ui"; got != want {
		t.Fatalf("serviceDashboardURL() = %q, want %q", got, want)
	}
}
