// Package wikidata resolves series membership when Open Library's own work
// records contain only a partial series tag. It returns identifiers and labels;
// Open Library remains the source for edition metadata and cover art.
package wikidata

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"time"
)

const defaultBaseURL = "https://query.wikidata.org"

var openLibraryWorkID = regexp.MustCompile(`^OL[0-9]+W$`)

type Client struct {
	baseURL string
	http    *http.Client
}

type Series struct {
	ID                     string
	Name                   string
	OpenLibraryWorkIDs     []string
	memberPublicationDates []string
}

func New(baseURL string) *Client {
	if strings.TrimSpace(baseURL) == "" {
		baseURL = defaultBaseURL
	}
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), http: &http.Client{Timeout: 15 * time.Second}}
}

func (c *Client) SeriesContainingOpenLibraryWork(ctx context.Context, workID string) ([]Series, error) {
	workID = strings.TrimSpace(strings.TrimPrefix(workID, "/works/"))
	if !openLibraryWorkID.MatchString(workID) {
		return nil, fmt.Errorf("wikidata: invalid Open Library work id")
	}
	query := `SELECT ?series ?seriesLabel ?book ?bookLabel ?date ?olid WHERE {
  ?candidate wdt:P648 "` + workID + `".
  ?series wdt:P527 ?candidate; wdt:P527 ?book.
  ?book wdt:P648 ?olid.
  FILTER(STRENDS(?olid, "W"))
  OPTIONAL { ?book wdt:P577 ?date. }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
} ORDER BY ?date ?bookLabel`
	values := url.Values{"query": {query}, "format": {"json"}}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/sparql?"+values.Encode(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/sparql-results+json")
	req.Header.Set("User-Agent", "AyaneoHub/0.3 (personal media server)")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("wikidata: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("wikidata: http %d", resp.StatusCode)
	}
	var payload struct {
		Results struct {
			Bindings []map[string]struct {
				Value string `json:"value"`
			} `json:"bindings"`
		} `json:"results"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, fmt.Errorf("wikidata: decoding response: %w", err)
	}
	byID := map[string]*Series{}
	order := []string{}
	seenMember := map[string]map[string]bool{}
	for _, row := range payload.Results.Bindings {
		seriesID := entityID(row["series"].Value)
		memberID := strings.TrimSpace(row["olid"].Value)
		if seriesID == "" || !openLibraryWorkID.MatchString(memberID) {
			continue
		}
		series := byID[seriesID]
		if series == nil {
			series = &Series{ID: seriesID, Name: strings.TrimSpace(row["seriesLabel"].Value)}
			byID[seriesID] = series
			seenMember[seriesID] = map[string]bool{}
			order = append(order, seriesID)
		}
		if seenMember[seriesID][memberID] {
			continue
		}
		seenMember[seriesID][memberID] = true
		series.OpenLibraryWorkIDs = append(series.OpenLibraryWorkIDs, memberID)
		series.memberPublicationDates = append(series.memberPublicationDates, row["date"].Value)
	}
	out := make([]Series, 0, len(order))
	for _, id := range order {
		series := byID[id]
		if len(series.OpenLibraryWorkIDs) < 2 {
			continue
		}
		indices := make([]int, len(series.OpenLibraryWorkIDs))
		for i := range indices {
			indices[i] = i
		}
		sort.SliceStable(indices, func(i, j int) bool {
			return series.memberPublicationDates[indices[i]] < series.memberPublicationDates[indices[j]]
		})
		ids, dates := make([]string, 0, len(indices)), make([]string, 0, len(indices))
		for _, index := range indices {
			ids = append(ids, series.OpenLibraryWorkIDs[index])
			dates = append(dates, series.memberPublicationDates[index])
		}
		series.OpenLibraryWorkIDs, series.memberPublicationDates = ids, dates
		out = append(out, *series)
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("wikidata: no series membership found")
	}
	return out, nil
}

func entityID(raw string) string {
	raw = strings.TrimRight(strings.TrimSpace(raw), "/")
	if index := strings.LastIndex(raw, "/"); index >= 0 {
		raw = raw[index+1:]
	}
	if len(raw) < 2 || raw[0] != 'Q' {
		return ""
	}
	for _, r := range raw[1:] {
		if r < '0' || r > '9' {
			return ""
		}
	}
	return raw
}
