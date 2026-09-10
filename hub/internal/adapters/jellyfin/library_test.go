package jellyfin

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"ayaneohub/internal/config"
)

func libraryTestClient(t *testing.T, handler http.HandlerFunc) *Client {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client, err := New(config.ServiceConfig{
		BaseURL: server.URL, APIKey: config.Secret("test-key"), UserID: "user-1",
	})
	if err != nil {
		t.Fatal(err)
	}
	return client
}

func TestItemQueryCarriesUserAndFields(t *testing.T) {
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/Items/0123456789abcdef0123456789abcdef" {
			t.Errorf("path = %q", r.URL.Path)
		}
		if r.URL.Query().Get("userId") != "user-1" || r.URL.Query().Get("fields") == "" {
			t.Errorf("query = %v", r.URL.Query())
		}
		_, _ = w.Write([]byte(`{"Id":"0123456789abcdef0123456789abcdef","Type":"Movie"}`))
	})
	if _, err := client.Item(context.Background(), "0123456789abcdef0123456789abcdef"); err != nil {
		t.Fatal(err)
	}
}

func TestImagesUsesNativeItemRoute(t *testing.T) {
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/Items/0123456789abcdef0123456789abcdef/Images" {
			t.Errorf("path = %q", r.URL.Path)
		}
		_, _ = w.Write([]byte(`[{"ImageType":"Primary","Path":"C:\\Media\\folder.jpg","Width":640,"Height":360}]`))
	})
	images, err := client.Images(context.Background(), "0123456789abcdef0123456789abcdef")
	if err != nil {
		t.Fatal(err)
	}
	if len(images) != 1 || images[0].Path == "" || images[0].Width != 640 {
		t.Fatalf("images = %+v", images)
	}
}

func TestSeasonsAndEpisodesUseNativeRoutesAndPaging(t *testing.T) {
	calls := 0
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		switch calls {
		case 1:
			if r.URL.Path != "/Shows/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/Seasons" {
				t.Errorf("seasons path = %q", r.URL.Path)
			}
		case 2:
			if r.URL.Path != "/Shows/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/Episodes" {
				t.Errorf("episodes path = %q", r.URL.Path)
			}
			q := r.URL.Query()
			if q.Get("seasonId") != "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" ||
				q.Get("isMissing") != "false" || q.Get("limit") != "60" ||
				q.Get("startIndex") != "60" {
				t.Errorf("episodes query = %v", q)
			}
		}
		_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
	})
	if _, err := client.Seasons(context.Background(), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"); err != nil {
		t.Fatal(err)
	}
	if _, err := client.Episodes(context.Background(), "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 60, 60); err != nil {
		t.Fatal(err)
	}
}
