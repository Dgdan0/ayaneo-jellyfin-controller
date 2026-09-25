package api

import (
	"errors"
	"net/http"
	"time"
)

// JSON responses keep the server's ordinary write budget. Authenticated media
// and EPUB transfers may take much longer over a remote connection.
func prepareLongStream(w http.ResponseWriter) error {
	err := http.NewResponseController(w).SetWriteDeadline(time.Time{})
	if errors.Is(err, http.ErrNotSupported) {
		// ResponseRecorder in handler tests has no network write deadline.
		return nil
	}
	return err
}
