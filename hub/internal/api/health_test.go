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

func TestProwlarrAndReadarrUseTheirSystemStatusEndpoints(t *testing.T) {
	cases := map[string]string{
		"prowlarr": "/api/v1/system/status",
		"readarr":  "/api/v1/system/status",
	}
	for name, wantPath := range cases {
		spec, ok := probes[name]
		if !ok {
			t.Fatalf("no probe for %s", name)
		}
		if spec.path != wantPath || spec.authHeader != "X-Api-Key" || spec.versionField != "version" {
			t.Fatalf("probe for %s = %+v", name, spec)
		}
	}
}

func TestBookKeeprrUsesItsUnauthenticatedHealthEndpoint(t *testing.T) {
	spec, ok := probes["bookkeeprr"]
	if !ok {
		t.Fatal("no probe for bookkeeprr")
	}
	if spec.path != "/api/health" || spec.authHeader != "" || spec.authQuery != "" {
		t.Fatalf("probe for bookkeeprr = %+v", spec)
	}
}

func TestReadingServicesUseUnauthenticatedLivenessEndpoints(t *testing.T) {
	cases := map[string]string{
		"kavita":      "/api/health",
		"storyteller": "/api/v2/auth/providers",
	}
	for name, path := range cases {
		spec, ok := probes[name]
		if !ok {
			t.Fatalf("no probe for %s", name)
		}
		if spec.path != path || spec.authHeader != "" || spec.authQuery != "" {
			t.Fatalf("probe for %s = %+v", name, spec)
		}
	}
}
