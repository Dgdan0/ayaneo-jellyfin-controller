package httpx

import (
	"net/url"
	"strings"
	"testing"
)

func TestEncodeQuerySpacesAsPercent20(t *testing.T) {
	// Jellyseerr answers a bare 400 to the plus form. Every single-word search
	// worked and every multi-word one failed, which looked like a hub bug for a
	// long time and was a space.
	got := encodeQuery(url.Values{"query": {"gran torino"}})
	if got != "query=gran%20torino" {
		t.Fatalf("got %q, want query=gran%%20torino", got)
	}
	if strings.Contains(got, "+") {
		t.Fatalf("%q still contains a plus", got)
	}
}

func TestEncodeQueryKeepsLiteralPlus(t *testing.T) {
	// The substitution is only safe because QueryEscape writes a literal plus
	// as %2B, so any '+' left in the output is always an encoded space. If that
	// ever stopped being true, this test is where it would show up.
	got := encodeQuery(url.Values{"q": {"c++ and rust"}})
	if got != "q=c%2B%2B%20and%20rust" {
		t.Fatalf("got %q", got)
	}
}

func TestEncodeQueryOrdersKeysStably(t *testing.T) {
	// url.Values.Encode sorts by key, which is what makes a URL usable as a
	// cache key.
	got := encodeQuery(url.Values{"b": {"2"}, "a": {"1"}})
	if got != "a=1&b=2" {
		t.Fatalf("got %q", got)
	}
}

func TestEncodeQueryEmpty(t *testing.T) {
	if got := encodeQuery(url.Values{}); got != "" {
		t.Fatalf("got %q, want empty", got)
	}
}

func TestEncodeQueryEscapesReservedCharacters(t *testing.T) {
	got := encodeQuery(url.Values{"path": {`E:\Videos\Daniel\Movies`}})
	if strings.Contains(got, `\`) {
		t.Fatalf("%q left a backslash unescaped", got)
	}
}
