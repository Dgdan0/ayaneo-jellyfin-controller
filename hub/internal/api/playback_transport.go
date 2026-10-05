package api

// Playback transport: the session-bound media, HLS, subtitle, trickplay and
// preview routes, and the proxy that keeps Jellyfin's URLs and key off the
// handheld.

import (
	"bufio"
	"bytes"
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
)

func (s *Server) handlePlaybackStream(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	session.mu.Lock()
	resource := session.Source.DirectStreamURL
	if resource == "" {
		query := url.Values{
			"static": {"true"}, "mediaSourceId": {session.Source.ID},
			"playSessionId": {session.Info.PlaySessionID}, "deviceId": {session.DeviceID},
		}
		resource = "/Videos/" + session.Item.ID + "/stream?" + query.Encode()
	}
	client := session.Client
	itemID := session.Item.ID
	session.mu.Unlock()
	if !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "Jellyfin returned an invalid stream"})
		return
	}
	s.proxyPlaybackResource(w, r, client, resource, false, itemID, session.ID)
}

func (s *Server) handlePlaybackSubtitle(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	trackID := r.PathValue("trackId")
	session.mu.Lock()
	resource := session.Subtitles[trackID]
	client, itemID := session.Client, session.Item.ID
	session.mu.Unlock()
	if resource == "" || !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such subtitle track"})
		return
	}
	offsetMillis, err := strconv.ParseInt(r.URL.Query().Get("offsetMillis"), 10, 64)
	if r.URL.Query().Get("offsetMillis") == "" {
		offsetMillis = 0
		err = nil
	}
	if err != nil || offsetMillis < -maxSubtitleOffset || offsetMillis > maxSubtitleOffset {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "subtitle offset is invalid"})
		return
	}
	if offsetMillis != 0 {
		s.proxyShiftedSubtitle(w, r, client, resource, offsetMillis)
		return
	}
	s.proxyPlaybackResource(w, r, client, resource, false, itemID, session.ID)
}

func (s *Server) proxyShiftedSubtitle(
	w http.ResponseWriter, r *http.Request, client *jellyfin.Client, resource string, offsetMillis int64,
) {
	response, err := client.OpenResource(r.Context(), http.MethodGet, resource, nil)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode >= 400 {
		copyPlaybackResponseHeaders(w.Header(), response.Header)
		w.WriteHeader(response.StatusCode)
		_, _ = io.Copy(w, io.LimitReader(response.Body, 8<<10))
		return
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxSubtitleBytes+1))
	if err != nil || len(body) > maxSubtitleBytes {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "Jellyfin returned an invalid subtitle"})
		return
	}
	shifted := shiftSubtitleTimings(body, offsetMillis)
	w.Header().Set("Content-Type", response.Header.Get("Content-Type"))
	w.Header().Set("Content-Length", strconv.Itoa(len(shifted)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(shifted)
}

var subtitleTimestamp = regexp.MustCompile(`(?:(\d{1,3}):)?(\d{2}):(\d{2})([,.])(\d{3})`)

func shiftSubtitleTimings(body []byte, offsetMillis int64) []byte {
	return subtitleTimestamp.ReplaceAllFunc(body, func(value []byte) []byte {
		parts := subtitleTimestamp.FindSubmatch(value)
		if len(parts) != 6 {
			return value
		}
		hours, minutes, seconds, millis := int64(0), int64(0), int64(0), int64(0)
		if len(parts[1]) > 0 {
			hours, _ = strconv.ParseInt(string(parts[1]), 10, 64)
		}
		minutes, _ = strconv.ParseInt(string(parts[2]), 10, 64)
		seconds, _ = strconv.ParseInt(string(parts[3]), 10, 64)
		millis, _ = strconv.ParseInt(string(parts[5]), 10, 64)
		total := ((hours*60+minutes)*60+seconds)*1000 + millis + offsetMillis
		if total < 0 {
			total = 0
		}
		h := total / 3_600_000
		m := (total % 3_600_000) / 60_000
		s := (total % 60_000) / 1000
		ms := total % 1000
		separator := string(parts[4])
		if len(parts[1]) > 0 || h > 0 {
			return []byte(fmt.Sprintf("%02d:%02d:%02d%s%03d", h, m, s, separator, ms))
		}
		return []byte(fmt.Sprintf("%02d:%02d%s%03d", m, s, separator, ms))
	})
}

func (s *Server) handlePlaybackTrickplay(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	index, err := strconv.Atoi(r.PathValue("index"))
	session.mu.Lock()
	info := session.Plan.Trickplay
	client, itemID, sourceID := session.Client, session.Item.ID, session.Source.ID
	session.mu.Unlock()
	if err != nil || info == nil || index < 0 {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such trickplay tile"})
		return
	}
	perTile := info.TileWidth * info.TileHeight
	tileCount := (info.ThumbnailCount + perTile - 1) / perTile
	if index >= tileCount {
		writeError(w, r, http.StatusNotFound, Error{Code: CodeNotFound, Message: "no such trickplay tile"})
		return
	}
	query := url.Values{"MediaSourceId": {sourceID}}
	resource := fmt.Sprintf("/Videos/%s/Trickplay/%d/%d.jpg?%s", itemID, info.Width, index, query.Encode())
	response, err := client.OpenResource(r.Context(), http.MethodGet, resource, nil)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	defer response.Body.Close()
	copyPlaybackResponseHeaders(w.Header(), response.Header)
	w.Header().Set("Content-Disposition", "inline")
	w.Header().Set("Cache-Control", "private, max-age=14400")
	w.WriteHeader(response.StatusCode)
	_, _ = io.Copy(w, response.Body)
}

// handlePlaybackPreview extracts one small frame when Jellyfin has not generated
// trickplay sheets for the item. The media path remains inside the session and
// never enters the API response or an ffmpeg shell command.
func (s *Server) handlePlaybackPreview(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	position, err := strconv.ParseInt(r.URL.Query().Get("positionMillis"), 10, 64)
	session.mu.Lock()
	duration, sourcePath := session.Plan.DurationMillis, session.Source.Path
	session.mu.Unlock()
	if err != nil || position < 0 || duration <= 0 || position > duration {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "positionMillis is outside this item",
		})
		return
	}
	if sourcePath == "" {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "preview", Message: "preview source is unavailable",
		})
		return
	}
	// Five-second buckets make a drag reuse nearby frames and cap process churn.
	position = (position / 5_000) * 5_000
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	frame, err := s.previewFrame(ctx, sourcePath, position)
	if err != nil {
		slog.Debug("preview frame extraction failed")
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: "preview", Message: "preview frame is unavailable",
		})
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Content-Disposition", "inline")
	w.Header().Set("Cache-Control", "private, max-age=86400")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(frame)
}

func extractPreviewFrame(ctx context.Context, sourcePath string, positionMillis int64) ([]byte, error) {
	ffmpeg, err := findFFmpeg()
	if err != nil {
		return nil, err
	}
	var output, stderr bytes.Buffer
	command := exec.CommandContext(
		ctx, ffmpeg,
		"-nostdin", "-hide_banner", "-loglevel", "error",
		"-ss", fmt.Sprintf("%.3f", float64(positionMillis)/1000),
		"-i", sourcePath,
		"-frames:v", "1", "-vf", "scale=240:-2",
		"-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
	)
	command.Stdout = &output
	command.Stderr = &stderr
	if err := command.Run(); err != nil {
		return nil, fmt.Errorf("ffmpeg preview: %w: %s", err, strings.TrimSpace(stderr.String()))
	}
	if output.Len() == 0 || output.Len() > 2<<20 {
		return nil, fmt.Errorf("ffmpeg preview returned %d bytes", output.Len())
	}
	return output.Bytes(), nil
}

func findFFmpeg() (string, error) { return findMediaTool("ffmpeg") }

// findFFprobe is the same search for ffprobe, which Jellyfin ships beside its
// ffmpeg. Neither is on PATH on the media PC.
func findFFprobe() (string, error) { return findMediaTool("ffprobe") }

func findMediaTool(name string) (string, error) {
	if binary, err := exec.LookPath(name); err == nil {
		return binary, nil
	}
	programFiles := os.Getenv("ProgramFiles")
	if programFiles != "" {
		for _, relative := range []string{
			filepath.Join("Jellyfin", "Server", name+".exe"),
			filepath.Join("Jellyfin", "Server", "jellyfin-ffmpeg", name+".exe"),
		} {
			candidate := filepath.Join(programFiles, relative)
			if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
				return candidate, nil
			}
		}
	}
	return "", fmt.Errorf("%s executable was not found", name)
}

func (s *Server) handlePlaybackHLS(w http.ResponseWriter, r *http.Request) {
	if !s.requirePlay(w, r) {
		return
	}
	session, ok := s.playbackSessionForRequest(w, r)
	if !ok {
		return
	}
	decoded, err := base64.RawURLEncoding.DecodeString(r.PathValue("resource"))
	if err != nil {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad HLS resource"})
		return
	}
	resource := string(decoded)
	session.mu.Lock()
	client, itemID := session.Client, session.Item.ID
	session.mu.Unlock()
	if !validPlaybackResource(resource, itemID) {
		writeError(w, r, http.StatusBadRequest, Error{Code: CodeInvalidRequest, Message: "bad HLS resource"})
		return
	}
	s.proxyPlaybackResource(w, r, client, resource, true, itemID, session.ID)
}

func encodePlaybackResource(resource string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(resource))
}

func validPlaybackResource(resource, itemID string) bool {
	parsed, err := url.Parse(resource)
	if err != nil || parsed.IsAbs() || parsed.Host != "" || parsed.Path == "" {
		return false
	}
	cleaned := path.Clean(parsed.Path)
	parts := strings.Split(strings.TrimPrefix(cleaned, "/"), "/")
	return len(parts) >= 2 && strings.EqualFold(parts[0], "Videos") &&
		playbackIDsEqual(parts[1], itemID)
}

func playbackIDsEqual(left, right string) bool {
	canonical := func(value string) string {
		value = strings.ToLower(strings.ReplaceAll(value, "-", ""))
		if len(value) != 32 {
			return ""
		}
		for _, char := range value {
			if (char < '0' || char > '9') && (char < 'a' || char > 'f') {
				return ""
			}
		}
		return value
	}
	a, b := canonical(left), canonical(right)
	return a != "" && a == b
}

func sanitizePlaybackResource(resource string) (string, error) {
	parsed, err := url.Parse(resource)
	if err != nil || parsed.IsAbs() || parsed.Host != "" || parsed.Path == "" {
		return "", fmt.Errorf("invalid playback resource")
	}
	query := parsed.Query()
	for name := range query {
		switch strings.ToLower(name) {
		case "apikey", "api_key", "token", "access_token", "x-emby-token":
			query.Del(name)
		}
	}
	parsed.RawQuery = query.Encode()
	parsed.ForceQuery = false
	return parsed.RequestURI(), nil
}

func playbackResourceQueryValue(resource, wanted string) string {
	parsed, err := url.Parse(resource)
	if err != nil {
		return ""
	}
	for name, values := range parsed.Query() {
		if strings.EqualFold(name, wanted) && len(values) > 0 {
			return values[0]
		}
	}
	return ""
}

func (s *Server) proxyPlaybackResource(
	w http.ResponseWriter, r *http.Request, client *jellyfin.Client, resource string,
	rewriteHLS bool, itemID, sessionID string,
) {
	s.proxyPlaybackResourceWithPrefix(w, r, client, resource, rewriteHLS, itemID,
		"/v1/playback/sessions/"+sessionID+"/hls/")
}

func (s *Server) proxyPlaybackResourceWithPrefix(
	w http.ResponseWriter, r *http.Request, client *jellyfin.Client, resource string,
	rewriteHLS bool, itemID, hlsPrefix string,
) {
	// Authenticated media transfers (including offline range resumes) regularly
	// outlive the JSON response budget. Keep request cancellation, but remove the
	// server's absolute write deadline before opening the upstream stream.
	if err := prepareLongStream(w); err != nil {
		writeError(w, r, http.StatusInternalServerError, Error{Code: CodeInternal, Message: "could not start media transfer"})
		return
	}
	headers := make(http.Header)
	for _, name := range []string{"Range", "If-Range", "If-None-Match", "If-Modified-Since"} {
		if value := r.Header.Get(name); value != "" {
			headers.Set(name, value)
		}
	}
	response, err := client.OpenResource(r.Context(), r.Method, resource, headers)
	if err != nil {
		writeUpstreamError(w, r, "jellyfin", err)
		return
	}
	defer response.Body.Close()
	if response.StatusCode >= 400 {
		copyPlaybackResponseHeaders(w.Header(), response.Header)
		w.WriteHeader(response.StatusCode)
		_, _ = io.Copy(w, io.LimitReader(response.Body, 8<<10))
		return
	}
	contentType := response.Header.Get("Content-Type")
	isManifest := rewriteHLS && (strings.Contains(strings.ToLower(contentType), "mpegurl") ||
		strings.HasSuffix(strings.ToLower(strings.Split(resource, "?")[0]), ".m3u8"))
	if !isManifest {
		copyPlaybackResponseHeaders(w.Header(), response.Header)
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(response.StatusCode)
		if r.Method != http.MethodHead {
			_, _ = io.Copy(w, response.Body)
		}
		return
	}
	rewritten, err := rewriteHLSManifestWithPrefix(response.Body, resource, itemID, hlsPrefix)
	if err != nil {
		writeError(w, r, http.StatusBadGateway, Error{Code: CodeUpstreamDown, Message: "Jellyfin returned an invalid HLS manifest"})
		return
	}
	w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	w.Header().Set("Content-Length", strconv.Itoa(len(rewritten)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(rewritten)
}

func copyPlaybackResponseHeaders(dst, src http.Header) {
	for _, name := range []string{
		"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "ETag",
		"Last-Modified", "Cache-Control",
	} {
		if value := src.Get(name); value != "" {
			dst.Set(name, value)
		}
	}
}

var hlsURIAttribute = regexp.MustCompile(`URI="([^"]+)"`)

func rewriteHLSManifest(
	reader io.Reader, baseResource, itemID, sessionID string,
) ([]byte, error) {
	return rewriteHLSManifestWithPrefix(reader, baseResource, itemID,
		"/v1/playback/sessions/"+sessionID+"/hls/")
}

func rewriteHLSManifestWithPrefix(
	reader io.Reader, baseResource, itemID, hlsPrefix string,
) ([]byte, error) {
	baseURL, err := url.Parse(baseResource)
	if err != nil {
		return nil, err
	}
	baseURL.Scheme, baseURL.Host = "", ""
	rewrite := func(raw string) (string, error) {
		ref, err := url.Parse(raw)
		if err != nil {
			return "", err
		}
		ref.Scheme, ref.Host = "", ""
		resolved := baseURL.ResolveReference(ref)
		resource, err := sanitizePlaybackResource(resolved.RequestURI())
		if err != nil {
			return "", err
		}
		if !validPlaybackResource(resource, itemID) {
			return "", fmt.Errorf("HLS resource escaped item")
		}
		return hlsPrefix + encodePlaybackResource(resource), nil
	}
	var out strings.Builder
	scanner := bufio.NewScanner(reader)
	buffer := make([]byte, 64<<10)
	scanner.Buffer(buffer, 1<<20)
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "#") {
			var replaceErr error
			line = hlsURIAttribute.ReplaceAllStringFunc(line, func(match string) string {
				parts := hlsURIAttribute.FindStringSubmatch(match)
				if len(parts) != 2 {
					return match
				}
				value, err := rewrite(parts[1])
				if err != nil {
					replaceErr = err
					return match
				}
				return `URI="` + value + `"`
			})
			if replaceErr != nil {
				return nil, replaceErr
			}
		} else if strings.TrimSpace(line) != "" {
			line, err = rewrite(strings.TrimSpace(line))
			if err != nil {
				return nil, err
			}
		}
		out.WriteString(line)
		out.WriteByte('\n')
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return []byte(out.String()), nil
}
