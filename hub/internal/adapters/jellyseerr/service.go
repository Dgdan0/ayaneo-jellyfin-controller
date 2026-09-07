package jellyseerr

import (
	"context"
	"fmt"
)

// The Radarr/Sonarr settings Jellyseerr already knows about, which is what
// makes a request dialog possible without the hub talking to the *arrs itself.
//
// Measured on this install: Radarr has 7 quality profiles and 2 root folders
// (`E:\Videos\Daniel\Movies` and `E:\Videos\Daniel\Marvel\Movies`), Sonarr has
// 7 and 3 (TV Shows, Anime, Marvel T.V. Series). A separate Marvel folder means
// "where does this go?" is a real question on this stack rather than a
// hypothetical one.

type IDName struct {
	ID   int    `json:"id"`
	Name string `json:"name"`
}

type RootFolder struct {
	ID        int    `json:"id"`
	Path      string `json:"path"`
	FreeSpace int64  `json:"freeSpace"`
}

type Tag struct {
	ID    int    `json:"id"`
	Label string `json:"label"`
}

// ServiceServer is one configured *arr instance.
//
// Sonarr carries a second set of defaults for anime, which is a real
// distinction on this stack: the anime root folder is a different directory.
type ServiceServer struct {
	ID                   int    `json:"id"`
	Name                 string `json:"name"`
	Is4k                 bool   `json:"is4k"`
	IsDefault            bool   `json:"isDefault"`
	ActiveProfileID      int    `json:"activeProfileId"`
	ActiveDirectory      string `json:"activeDirectory"`
	ActiveAnimeProfileID int    `json:"activeAnimeProfileId"`
	ActiveAnimeDirectory string `json:"activeAnimeDirectory"`
	ActiveTags           []int  `json:"activeTags"`
}

type ServiceDetail struct {
	Server           ServiceServer `json:"server"`
	Profiles         []IDName      `json:"profiles"`
	RootFolders      []RootFolder  `json:"rootFolders"`
	Tags             []Tag         `json:"tags"`
	LanguageProfiles []IDName      `json:"languageProfiles"`
}

// Servers lists the configured instances of one service kind.
//
// @param kind "radarr" or "sonarr". Anything else is refused here rather than
// being pasted into a URL.
func (c *Client) Servers(ctx context.Context, kind string) ([]ServiceServer, error) {
	if err := checkServiceKind(kind); err != nil {
		return nil, err
	}
	var out []ServiceServer
	if err := c.base.GetJSON(ctx, "/api/v1/service/"+kind, nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// ServiceDetail is the profiles, root folders and tags for one instance.
func (c *Client) ServiceDetail(ctx context.Context, kind string, id int) (*ServiceDetail, error) {
	if err := checkServiceKind(kind); err != nil {
		return nil, err
	}
	out := &ServiceDetail{}
	path := fmt.Sprintf("/api/v1/service/%s/%d", kind, id)
	if err := c.base.GetJSON(ctx, path, nil, out); err != nil {
		return nil, err
	}
	return out, nil
}

func checkServiceKind(kind string) error {
	if kind != "radarr" && kind != "sonarr" {
		return fmt.Errorf("unknown service kind %q", kind)
	}
	return nil
}
