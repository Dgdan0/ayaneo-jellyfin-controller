package hoststats

import (
	"context"
	"fmt"
	"os/exec"
	"syscall"
	"time"
	"unsafe"
)

var kernel = syscall.NewLazyDLL("kernel32.dll")
var systemTimes = kernel.NewProc("GetSystemTimes")
var memoryStatus = kernel.NewProc("GlobalMemoryStatusEx")
var diskSpace = kernel.NewProc("GetDiskFreeSpaceExW")
var logicalDrives = kernel.NewProc("GetLogicalDrives")
var driveType = kernel.NewProc("GetDriveTypeW")
var tickCount = kernel.NewProc("GetTickCount64")

func hideWindow(cmd *exec.Cmd) { cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true} }
func times() (idle, kern, user uint64, ok bool) {
	result, _, _ := systemTimes.Call(uintptr(unsafe.Pointer(&idle)), uintptr(unsafe.Pointer(&kern)), uintptr(unsafe.Pointer(&user)))
	return idle, kern, user, result != 0
}
func Collect(ctx context.Context) Snapshot {
	result := Snapshot{OS: "Windows", Disks: []Disk{}, Warnings: []string{}}
	var memory struct {
		Length, Load                                                                         uint32
		Total, Available, PageTotal, PageAvailable, VirtualTotal, VirtualAvailable, Extended uint64
	}
	memory.Length = uint32(unsafe.Sizeof(memory))
	ok, _, _ := memoryStatus.Call(uintptr(unsafe.Pointer(&memory)))
	if ok != 0 {
		result.MemoryTotalBytes = memory.Total
		result.MemoryAvailableBytes = memory.Available
	} else {
		result.Warnings = append(result.Warnings, "Windows memory statistics unavailable")
	}
	ticks, _, _ := tickCount.Call()
	result.UptimeSeconds = uint64(ticks) / 1000
	drives, _, _ := logicalDrives.Call()
	for i := 0; i < 26; i++ {
		if drives&(1<<i) == 0 {
			continue
		}
		name := fmt.Sprintf("%c:\\", 'A'+i)
		path, _ := syscall.UTF16PtrFromString(name)
		kind, _, _ := driveType.Call(uintptr(unsafe.Pointer(path)))
		if kind != 3 {
			continue
		}
		var available, total, free uint64
		success, _, _ := diskSpace.Call(uintptr(unsafe.Pointer(path)), uintptr(unsafe.Pointer(&available)), uintptr(unsafe.Pointer(&total)), uintptr(unsafe.Pointer(&free)))
		if success != 0 {
			result.Disks = append(result.Disks, Disk{Name: name, TotalBytes: total, AvailableBytes: available})
		} else {
			result.Warnings = append(result.Warnings, name+" space unavailable")
		}
	}
	idle, kern, user, firstOK := times()
	timer := time.NewTimer(250 * time.Millisecond)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return result
	case <-timer.C:
	}
	idle2, kern2, user2, secondOK := times()
	if firstOK && secondOK {
		result.CPUPercent = CPUUsage(idle, kern, user, idle2, kern2, user2)
	}
	if result.CPUPercent == nil {
		result.Warnings = append(result.Warnings, "Windows CPU sample unavailable")
	}
	return result
}
