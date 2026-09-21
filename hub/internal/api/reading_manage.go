package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const readingScanTimeout = 2 * time.Minute

type ReadingScanResponse struct {
	OK       bool      `json:"ok"`
	Action   string    `json:"action"`
	Services []string  `json:"services"`
	Partial  []Partial `json:"partial,omitempty"`
}

func (s *Server) handleReadingLibraryScan(w http.ResponseWriter, r *http.Request) {
	if !s.requireControl(w, r) {
		return
	}
	target := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("service")))
	if target != "" && target != "kavita" && target != "storyteller" {
		writeError(w, r, http.StatusBadRequest, Error{
			Code: CodeInvalidRequest, Message: "service must be kavita or storyteller",
		})
		return
	}
	targets := []string{"kavita", "storyteller"}
	if target != "" {
		targets = []string{target}
	}
	if target != "" && !s.readingScannerConfigured(target) {
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: target, Message: readerDisplayName(target) + " is not configured",
		})
		return
	}

	ctx, cancel := timeoutFor(r, readingScanTimeout)
	defer cancel()
	s.readingScanMu.Lock()
	defer s.readingScanMu.Unlock()

	response := ReadingScanResponse{Action: "scan_reading_libraries", Services: []string{}, Partial: []Partial{}}
	for _, service := range targets {
		if !s.readingScannerConfigured(service) {
			continue
		}
		tickets := s.readingTransfers.pendingImported(service)
		if err := s.scanReadingService(ctx, service); err != nil {
			response.Partial = append(response.Partial, Partial{
				Service: service, Reason: "scan_failed", Affects: []string{"reading_library"}, Message: err.Error(),
			})
			continue
		}
		if err := s.readingTransfers.markImportedScanned(service, tickets); err != nil {
			response.Partial = append(response.Partial, Partial{
				Service: service, Reason: "state_write_failed", Affects: []string{"import_reconciliation"}, Message: err.Error(),
			})
			continue
		}
		response.Services = append(response.Services, service)
	}
	if len(response.Services) == 0 {
		message := "no reading service is configured"
		service := target
		if len(response.Partial) > 0 {
			message = response.Partial[0].Message
			service = response.Partial[0].Service
		}
		writeError(w, r, http.StatusServiceUnavailable, Error{
			Code: CodeUpstreamDown, Service: service, Message: message, Retryable: true,
		})
		return
	}
	response.OK = true
	s.invalidateReadingCatalog()
	s.watchReadingScan()
	writeJSON(w, http.StatusAccepted, response)
}

// reconcileReadingImports observes BookKeeprr's durable import marker, scans
// only the readers that can consume that content type, and records each
// successful scan. Failed readers remain pending while successful readers are
// never repeated for the same importedAt value.
func (s *Server) reconcileReadingImports(parent context.Context) error {
	if s.bookkeeprr == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(parent, readingScanTimeout)
	defer cancel()
	downloads, err := s.bookkeeprr.Downloads(ctx)
	if err != nil {
		return fmt.Errorf("listing BookKeeprr imports: %w", err)
	}
	if _, err := s.readingTransfers.reconcile(downloads.Downloads); err != nil {
		return fmt.Errorf("saving BookKeeprr imports: %w", err)
	}

	s.readingScanMu.Lock()
	defer s.readingScanMu.Unlock()
	var failures []error
	scanned := false
	for _, service := range []string{"kavita", "storyteller"} {
		if !s.readingScannerConfigured(service) {
			continue
		}
		tickets := s.readingTransfers.pendingImported(service)
		if len(tickets) == 0 {
			continue
		}
		if err := s.scanReadingService(ctx, service); err != nil {
			failures = append(failures, fmt.Errorf("%s scan: %w", service, err))
			continue
		}
		if err := s.readingTransfers.markImportedScanned(service, tickets); err != nil {
			failures = append(failures, fmt.Errorf("saving %s scan receipt: %w", service, err))
			continue
		}
		scanned = true
	}
	if scanned {
		s.invalidateReadingCatalog()
		s.watchReadingScan()
	}
	return errors.Join(failures...)
}

func (s *Server) readingScannerConfigured(service string) bool {
	switch service {
	case "kavita":
		return s.kavita != nil
	case "storyteller":
		return s.storyteller != nil
	default:
		return false
	}
}

func (s *Server) scanReadingService(ctx context.Context, service string) error {
	switch service {
	case "kavita":
		return s.kavita.ScanAll(ctx)
	case "storyteller":
		return s.storyteller.ScanAll(ctx)
	default:
		return fmt.Errorf("unsupported reading service %q", service)
	}
}

func readerDisplayName(service string) string {
	if service == "storyteller" {
		return "Storyteller"
	}
	return "Kavita"
}

func (s *Server) invalidateReadingCatalog() {
	s.cache.InvalidatePrefix("reading:")
}

// Kavita can acknowledge before its background scanner has committed every
// book. Expiring reading caches again while that work settles prevents a
// recently imported title from staying hidden behind a previously cached row.
func (s *Server) watchReadingScan() {
	s.readingWatchMu.Lock()
	if s.readingScanWatch {
		s.readingWatchMu.Unlock()
		return
	}
	s.readingScanWatch = true
	s.readingWatchMu.Unlock()
	go func() {
		defer func() {
			s.readingWatchMu.Lock()
			s.readingScanWatch = false
			s.readingWatchMu.Unlock()
		}()
		for _, wait := range []time.Duration{10 * time.Second, 20 * time.Second, 30 * time.Second} {
			timer := time.NewTimer(wait)
			<-timer.C
			s.invalidateReadingCatalog()
		}
	}()
}
