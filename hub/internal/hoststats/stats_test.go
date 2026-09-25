package hoststats

import (
	"strings"
	"testing"
)

func TestWindowsCPUIncludesIdleInKernelTime(t *testing.T) {
	value := CPUUsage(100, 200, 100, 150, 280, 120)
	if value == nil || *value != 50 {
		t.Fatalf("expected 50%%, got %v", value)
	}
	if CPUUsage(100, 200, 100, 50, 280, 120) != nil {
		t.Fatal("counter reset must not show a false percentage")
	}
	if CPUUsage(0, 0, 0, 0, 0, 0) != nil {
		t.Fatal("zero observation interval must be unknown")
	}
}
func TestDockerRowsDecodeWithoutInventingHealth(t *testing.T) {
	rows, err := parseContainers(strings.NewReader("{\"name\":\"one\",\"state\":\"running\",\"status\":\"Up 2 days\"}\n{\"name\":\"two\",\"state\":\"exited\",\"status\":\"Exited (1)\"}\n"))
	if err != nil || len(rows) != 2 || rows[0].Status != "Up 2 days" || rows[1].State != "exited" {
		t.Fatalf("%+v %v", rows, err)
	}
	if _, err = parseContainers(strings.NewReader("not JSON")); err == nil {
		t.Fatal("invalid Docker output must be reported")
	}
}
