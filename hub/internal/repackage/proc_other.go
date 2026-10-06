//go:build !windows

package repackage

import "os/exec"

func lowPriority(command *exec.Cmd) {}
