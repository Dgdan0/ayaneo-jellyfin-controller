//go:build windows

package repackage

import (
	"os/exec"
	"syscall"
)

const (
	belowNormalPriorityClass = 0x00004000
	createNoWindow           = 0x08000000
)

// lowPriority runs ffmpeg below normal priority and without a console window: a
// repackage is long and must not starve Jellyfin's own streaming on the same PC.
func lowPriority(command *exec.Cmd) {
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: belowNormalPriorityClass | createNoWindow}
}
