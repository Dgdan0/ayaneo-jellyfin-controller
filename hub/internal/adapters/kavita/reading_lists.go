package kavita

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"sort"
	"strconv"
	"strings"
)

type ReadingList struct {
	ID        int    `json:"id"`
	Title     string `json:"title"`
	Summary   string `json:"summary"`
	Promoted  bool   `json:"promoted"`
	ItemCount int    `json:"itemCount"`
}

type ReadingListItem struct {
	ID            int    `json:"id"`
	Order         int    `json:"order"`
	ChapterID     int    `json:"chapterId"`
	SeriesID      int    `json:"seriesId"`
	SeriesName    string `json:"seriesName"`
	LibraryID     int    `json:"libraryId"`
	LibraryType   int    `json:"libraryType"`
	Title         string `json:"title"`
	ChapterNumber string `json:"chapterNumber"`
	VolumeNumber  string `json:"volumeNumber"`
	PagesRead     int    `json:"pagesRead"`
	PagesTotal    int    `json:"pagesTotal"`
}

// includePromoted includes additional public lists; it is not a promoted-only filter.
func (c *Client) ReadingLists(ctx context.Context) ([]ReadingList, error) {
	out := []ReadingList{}
	seen := map[int]bool{}
	for page := 1; page <= 100; page++ {
		query := url.Values{"includePromoted": {"true"}, "sortByLastModified": {"false"}, "pageNumber": {strconv.Itoa(page)}, "itemsPerPage": {"100"}}
		var items []ReadingList
		headers, err := c.base.PostJSONHeaders(ctx, "/api/readinglist/lists", query, struct{}{}, &items)
		if err != nil {
			return nil, err
		}
		for _, item := range items {
			if !seen[item.ID] {
				out = append(out, item)
				seen[item.ID] = true
			}
		}
		meta := pagination{}
		if raw := headers.Get("Pagination"); raw != "" {
			if err = json.Unmarshal([]byte(raw), &meta); err != nil {
				return nil, fmt.Errorf("kavita: invalid list pagination: %w", err)
			}
		}
		if len(items) < 100 || (meta.TotalPages > 0 && page >= meta.TotalPages) {
			sort.SliceStable(out, func(i, j int) bool { return strings.ToLower(out[i].Title) < strings.ToLower(out[j].Title) })
			return out, nil
		}
	}
	return nil, fmt.Errorf("kavita: reading list pagination exceeded limit")
}

func (c *Client) ReadingListItems(ctx context.Context, id int) ([]ReadingListItem, error) {
	if id <= 0 {
		return nil, fmt.Errorf("kavita: invalid reading list")
	}
	out := []ReadingListItem{}
	err := c.base.GetJSON(ctx, "/api/readinglist/items", url.Values{"readingListId": {strconv.Itoa(id)}}, &out)
	if err != nil {
		return nil, err
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Order < out[j].Order })
	return out, nil
}
