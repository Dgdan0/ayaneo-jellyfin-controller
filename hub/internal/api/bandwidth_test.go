package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestBandwidthMutationsRequireControlAndValidateBodies(t *testing.T) {
	for _, control := range []bool{false, true} {
		cfg := libraryAPIConfig("", "")
		cfg.Auth.Tokens[0].Scopes = []string{"read"}
		if control {
			cfg.Auth.Tokens[0].Scopes = append(cfg.Auth.Tokens[0].Scopes, "control")
		}
		handler := NewServer(cfg).Handler()
		for _, payload := range []string{`{}`, `{"mode":"toggle"}`, `{"limitsFor":"normal","downloadBps":-1}`, `{"limitsFor":"normal","uploadBps":2147483648}`, `{"mode":"normal","shell":"x"}`, `{"mode":"normal"} {}`} {
			r := httptest.NewRequest(http.MethodPost, "/v1/downloads/bandwidth", strings.NewReader(payload))
			r.Header.Set("Authorization", "Bearer "+libraryTestToken)
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)
			want := 403
			if control {
				want = 400
			}
			if w.Code != want {
				t.Fatalf("control=%v payload=%s code=%d body=%s", control, payload, w.Code, w.Body.String())
			}
		}
	}
}
