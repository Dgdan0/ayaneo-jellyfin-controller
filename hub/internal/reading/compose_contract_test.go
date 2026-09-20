package reading

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

type composeContract struct {
	Services map[string]struct {
		Image       string            `yaml:"image"`
		Restart     string            `yaml:"restart"`
		Ports       []string          `yaml:"ports"`
		Volumes     []string          `yaml:"volumes"`
		Secrets     []string          `yaml:"secrets"`
		Environment map[string]string `yaml:"environment"`
		DependsOn   []string          `yaml:"depends_on"`
		Healthcheck struct {
			Test []string `yaml:"test"`
		} `yaml:"healthcheck"`
	} `yaml:"services"`
	Secrets map[string]struct {
		File string `yaml:"file"`
	} `yaml:"secrets"`
}

func TestReadingLabComposeIsSafeByDefault(t *testing.T) {
	composePath := filepath.Join("..", "..", "..", "deploy", "reading", "compose.yaml")
	raw, err := os.ReadFile(composePath)
	if err != nil {
		t.Fatal(err)
	}
	var document composeContract
	if err := yaml.Unmarshal(raw, &document); err != nil {
		t.Fatal(err)
	}

	for _, name := range []string{"kavita", "storyteller", "bookkeeprr", "qbittorrent", "qbittorrent-backend"} {
		service, ok := document.Services[name]
		if !ok {
			t.Errorf("missing %s service", name)
			continue
		}
		if service.Image == "" || strings.HasSuffix(service.Image, ":latest") {
			t.Errorf("%s image is not pinned: %q", name, service.Image)
		}
		if service.Restart != "unless-stopped" {
			t.Errorf("%s restart = %q", name, service.Restart)
		}
		for _, binding := range service.Ports {
			if !strings.HasPrefix(binding, "127.0.0.1:") {
				t.Errorf("%s exposes non-loopback port %q", name, binding)
			}
		}
	}

	for _, name := range []string{"kavita", "storyteller"} {
		for _, mount := range document.Services[name].Volumes {
			if strings.Contains(mount, "READING_MEDIA_ROOT") && !strings.HasSuffix(mount, ":ro") {
				t.Errorf("%s canonical media mount is writable: %q", name, mount)
			}
		}
	}
	bookkeeprrMounts := strings.Join(document.Services["bookkeeprr"].Volumes, "\n")
	qbitMounts := strings.Join(document.Services["qbittorrent-backend"].Volumes, "\n")
	if strings.Contains(bookkeeprrMounts, "READING_MEDIA_ROOT") {
		t.Errorf("bookkeeprr must not write to reader fixture media: %q", bookkeeprrMounts)
	}
	for name, mounts := range map[string]string{"bookkeeprr": bookkeeprrMounts, "qbittorrent": qbitMounts} {
		if !strings.Contains(mounts, "${READING_ACQUISITION_ROOT}:/media:rw") {
			t.Errorf("%s does not share the isolated acquisition root at /media: %q", name, mounts)
		}
	}
	qbit := document.Services["qbittorrent-backend"]
	if len(qbit.Ports) != 1 || qbit.Ports[0] != "127.0.0.1:${QBITTORRENT_WEBUI_PORT:-18080}:${QBITTORRENT_WEBUI_PORT:-18080}" {
		t.Errorf("qBittorrent must expose only its loopback Web UI: %+v", qbit.Ports)
	}
	if qbit.Environment["WEBUI_PORT"] != "${QBITTORRENT_WEBUI_PORT:-18080}" {
		t.Errorf("qBittorrent WEBUI_PORT must match both sides of the published port: %q", qbit.Environment["WEBUI_PORT"])
	}
	compat := document.Services["qbittorrent"]
	if len(compat.Ports) != 0 {
		t.Errorf("qBittorrent compatibility proxy must stay internal: %+v", compat.Ports)
	}
	if !containsString(compat.DependsOn, "qbittorrent-backend") {
		t.Errorf("qBittorrent compatibility proxy must depend on its backend: %+v", compat.DependsOn)
	}
	compatMounts := strings.Join(compat.Volumes, "\n")
	if !strings.Contains(compatMounts, "./qbt5-compat.Caddyfile:/etc/caddy/Caddyfile:ro") {
		t.Errorf("qBittorrent compatibility proxy config is not read-only: %q", compatMounts)
	}
	if len(document.Secrets) == 0 || len(document.Services["storyteller"].Secrets) == 0 {
		t.Fatal("Storyteller secret is not file-backed")
	}
	kavitaProbe := strings.Join(document.Services["kavita"].Healthcheck.Test, " ")
	if strings.Contains(kavitaProbe, "wget") || !strings.Contains(kavitaProbe, "/dev/tcp/127.0.0.1/5000") {
		t.Errorf("Kavita health check must use its available bash TCP probe: %q", kavitaProbe)
	}
}

func TestReadingLabExampleContainsNoRealSecretsOrProductionPaths(t *testing.T) {
	examplePath := filepath.Join("..", "..", "..", "deploy", "reading", ".env.example")
	raw, err := os.ReadFile(examplePath)
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	for _, forbidden := range []string{"10.100.102.8", "100.97.20.86", "myjellydan", "D:\\Media"} {
		if strings.Contains(strings.ToLower(text), strings.ToLower(forbidden)) {
			t.Errorf("example contains machine-specific value %q", forbidden)
		}
	}
}

func TestReadingLabImagesUseImmutableDigests(t *testing.T) {
	examplePath := filepath.Join("..", "..", "..", "deploy", "reading", ".env.example")
	raw, err := os.ReadFile(examplePath)
	if err != nil {
		t.Fatal(err)
	}
	values := map[string]string{}
	for _, line := range strings.Split(string(raw), "\n") {
		key, value, ok := strings.Cut(strings.TrimSpace(line), "=")
		if ok {
			values[key] = value
		}
	}
	digest := regexp.MustCompile(`@sha256:[0-9a-f]{64}$`)
	for _, key := range []string{"KAVITA_IMAGE", "STORYTELLER_IMAGE", "BOOKKEEPRR_IMAGE", "QBITTORRENT_IMAGE", "QBITTORRENT_COMPAT_IMAGE"} {
		if !digest.MatchString(values[key]) {
			t.Errorf("%s is not pinned to an immutable digest: %q", key, values[key])
		}
	}
}

func TestQbittorrentV5CompatibilityProxyRewritesOnlyRenamedControls(t *testing.T) {
	configPath := filepath.Join("..", "..", "..", "deploy", "reading", "qbt5-compat.Caddyfile")
	raw, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	for _, required := range []string{
		"@pause path /api/v2/torrents/pause",
		"rewrite @pause /api/v2/torrents/stop",
		"@resume path /api/v2/torrents/resume",
		"rewrite @resume /api/v2/torrents/start",
		"reverse_proxy qbittorrent-backend:18080",
		"header_up Host {upstream_hostport}",
	} {
		if !strings.Contains(text, required) {
			t.Errorf("compatibility proxy is missing %q", required)
		}
	}
	for _, forbidden := range []string{"0.0.0.0", "tls ", "handle_path"} {
		if strings.Contains(text, forbidden) {
			t.Errorf("compatibility proxy contains unsafe directive %q", forbidden)
		}
	}
}

func containsString(values []string, want string) bool {
	for _, value := range values {
		if value == want {
			return true
		}
	}
	return false
}

func TestReadingPublicKavitaProxyContract(t *testing.T) {
	configPath := filepath.Join("..", "..", "..", "deploy", "reading", "Caddyfile.public.example")
	raw, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	text := string(raw)
	lines := map[string]bool{}
	for _, line := range strings.Split(text, "\n") {
		lines[strings.TrimSpace(line)] = true
	}

	for _, required := range []string{
		"handle /kavita {",
		"redir * /kavita/ 308",
		"handle /kavita/* {",
		"handle /api/* {",
		"reverse_proxy 127.0.0.1:5000",
	} {
		if !lines[required] {
			t.Errorf("public proxy example is missing %q", required)
		}
	}
	for _, forbidden := range []string{
		"handle_path /kavita",
		"redir https://myjellydan.duckdns.org/kavita/",
		"127.0.0.1:8001",
		"127.0.0.1:3000",
	} {
		if strings.Contains(text, forbidden) {
			t.Errorf("public proxy example contains unsafe or path-stripping route %q", forbidden)
		}
	}
}
