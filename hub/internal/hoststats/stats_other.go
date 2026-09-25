//go:build !windows

package hoststats

import (
	"context"
	"os/exec"
	"runtime"
)

func hideWindow(cmd *exec.Cmd) {}
func Collect(ctx context.Context) Snapshot {
	return Snapshot{OS: runtime.GOOS, Disks: []Disk{}, Warnings: []string{"Host CPU, memory and disk monitoring currently supports Windows"}}
}
