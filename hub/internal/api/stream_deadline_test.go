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
	client := &http.Client{Timeout: 2 * time.Second}
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

// Over a real connection, behind the logging wrapper the hub puts on every
// request: a client that reads slowly but never stops keeps its transfer past
// the server's own write timeout, and one that stops is let go after the window
// rather than holding the handler (and the file it has open) for good.
func TestStalledTransferIsCutAfterItsWindowAndASlowOneIsNot(t *testing.T) {
	body := make([]byte, 12<<20)
	finished := make(chan error, 4)
	server := httptest.NewUnstartedServer(withLogging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		out, err := streamUntilStalled(w, stallPolicy{Window: 400 * time.Millisecond, Step: 64 << 10})
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Length", strconv.Itoa(len(body)))
		// Copied the way http.ServeContent copies, 32 KB at a time. A single huge
		// Write is another thing on Windows: the kernel takes it whole and the
		// deadline never has anything to cut.
		_, writeErr := io.CopyBuffer(out, io.LimitReader(bytes.NewReader(body), int64(len(body))), make([]byte, 32<<10))
		finished <- writeErr
	})))
	server.Config.WriteTimeout = 100 * time.Millisecond
	server.Config.ConnState = func(conn net.Conn, state http.ConnState) {
		// Small buffers, so a client that does not read blocks the handler soon
		// rather than after the kernel has swallowed the lot.
		if tcp, ok := conn.(*net.TCPConn); ok && state == http.StateNew {
			_ = tcp.SetWriteBuffer(16 << 10)
		}
	}
	server.Start()
	defer server.Close()

	open := func() (*http.Response, net.Conn) {
		dialer := &net.Dialer{}
		var conn net.Conn
		client := &http.Client{Transport: &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			c, err := dialer.DialContext(ctx, network, address)
			if tcp, ok := c.(*net.TCPConn); ok {
				_ = tcp.SetReadBuffer(16 << 10)
			}
			conn = c
			return c, err
		}}}
		response, err := client.Get(server.URL)
		if err != nil {
			t.Fatal(err)
		}
		return response, conn
	}

	t.Run("a client that keeps reading, slowly, gets all of it", func(t *testing.T) {
		response, _ := open()
		defer response.Body.Close()
		started := time.Now()
		buffer := make([]byte, 256<<10)
		total := 0
		for total < len(body) {
			n, err := response.Body.Read(buffer)
			total += n
			if err != nil {
				break
			}
			// Far slower than the server's write timeout allows a transfer to take, never still.
			time.Sleep(15 * time.Millisecond)
		}
		if total != len(body) {
			t.Fatalf("read %d of %d bytes after %v", total, len(body), time.Since(started))
		}
		if err := <-finished; err != nil {
			t.Fatalf("the handler's copy failed: %v", err)
		}
		if elapsed := time.Since(started); elapsed < 250*time.Millisecond {
			t.Fatalf("the whole transfer took %v: it never had to wait for the client, so it proved nothing", elapsed)
		}
	})

	t.Run("a client that stops reading is cut after the window", func(t *testing.T) {
		response, conn := open()
		defer response.Body.Close()
		defer conn.Close()
		first := make([]byte, 64<<10)
		if _, err := io.ReadFull(response.Body, first); err != nil {
			t.Fatal(err)
		}
		stopped := time.Now()
		select {
		case err := <-finished:
			if err == nil {
				t.Fatal("the handler finished without an error although the client had stopped reading")
			}
			if waited := time.Since(stopped); waited < 200*time.Millisecond || waited > 3*time.Second {
				t.Fatalf("the handler let go after %v; the window is 400 ms", waited)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("a client that stopped reading held the handler for five seconds")
		}
	})
}
