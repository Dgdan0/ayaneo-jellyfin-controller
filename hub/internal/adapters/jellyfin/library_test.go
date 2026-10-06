package jellyfin

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
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

// A person on a title is an item of their own, and Jellyfin hands over several
// items by id in one call: that is how a cast is looked up (#27).
func TestItemsCanBeAskedForByID(t *testing.T) {
	const first = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	const second = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	var path string
	var query url.Values
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		path, query = r.URL.Path, r.URL.Query()
		_, _ = w.Write([]byte(`{"Items":[{"Id":"` + first + `","Name":"Daniel Radcliffe","Type":"Person",` +
			`"ProviderIds":{"Tmdb":"10980","Imdb":"nm0705356"}}],"TotalRecordCount":1}`))
	})
	page, err := client.Items(context.Background(), ItemsQuery{IDs: []string{first, second}, Fields: "ProviderIds"})
	if err != nil {
		t.Fatal(err)
	}
	if path != "/Items" || query.Get("ids") != first+","+second ||
		query.Get("fields") != "ProviderIds" || query.Get("userId") != "user-1" {
		t.Fatalf("asked %s?%v", path, query)
	}
	if len(page.Items) != 1 || page.Items[0].ProviderIds == nil || page.Items[0].ProviderIds.Tmdb != "10980" {
		t.Fatalf("the person came back as %+v", page.Items)
	}
}

func TestAQueryThatNamesNoIDsSendsNone(t *testing.T) {
	var query url.Values
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		query = r.URL.Query()
		_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
	})
	if _, err := client.Items(context.Background(), ItemsQuery{Types: "Movie", Recursive: true}); err != nil {
		t.Fatal(err)
	}
	if query.Has("ids") {
		t.Fatalf("a query that names no ids sent %q", query.Get("ids"))
	}
}

// An item read without a user is the server's own view of it, which is where a
// media file's path is dependably given (#5).
func TestItemAsServerAsksForTheItemWithNoUser(t *testing.T) {
	var query url.Values
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		query = r.URL.Query()
		_, _ = w.Write([]byte(`{"Items":[{"Id":"0123456789abcdef0123456789abcdef","Type":"Movie","MediaSources":[{"Id":"s1","Path":"E:/Films/a.mkv"}]}],"TotalRecordCount":1}`))
	})
	item, err := client.ItemAsServer(context.Background(), "0123456789abcdef0123456789abcdef")
	if err != nil {
		t.Fatal(err)
	}
	if query.Has("userId") || query.Get("ids") != "0123456789abcdef0123456789abcdef" || query.Get("fields") != "MediaSources,Path" {
		t.Fatalf("query = %v", query)
	}
	if len(item.MediaSources) != 1 || item.MediaSources[0].Path != "E:/Films/a.mkv" {
		t.Fatalf("item = %+v", item)
	}
}

func TestItemAsServerOfAnItemThatIsNotThereIsAnError(t *testing.T) {
	client := libraryTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"Items":[],"TotalRecordCount":0}`))
	})
	if _, err := client.ItemAsServer(context.Background(), "0123456789abcdef0123456789abcdef"); err == nil {
		t.Fatal("an empty answer was taken for an item")
	}
}
