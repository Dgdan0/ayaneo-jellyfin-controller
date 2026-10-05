package api

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

// An aligned EPUB can take longer than the JSON response budget on a remote link.
// Exercise a real TCP server and the logging ResponseWriter wrapper.
func TestStreamingResponseOutlivesServerWriteTimeout(t *testing.T) {
	server := httptest.NewUnstartedServer(withLogging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if err := prepareLongStream(w); err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Type", "application/epub+zip")
		w.Header().Set("Content-Length", "2")
		_, _ = io.WriteString(w, "a")
		time.Sleep(200 * time.Millisecond)
		_, _ = io.WriteString(w, "b")
	})))
	server.Config.WriteTimeout = 50 * time.Millisecond
	server.Start()
	defer server.Close()
	client := &http.Client{Timeout: patient}
	response, err := client.Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != http.StatusOK || string(body) != "ab" {
		t.Fatalf("slow EPUB stream = %d %q, read error %v", response.StatusCode, body, err)
	}
}

// The deadline is the server's, so it is armed through a seam: the writer is
// given the function that sets it and the clock, and these tests watch both.
func TestStallWriterArmsItsWindowAtOnceAndAgainEveryStepWritten(t *testing.T) {
	now := time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)
	var armed []time.Time
	recorder := httptest.NewRecorder()
	writer := &stallWriter{
		ResponseWriter: recorder, policy: stallPolicy{Window: 5 * time.Minute, Step: 128 << 10},
		now: func() time.Time { return now },
		set: func(deadline time.Time) error { armed = append(armed, deadline); return nil },
	}
	if err := writer.arm(); err != nil {
		t.Fatal(err)
	}
	if len(armed) != 1 || !armed[0].Equal(now.Add(5*time.Minute)) {
		t.Fatalf("armed at the start = %v", armed)
	}

	chunk := make([]byte, 32<<10)
	for i := 1; i <= 8; i++ {
		now = now.Add(time.Second)
		if n, err := writer.Write(chunk); err != nil || n != len(chunk) {
			t.Fatalf("write %d = %d, %v", i, n, err)
		}
		// 32 KB at a time: nothing until the fourth reaches 128 KB, then again at the eighth.
		want := 1 + i/4
		if len(armed) != want {
			t.Fatalf("after %d KB the deadline was armed %d times, want %d", i*32, len(armed), want)
		}
	}
	if last := armed[len(armed)-1]; !last.Equal(now.Add(5 * time.Minute)) {
		t.Fatalf("the last arming was for %v, want a window from the moment of the write, %v", last, now.Add(5*time.Minute))
	}
	if recorder.Body.Len() != 8*len(chunk) {
		t.Fatalf("%d bytes reached the client", recorder.Body.Len())
	}
}

func TestStallWriterPassesWriteErrorsAndFailedArmingThrough(t *testing.T) {
	failing := &stallWriter{
		ResponseWriter: httptest.NewRecorder(), policy: stallPolicy{Window: time.Second, Step: 4},
		now: time.Now, set: func(time.Time) error { return errors.New("the connection is gone") },
	}
	// A deadline that cannot be moved is not a reason to stop writing: the
	// transfer simply goes on under the one it has.
	if n, err := failing.Write([]byte("abcdefgh")); err != nil || n != 8 {
		t.Fatalf("write = %d, %v", n, err)
	}

	broken := &stallWriter{ResponseWriter: errWriter{}, policy: stallPolicy{Window: time.Second, Step: 4}, now: time.Now, set: func(time.Time) error { return nil }}
	if _, err := broken.Write([]byte("abc")); err == nil || err.Error() != "broken pipe" {
		t.Fatalf("write error = %v", err)
	}
}

type errWriter struct{ http.ResponseWriter }

func (errWriter) Header() http.Header       { return http.Header{} }
func (errWriter) WriteHeader(int)           {}
func (errWriter) Write([]byte) (int, error) { return 0, errors.New("broken pipe") }

func TestStreamUntilStalledLeavesAHandlerTestAloneAndKeepsTheControllerReachable(t *testing.T) {
	// A ResponseRecorder has no write deadline to move; the wrapper is a plain pass-through.
	recorder := httptest.NewRecorder()
	out, err := streamUntilStalled(recorder, defaultStallPolicy)
	if err != nil || out != http.ResponseWriter(recorder) {
		t.Fatalf("recorder: %v, wrapped = %v", err, out != http.ResponseWriter(recorder))
	}
	if defaultStallPolicy.Window != 5*time.Minute || defaultStallPolicy.Step != 128<<10 {
		t.Fatalf("the default is five minutes and 128 KB: %+v", defaultStallPolicy)
	}
}

// What follows runs the deadline over a real connection, behind the logging
// wrapper the hub puts on every request. The arithmetic is pinned above with an
// injected clock; these show that it reaches the socket. Real time cannot be
// avoided, so they assert on outcome and order: what a client that keeps reading
// receives, and that a client that stopped is let go at some point. A window is
// either far longer than any pause the test makes (so a busy machine cannot
// trip it) or the very thing being waited out (so a slow machine only waits
// longer).

// smallBuffers makes a connection block its writer soon, rather than after the
// kernel has taken the whole body.
func smallBuffers(conn net.Conn, state http.ConnState) {
	if tcp, ok := conn.(*net.TCPConn); ok && state == http.StateNew {
		_ = tcp.SetWriteBuffer(16 << 10)
	}
}

// slowReaderClient is a client whose connection holds little, so what the
// server writes is not swallowed before the test reads it.
func slowReaderClient() *http.Client {
	return &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		conn, err := (&net.Dialer{}).DialContext(ctx, network, address)
		if tcp, ok := conn.(*net.TCPConn); ok {
			_ = tcp.SetReadBuffer(16 << 10)
		}
		return conn, err
	}}}
}

// stallingServer writes size bytes the way http.ServeContent does, 32 KB at a
// time (a single huge Write is another thing on Windows: the kernel takes it
// whole and there is nothing for a deadline to cut), under a stall policy,
// with a server write timeout far shorter than the transfer.
func stallingServer(t *testing.T, policy stallPolicy, size int) (*httptest.Server, chan error) {
	t.Helper()
	body := make([]byte, size)
	finished := make(chan error, 2)
	server := httptest.NewUnstartedServer(withLogging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		out, err := streamUntilStalled(w, policy)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Length", strconv.Itoa(len(body)))
		_, writeErr := io.CopyBuffer(out, io.LimitReader(bytes.NewReader(body), int64(len(body))), make([]byte, 32<<10))
		finished <- writeErr
	})))
	server.Config.WriteTimeout = stallingServerWriteTimeout
	server.Config.ConnState = smallBuffers
	server.Start()
	t.Cleanup(server.Close)
	return server, finished
}

const stallingServerWriteTimeout = 100 * time.Millisecond

func TestSlowButMovingTransferOutlivesTheServersWriteTimeout(t *testing.T) {
	const size = 12 << 20
	// The window is far longer than the pause between the client's reads.
	server, finished := stallingServer(t, stallPolicy{Window: patient, Step: 64 << 10}, size)
	response, err := slowReaderClient().Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()

	buffer := make([]byte, 256<<10)
	total, reads := 0, 0
	for total < size {
		n, err := response.Body.Read(buffer)
		total += n
		reads++
		if err != nil {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	if total != size {
		t.Fatalf("read %d of %d bytes", total, size)
	}
	select {
	case err := <-finished:
		if err != nil {
			t.Fatalf("the handler's copy failed: %v", err)
		}
	case <-time.After(patient):
		t.Fatal("the handler never finished")
	}
	// The client paced itself, so the transfer lasted at least as long as its
	// sleeps (a sleep cannot return early, however idle the machine), and that is
	// longer than the server's own write timeout would have let it. Had the
	// timeout applied, the server would have given up on the blocked write long
	// before the last read.
	if pacing := time.Duration(reads-1) * 5 * time.Millisecond; pacing <= stallingServerWriteTimeout {
		t.Fatalf("%d reads is too few to outlive the server's %v write timeout: the test proved nothing", reads, stallingServerWriteTimeout)
	}
}

func TestTransferToAClientThatStoppedReadingIsCutAtSomePoint(t *testing.T) {
	// A short window is the thing waited out; the test waits as long as it takes.
	server, finished := stallingServer(t, stallPolicy{Window: 300 * time.Millisecond, Step: 64 << 10}, 12<<20)
	response, err := slowReaderClient().Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if _, err := io.ReadFull(response.Body, make([]byte, 64<<10)); err != nil {
		t.Fatal(err)
	}
	// Nothing more is read. The connection stays open and quiet, as a phone that
	// has gone to sleep leaves it.
	select {
	case err := <-finished:
		if err == nil {
			t.Fatal("the handler finished without an error although the client had stopped reading")
		}
	case <-time.After(patient):
		t.Fatal("a client that stopped reading held the handler for good")
	}
}
