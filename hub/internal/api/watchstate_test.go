package api

import (
	"testing"

	"ayaneohub/internal/adapters/jellyfin"
)

const tenMinutes = int64(10 * 60 * jellyfin.TicksPerSecond)

func TestWatchDecisionFollowsTheServersStopRules(t *testing.T) {
	rules := jellyfin.DefaultResumeRules
	runtime := 4 * tenMinutes // a 40-minute episode
	cases := []struct {
		name   string
		report watchReport
		want   watchDecision
	}{
		{"progress saves the live position", watchReport{PositionTicks: tenMinutes, RuntimeTicks: runtime}, watchDecision{PositionTicks: tenMinutes}},
		{"progress past the end is clamped", watchReport{PositionTicks: runtime + 5, RuntimeTicks: runtime}, watchDecision{PositionTicks: runtime}},
		{"a negative position is the start", watchReport{PositionTicks: -1, RuntimeTicks: runtime, Final: true}, watchDecision{}},
		{"stopping in the middle keeps a resume point", watchReport{PositionTicks: 2 * tenMinutes, RuntimeTicks: runtime, Final: true}, watchDecision{PositionTicks: 2 * tenMinutes}},
		{"stopping under the minimum clears the resume point", watchReport{PositionTicks: runtime / 25, RuntimeTicks: runtime, Final: true}, watchDecision{}},
		{"stopping past the maximum is watched", watchReport{PositionTicks: runtime * 93 / 100, RuntimeTicks: runtime, Final: true}, watchDecision{Played: true}},
		{"reaching the end is watched", watchReport{PositionTicks: runtime, RuntimeTicks: runtime, Final: true}, watchDecision{Played: true}},
		{"a completed offline stop is watched whatever the numbers say", watchReport{PositionTicks: tenMinutes, RuntimeTicks: runtime, Final: true, Completed: true}, watchDecision{Played: true}},
		{"an item shorter than the minimum resume duration is watched", watchReport{PositionTicks: 2 * 60 * jellyfin.TicksPerSecond, RuntimeTicks: 4 * 60 * jellyfin.TicksPerSecond, Final: true}, watchDecision{Played: true}},
		{"an unknown runtime never guesses watched", watchReport{PositionTicks: tenMinutes, Final: true}, watchDecision{PositionTicks: tenMinutes}},
	}
	for _, c := range cases {
		if got := decideWatchPosition(c.report, rules); got != c.want {
			t.Errorf("%s: got %+v, want %+v", c.name, got, c.want)
		}
	}
}

func TestWatchDecisionUsesTheConfiguredThresholds(t *testing.T) {
	strict := jellyfin.ResumeRules{MinResumePct: 1, MaxResumePct: 99, MinResumeDurationSeconds: 60}
	runtime := 4 * tenMinutes
	// 95% is watched under the shipped 90% default but still resumable at 99%.
	report := watchReport{PositionTicks: runtime * 95 / 100, RuntimeTicks: runtime, Final: true}
	if got := decideWatchPosition(report, strict); got.Played || got.PositionTicks != report.PositionTicks {
		t.Fatalf("strict rules = %+v", got)
	}
}
