package kavita

import (
	"ayaneohub/internal/config"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestReadingListsIncludesUnpromotedAcrossPagesAndKeepsIssueOrder(t *testing.T) {
	calls := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Api-Key") != "key" {
			t.Error("missing authentication")
		}
		switch r.URL.Path {
		case "/api/readinglist/lists":
			calls++
			body, _ := io.ReadAll(r.Body)
			if r.Method != "POST" || string(body) != "{}" || r.URL.Query().Get("includePromoted") != "true" || r.URL.Query().Get("sortByLastModified") != "false" || r.URL.Query().Get("itemsPerPage") != "100" {
				t.Errorf("unexpected request: %s %s %s", r.Method, r.URL, string(body))
			}
			w.Header().Set("Pagination", `{"totalPages":2}`)
			items := []ReadingList{}
			if r.URL.Query().Get("pageNumber") == "1" {
				for n := 100; n >= 1; n-- {
					items = append(items, ReadingList{ID: n, Title: fmt.Sprintf("Year %04d", n), Promoted: false})
				}
			} else {
				items = append(items, ReadingList{ID: 101, Title: "Year 0000"})
			}
			json.NewEncoder(w).Encode(items)
		case "/api/readinglist/items":
			if r.Method != "GET" || r.URL.Query().Get("readingListId") != "19" {
				t.Error("unexpected items request")
			}
			io.WriteString(w, `[{"id":2,"order":1,"seriesId":8,"chapterId":9},{"id":1,"order":0,"seriesId":3,"chapterId":4}]`)
		default:
			t.Errorf("unexpected endpoint %s", r.URL)
		}
	}))
	defer upstream.Close()
	c, _ := New(config.ServiceConfig{BaseURL: upstream.URL, APIKey: config.Secret("key")})
	lists, err := c.ReadingLists(context.Background())
	if err != nil || len(lists) != 101 || calls != 2 {
		t.Fatalf("lists=%d calls=%d err=%v", len(lists), calls, err)
	}
	if lists[0].ID != 101 || lists[1].ID != 1 || lists[1].Promoted {
		t.Fatalf("order/filter = %+v", lists[:2])
	}
	items, err := c.ReadingListItems(context.Background(), 19)
	if err != nil || len(items) != 2 || items[0].ChapterID != 4 || items[1].ChapterID != 9 {
		t.Fatalf("items=%+v err=%v", items, err)
	}
	if _, err = c.ReadingListItems(context.Background(), 0); err == nil {
		t.Fatal("accepted invalid id")
	}
}
