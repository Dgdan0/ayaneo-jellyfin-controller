// Package hoststats collects read-only host information. It accepts no commands
// or filesystem paths from API callers.
package hoststats

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

type Disk struct {
	Name           string `json:"name"`
	TotalBytes     uint64 `json:"totalBytes"`
	AvailableBytes uint64 `json:"availableBytes"`
}
type Snapshot struct {
	OS                   string   `json:"os"`
	CPUPercent           *float64 `json:"cpuPercent"`
	MemoryTotalBytes     uint64   `json:"memoryTotalBytes"`
	MemoryAvailableBytes uint64   `json:"memoryAvailableBytes"`
	UptimeSeconds        uint64   `json:"uptimeSeconds"`
	Disks                []Disk   `json:"disks"`
	Warnings             []string `json:"warnings"`
}
type Container struct {
	Name   string `json:"name"`
	Image  string `json:"image"`
	State  string `json:"state"`
	Status string `json:"status"`
}

func CPUUsage(idleBefore, kernelBefore, userBefore, idleAfter, kernelAfter, userAfter uint64) *float64 {
	if idleAfter < idleBefore || kernelAfter < kernelBefore || userAfter < userBefore {
		return nil
	}
	total := kernelAfter - kernelBefore + userAfter - userBefore
	idle := idleAfter - idleBefore
	if total == 0 || idle > total {
		return nil
	}
	value := 100 * float64(total-idle) / float64(total)
	return &value
}

func Containers(ctx context.Context) ([]Container, error) {
	binary, err := exec.LookPath("docker")
	if err != nil && runtime.GOOS == "windows" {
		binary = filepath.Join(os.Getenv("ProgramFiles"), "Docker", "Docker", "resources", "bin", "docker.exe")
		_, err = os.Stat(binary)
	}
	if err != nil {
		return nil, fmt.Errorf("Docker CLI is unavailable to the Hub service")
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	// Select just four fields: labels, command lines and mount paths can contain private details.
	cmd := exec.CommandContext(ctx, binary, "ps", "--all", "--format", `{"name":{{json .Names}},"image":{{json .Image}},"state":{{json .State}},"status":{{json .Status}}}`)
	hideWindow(cmd)
	pipe, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err = cmd.Start(); err != nil {
		return nil, fmt.Errorf("Docker could not be queried by the Hub service")
	}
	rows, parseErr := parseContainers(io.LimitReader(pipe, 1<<20))
	if parseErr != nil {
		_ = cmd.Process.Kill()
	}
	err = cmd.Wait()
	if parseErr != nil || err != nil {
		return nil, fmt.Errorf("Docker is unavailable to the Hub service account")
	}
	return rows, nil
}
func parseContainers(reader io.Reader) ([]Container, error) {
	rows := []Container{}
	scanner := bufio.NewScanner(reader)
	for scanner.Scan() {
		if len(rows) >= 200 {
			return nil, fmt.Errorf("too many containers")
		}
		var row Container
		if err := json.Unmarshal(scanner.Bytes(), &row); err != nil {
			return nil, err
		}
		rows = append(rows, row)
	}
	return rows, scanner.Err()
}
