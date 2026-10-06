//go:build !windows

package repackage

// diskFree is not known off Windows, where the hub does not run; a cache there
// is bounded by its cap alone.
func diskFree(dir string) (uint64, bool) { return 0, false }
