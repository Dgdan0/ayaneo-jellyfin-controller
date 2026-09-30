package api

import (
	"context"
	"time"

	"ayaneohub/internal/adapters/jellyfin"
	"ayaneohub/internal/cache"
)

// watchReport is one playback position on its way to the user's Jellyfin data.
type watchReport struct {
	ItemID        string
	PositionTicks int64
	RuntimeTicks  int64
	// Final marks a stop. Progress during playback is saved as it is; a stop
	// is judged by the server's thresholds, exactly as Jellyfin judges its own.
	Final bool
	// Completed is a stop the client saw reach the natural end, which is
	// watched whatever the numbers say.
	Completed bool
	// At is when it was watched. Offline sync passes the original time so the
	// newer-server comparison keeps comparing like with like.
	At time.Time
}

type watchDecision struct {
	PositionTicks int64
	Played        bool
}

// decideWatchPosition mirrors Jellyfin's UserDataManager stop rules. The hub
// has to apply them itself because it saves through the user-data endpoint,
// not a user session (see jellyfin.Client.UpdateUserData).
//
// One deliberate difference: with no runtime Jellyfin marks an item watched on
// any stop, while this keeps the position. A wrong "watched" hides an episode
// from Continue watching, which is worse than an extra resume point.
func decideWatchPosition(report watchReport, rules jellyfin.ResumeRules) watchDecision {
	position := max(report.PositionTicks, 0)
	runtime := report.RuntimeTicks
	if runtime > 0 && position > runtime {
		position = runtime
	}
	if !report.Final {
		return watchDecision{PositionTicks: position}
	}
	if report.Completed {
		return watchDecision{Played: true}
	}
	if runtime <= 0 {
		return watchDecision{PositionTicks: position}
	}
	percent := float64(position) * 100 / float64(runtime)
	switch {
	case percent < rules.MinResumePct:
		return watchDecision{}
	case percent > rules.MaxResumePct || position >= runtime:
		return watchDecision{Played: true}
	case runtime/jellyfin.TicksPerSecond < rules.MinResumeDurationSeconds:
		return watchDecision{Played: true}
	}
	return watchDecision{PositionTicks: position}
}

// recordWatchPosition is the one place a playback position becomes the user's
// Jellyfin resume point: live progress, a stop, an abandoned session and
// offline sync all come through here.
func (s *Server) recordWatchPosition(ctx context.Context, client *jellyfin.Client, report watchReport) error {
	decision := decideWatchPosition(report, s.resumeRules(ctx, client))
	if decision.Played {
		if err := client.SetPlayed(ctx, report.ItemID, true); err != nil {
			return err
		}
	}
	position := decision.PositionTicks
	update := jellyfin.UserDataUpdate{PlaybackPositionTicks: &position}
	if !report.At.IsZero() {
		update.LastPlayedDate = report.At.UTC().Format(time.RFC3339Nano)
	}
	if err := client.UpdateUserData(ctx, report.ItemID, update); err != nil {
		return err
	}
	if report.Final {
		// Every cached view of this user's progress is now wrong: Home, every
		// Library page with its badges, favourites, search and details.
		s.invalidateLibraryUser(client.UserID())
	}
	return nil
}

func (s *Server) resumeRules(ctx context.Context, client *jellyfin.Client) jellyfin.ResumeRules {
	rules, _, err := cache.Fetch(ctx, s.cache, "jellyfin:resume-rules", cache.ServiceOptions, client.ResumeRules)
	if err != nil || rules.MaxResumePct <= 0 {
		return jellyfin.DefaultResumeRules
	}
	return rules
}
