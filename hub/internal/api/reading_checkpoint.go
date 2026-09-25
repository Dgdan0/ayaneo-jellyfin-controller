package api

import (
	"encoding/json"
	"hash/fnv"
	"reflect"
	"sync"
)

// Bounded stripes serialize Hub writers for the same upstream edition, including
// collection/child aliases. Upstream clients still retain their own conflict rules.
var readingCheckpointLocks [64]sync.Mutex

func lockReadingCheckpoint(source, edition string) func() {
	h := fnv.New32a()
	_, _ = h.Write([]byte(source + ":" + edition))
	lock := &readingCheckpointLocks[h.Sum32()%uint32(len(readingCheckpointLocks))]
	lock.Lock()
	return lock.Unlock
}

func sameReadingLocator(a, b json.RawMessage) bool {
	var left, right any
	if len(a) > 0 && json.Unmarshal(a, &left) != nil {
		return false
	}
	if len(b) > 0 && json.Unmarshal(b, &right) != nil {
		return false
	}
	return reflect.DeepEqual(left, right)
}
