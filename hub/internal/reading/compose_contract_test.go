package reading

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

type composeContract struct {
	Services map[string]struct {
		Image   string   `yaml:"image"`
		Restart string   `yaml:"restart"`
		Ports   []string `yaml:"ports"`
		Volumes []string `yaml:"volumes"`
		Secrets []string `yaml:"secrets"`
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

	for _, name := range []string{"kavita", "storyteller", "bookkeeprr"} {
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
	if len(document.Secrets) == 0 || len(document.Services["storyteller"].Secrets) == 0 {
		t.Fatal("Storyteller secret is not file-backed")
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
