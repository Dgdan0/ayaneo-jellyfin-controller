//go:build windows

package repackage

import (
	"syscall"
	"unsafe"
)

var diskSpace = syscall.NewLazyDLL("kernel32.dll").NewProc("GetDiskFreeSpaceExW")

// diskFree is the space this process may still use on the drive holding dir.
func diskFree(dir string) (uint64, bool) {
	path, err := syscall.UTF16PtrFromString(dir)
	if err != nil {
		return 0, false
	}
	var available, total, free uint64
	result, _, _ := diskSpace.Call(
		uintptr(unsafe.Pointer(path)), uintptr(unsafe.Pointer(&available)),
		uintptr(unsafe.Pointer(&total)), uintptr(unsafe.Pointer(&free)))
	return available, result != 0
}
