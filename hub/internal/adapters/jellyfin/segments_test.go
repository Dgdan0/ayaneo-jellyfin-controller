package jellyfin

import (
	"context"
	"net/http"
	"testing"
)

func TestMediaSegmentsReadsJellyfinsQueryResult(t *testing.T) {
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/MediaSegments/0123456789abcdef0123456789abcdef" {
			t.Errorf("path = %q", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"Items":[{"Id":"a","Type":"Intro","StartTicks":300000000,"EndTicks":900000000},
			{"Id":"b","Type":"Outro","StartTicks":12000000000,"EndTicks":13000000000}],"TotalRecordCount":2,"StartIndex":0}`))
	})
	segments, err := client.MediaSegments(context.Background(), "0123456789abcdef0123456789abcdef")
	if err != nil {
		t.Fatal(err)
	}
	if len(segments) != 2 || segments[0].Type != "Intro" || segments[1].StartTicks != 12000000000 {
		t.Fatalf("segments = %+v", segments)
	}
}

func TestMediaSegmentsStillReadsABareArray(t *testing.T) {
	segments, err := decodeMediaSegments([]byte(`[{"Id":"a","Type":"Recap","StartTicks":0,"EndTicks":10}]`))
	if err != nil || len(segments) != 1 || segments[0].Type != "Recap" {
		t.Fatalf("segments = %+v, err = %v", segments, err)
	}
}
