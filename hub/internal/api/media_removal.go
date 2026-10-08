package api

import (
	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/adapters/storyteller"
	readingdomain "ayaneohub/internal/reading"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type removalRequest struct {
	Kind string `json:"kind"`
	ID   string `json:"id"`
}
type removalPlan struct {
	Title     string
	Files     []readingdomain.RemovalFile
	Names     []string
	Sources   []readingdomain.SourceRef
	Signature string
	Video     *jellyfin.Client
}
type removalTicket struct {
	Owner   string
	Request removalRequest
	Plan    removalPlan
	Expires time.Time
}
type removalPreview struct {
	Ticket      string   `json:"ticket"`
	Title       string   `json:"title"`
	Description string   `json:"description"`
	Files       []string `json:"files"`
	FileCount   int      `json:"fileCount"`
}

func (s *Server) handleRemovalPreview(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	var body removalRequest
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body) != nil {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "Invalid removal request"})
		return
	}
	if body.Kind == "reading" && !s.requireReading(w, r) {
		return
	}
	ctx, cancel := timeoutFor(r, 45*time.Second)
	defer cancel()
	plan, err := s.buildRemovalPlan(ctx, w, r, body)
	if err != nil {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: err.Error()})
		return
	}
	if plan == nil {
		return
	}
	bytes := make([]byte, 32)
	if _, err = rand.Read(bytes); err != nil {
		writeError(w, r, 500, Error{Code: CodeUpstreamDown, Message: "Could not prepare deletion"})
		return
	}
	ticket := hex.EncodeToString(bytes)
	s.removalMu.Lock()
	for key, value := range s.removalTickets {
		if !value.Expires.After(time.Now()) {
			delete(s.removalTickets, key)
		}
	}
	if len(s.removalTickets) >= 100 {
		s.removalMu.Unlock()
		writeError(w, r, 429, Error{Code: CodeInvalidRequest, Message: "Too many pending confirmations"})
		return
	}
	s.removalTickets[ticket] = removalTicket{subtitleOwner(r, r.Header.Get(jellyfinUserHeader)), body, *plan, time.Now().Add(5 * time.Minute)}
	s.removalMu.Unlock()
	description := "Permanently delete these server files and remove the title from the library. Saved copies on this device remain."
	if body.Kind == "video" {
		description += " Jellyfin also removes associated subtitle and metadata files. Download managers may fetch monitored titles again."
	}
	if body.Kind == "reading" {
		description += " All listed editions/issues are included. Reading-list entries for deleted issues are also removed."
	}
	writeJSON(w, 200, removalPreview{ticket, plan.Title, description, plan.Names, len(plan.Names)})
}

func (s *Server) handleMediaRemove(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	var body struct {
		Ticket  string `json:"ticket"`
		Confirm bool   `json:"confirm"`
	}
	if json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&body) != nil || !body.Confirm {
		writeError(w, r, 400, Error{Code: CodeInvalidRequest, Message: "Explicit confirmation is required"})
		return
	}
	// Serialize removals and consume the ticket before any mutation. An uncertain
	// response must never cause an automatic second deletion.
	s.removalMu.Lock()
	defer s.removalMu.Unlock()
	ticket, ok := s.removalTickets[body.Ticket]
	if !ok || ticket.Owner != subtitleOwner(r, r.Header.Get(jellyfinUserHeader)) || !ticket.Expires.After(time.Now()) {
		writeError(w, r, 409, Error{Code: CodeInvalidRequest, Message: "Confirmation expired. Review the title again."})
		return
	}
	if ticket.Request.Kind == "reading" && !s.requireReading(w, r) {
		return
	}
	delete(s.removalTickets, body.Ticket)
	ctx, cancel := timeoutFor(r, 90*time.Second)
	defer cancel()
	fresh, err := s.buildRemovalPlan(ctx, w, r, ticket.Request)
	if err != nil || fresh == nil || fresh.Signature != ticket.Plan.Signature {
		writeError(w, r, 409, Error{Code: CodeInvalidRequest, Message: "The server files changed. Review the title again before deleting."})
		return
	}
	for _, file := range ticket.Plan.Files {
		if !file.Unchanged() {
			writeError(w, r, 409, Error{Code: CodeInvalidRequest, Message: "A file changed. Review the title again."})
			return
		}
	}
	if fresh.Video != nil {
		err = fresh.Video.DeleteItem(ctx, ticket.Request.ID)
	} else {
		// Resolve every file before deleting any; only regular files from the
		// confirmed plan are removed. Never recursively delete a book's directory.
		for _, file := range fresh.Files {
			if err = os.Remove(file.Path); err != nil {
				break
			}
		}
		if err == nil {
			for _, source := range fresh.Sources {
				switch source.Source {
				case "kavita":
					id, _ := strconv.Atoi(source.SourceID)
					err = s.kavita.DeleteSeries(ctx, id)
				case "storyteller":
					id, _ := strconv.ParseInt(source.SourceID, 10, 64)
					err = s.storyteller.DeleteBook(ctx, id)
				}
				if err != nil {
					break
				}
			}
		}
	}
	s.invalidateReadingCatalog()
	s.cache.InvalidatePrefix("library:")
	s.cache.InvalidatePrefix("home:")
	s.cache.InvalidatePrefix("media:")
	if err != nil {
		writeError(w, r, 502, Error{Code: CodeUpstreamDown, Message: "Deletion did not finish. Some files may already be removed. Refresh the library and review before trying again."})
		return
	}
	if fresh.Video != nil {
		go s.sweepIndex(context.Background())
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (s *Server) buildRemovalPlan(ctx context.Context, w http.ResponseWriter, r *http.Request, body removalRequest) (*removalPlan, error) {
	plan := &removalPlan{}
	if body.Kind == "video" {
		if !isHex32(body.ID) {
			return nil, fmt.Errorf("Invalid library item")
		}
		c, ok := s.jellyfinForRequest(w, r)
		if !ok {
			return nil, nil
		}
		item, err := c.Item(ctx, body.ID)
		if err != nil {
			return nil, fmt.Errorf("Could not load the server title")
		}
		switch item.Type {
		case "Movie", "Episode", "Series", "Season":
		default:
			return nil, fmt.Errorf("Open an individual movie, show, season or episode to delete it")
		}
		plan.Title = item.Name
		plan.Video = c
		items := []jellyfin.Item{*item}
		if item.Type == "Series" || item.Type == "Season" {
			items = nil
			for start := 0; start < 10000; start += 200 {
				page, err := c.Items(ctx, jellyfin.ItemsQuery{ParentID: item.ID, Types: "Episode", Recursive: true, Limit: 200, StartIndex: start, Fields: "Path,MediaSources", SortBy: "SortName", SortOrder: "Ascending"})
				if err != nil {
					return nil, fmt.Errorf("Could not load every episode")
				}
				items = append(items, page.Items...)
				if start+len(page.Items) >= page.TotalRecordCount {
					break
				}
				if len(page.Items) == 0 || start == 9800 {
					return nil, fmt.Errorf("Could not verify the full series")
				}
			}
		}
		signature := []string{item.ID, item.Type, item.Path, item.Name}
		for _, child := range items {
			signature = append(signature, child.ID, child.Path)
			if len(child.MediaSources) == 0 {
				plan.Names = append(plan.Names, child.Name)
			} else {
				for _, media := range child.MediaSources {
					signature = append(signature, media.ID, media.Path, strconv.FormatInt(media.Size, 10))
					plan.Names = append(plan.Names, child.Name+" · "+path.Base(strings.ReplaceAll(media.Path, "\\", "/")))
				}
			}
		}
		if len(plan.Names) == 0 {
			return nil, fmt.Errorf("No server media files are available to delete")
		}
		sort.Strings(signature)
		plan.Signature = subtitleHash(signature...)
		return plan, nil
	}
	if body.Kind != "reading" || !validReadingWorkID(body.ID) {
		return nil, fmt.Errorf("Invalid media selection")
	}
	binding, ok := s.readingCatalog.Resolve(body.ID)
	if !ok {
		return nil, fmt.Errorf("Book was not found")
	}
	remoteFiles := map[string]string{} // local files are deduplicated below
	selectedStory := map[string]bool{}
	for _, source := range binding.Sources {
		switch source.Source {
		case "kavita":
			if s.kavita == nil {
				return nil, fmt.Errorf("Kavita is unavailable")
			}
			id, _ := strconv.Atoi(source.SourceID)
			detail, err := s.kavita.Detail(ctx, id)
			if err != nil {
				return nil, fmt.Errorf("Could not verify every issue in Kavita")
			}
			plan.Title = detail.Series.Name
			for _, volume := range detail.Volumes {
				for _, chapter := range volume.Chapters {
					if len(chapter.Files) == 0 {
						return nil, fmt.Errorf("Kavita did not provide this issue's files")
					}
					for _, file := range chapter.Files {
						remoteFiles["kavita\x00"+file.FilePath] = file.FilePath
					}
				}
			}
		case "storyteller":
			if s.storyteller == nil {
				return nil, fmt.Errorf("Storyteller is unavailable")
			}
			id, _ := strconv.ParseInt(source.SourceID, 10, 64)
			if _, state := s.storytellerStanding(ctx, id); state == storytellerGone {
				// Deleted from Storyteller already (and now unbound): nothing of it to
				// verify or delete, and it must not stop the rest of the work going.
				continue
			}
			book, err := s.storyteller.Book(ctx, id)
			if err != nil {
				return nil, fmt.Errorf("Could not verify every book edition")
			}
			plan.Title = book.Title
			selectedStory[source.SourceID] = true
			paths, err := storytellerRemovalPaths(*book)
			if err != nil {
				return nil, err
			}
			for _, file := range paths {
				remoteFiles["storyteller\x00"+file] = file
			}
		default:
			return nil, fmt.Errorf("Open an individual book within this collection to delete it")
		}
		plan.Sources = append(plan.Sources, source)
	}
	// Shared files must not be removed while another book still owns them.
	if len(selectedStory) > 0 {
		books, err := s.storyteller.Books(ctx)
		if err != nil {
			return nil, fmt.Errorf("Could not check for shared book files")
		}
		for _, book := range books {
			if selectedStory[strconv.FormatInt(book.ID, 10)] {
				continue
			}
			paths, _ := storytellerRemovalPaths(book)
			for _, file := range paths {
				if _, shared := remoteFiles["storyteller\x00"+file]; shared {
					return nil, fmt.Errorf("A file is shared with another book. Resolve the duplicate in Storyteller first")
				}
			}
		}
	}
	seen := map[string]bool{}
	for key, remote := range remoteFiles {
		service := strings.SplitN(key, "\x00", 2)[0]
		file, err := readingdomain.ResolveRemovalFile(s.cfg.Server.MediaRemovalRoots, service, remote)
		if err != nil {
			return nil, err
		}
		if seen[strings.ToLower(file.Path)] {
			continue
		}
		seen[strings.ToLower(file.Path)] = true
		plan.Files = append(plan.Files, file)
	}
	if len(plan.Files) == 0 {
		return nil, fmt.Errorf("No server files are available to delete")
	}
	sort.Slice(plan.Files, func(i, j int) bool { return plan.Files[i].Path < plan.Files[j].Path })
	signature := []string{body.ID, plan.Title}
	for _, file := range plan.Files {
		plan.Names = append(plan.Names, filepath.Base(file.Path))
		signature = append(signature, file.Path, strconv.FormatInt(file.Size, 10), strconv.FormatInt(file.Modified, 10))
	}
	for _, source := range plan.Sources {
		signature = append(signature, source.Source, source.SourceID)
	}
	plan.Signature = subtitleHash(signature...)
	return plan, nil
}

func storytellerRemovalPaths(book storyteller.Book) ([]string, error) {
	out := []string{}
	if book.Ebook != nil && !book.Ebook.Missing {
		if book.Ebook.Filepath == "" {
			return nil, fmt.Errorf("Book file path is unavailable")
		}
		out = append(out, book.Ebook.Filepath)
	}
	if book.Readaloud != nil && !book.Readaloud.Missing {
		if book.Readaloud.Filepath == "" {
			return nil, fmt.Errorf("Read-along file path is unavailable")
		}
		out = append(out, book.Readaloud.Filepath)
	}
	if book.Audiobook != nil && !book.Audiobook.Missing {
		if book.Audiobook.Filepath == "" || len(book.Audiobook.Manifest.ReadingOrder) == 0 {
			return nil, fmt.Errorf("Audiobook track files are unavailable")
		}
		for _, track := range book.Audiobook.Manifest.ReadingOrder {
			u, err := url.Parse(track.Href)
			if err != nil || u.IsAbs() || u.Host != "" || u.RawQuery != "" {
				return nil, fmt.Errorf("Unsupported audiobook track path")
			}
			name := strings.ReplaceAll(u.Path, "\\", "/")
			if name == "" || name != path.Clean(name) || strings.HasPrefix(name, "/") || name == ".." || strings.HasPrefix(name, "../") {
				return nil, fmt.Errorf("Unsupported audiobook track path")
			}
			out = append(out, path.Join(book.Audiobook.Filepath, name))
		}
	}
	return out, nil
}
