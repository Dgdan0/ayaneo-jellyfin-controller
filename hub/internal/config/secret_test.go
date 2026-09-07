package config

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

const apiKey = "b7f3a1c99e2d4f8a1c0b5e6d7a8f9012"

func TestSecretRedactsThroughEveryFormattingVerb(t *testing.T) {
	// Each of these is a real way a credential escapes: %v in a wrapped error,
	// %s in a log line, %+v while debugging a config struct.
	s := Secret(apiKey)
	for _, format := range []string{"%s", "%v", "%+v", "%#v", "%q"} {
		got := fmt.Sprintf(format, s)
		if strings.Contains(got, apiKey) {
			t.Errorf("%s leaked the secret: %s", format, got)
		}
	}
}

func TestSecretRedactsInsideAStruct(t *testing.T) {
	// The common case: someone prints the whole config while chasing a bug.
	cfg := ServiceConfig{BaseURL: "http://localhost:8096", APIKey: Secret(apiKey)}
	got := fmt.Sprintf("%+v", cfg)
	if strings.Contains(got, apiKey) {
		t.Fatalf("struct formatting leaked the secret: %s", got)
	}
	if !strings.Contains(got, "http://localhost:8096") {
		t.Fatalf("redaction ate the non-secret fields too: %s", got)
	}
}

func TestSecretRedactsInJSON(t *testing.T) {
	out, err := json.Marshal(map[string]Secret{"key": Secret(apiKey)})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), apiKey) {
		t.Fatalf("json leaked the secret: %s", out)
	}
}

func TestSecretRedactsInYAML(t *testing.T) {
	out, err := yaml.Marshal(map[string]Secret{"key": Secret(apiKey)})
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), apiKey) {
		t.Fatalf("yaml leaked the secret: %s", out)
	}
}

func TestRevealReturnsTheRealValue(t *testing.T) {
	if got := Secret(apiKey).Reveal(); got != apiKey {
		t.Fatalf("Reveal() = %q, want the original", got)
	}
}

func TestIsSet(t *testing.T) {
	if Secret("").IsSet() {
		t.Error("empty secret reported as set")
	}
	if !Secret("x").IsSet() {
		t.Error("non-empty secret reported as unset")
	}
}
