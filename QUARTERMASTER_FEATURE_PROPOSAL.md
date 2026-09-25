# Quartermaster ideas for Ayaneo Hub

Research date: 2026-09-25. This is a design and implementation proposal, not shipped functionality. The accompanying interactive concept uses illustrative data and performs no live operations.

## Reference and recommendation

The relevant Quartermaster is **Quartermaster: Homelab Stack**, the self-hosted media/server controller from qmstack.com. This comparison uses its developer website and App Store description/version history, not a hands-on audit of the installed iOS app.

- [Quartermaster official website](https://www.qmstack.com/): download diagnostics, media/infrastructure separation, home/away connections, shortcuts and Companion.
- [Developer's App Store listing and release notes](https://apps.apple.com/gb/app/quartermaster-homelab-stack/id6779994284): release calendar, subtitle search, download speed controls, notifications, family access, multiple servers and library statistics.

The user supports features 1–8 and has explicitly asked to add bandwidth controls, server management and alerts. Their follow-up strongly favors Upcoming and requests a staged build/test rollout. The first proposed milestone pairs Upcoming with an evidence-based download diagnosis panel, delivered as two independently testable changes. Build host monitoring and server controls separately because the present deployment mixes Windows services, standalone Windows processes and Docker containers.

Keep the current architecture: the Android app talks to the Go Hub; the Hub stores upstream credentials and joins service data. Quartermaster's direct-to-service design is not a reason to move those keys onto the handheld.

## What already exists here

Source inspection confirms:

- Discovery, request options, interactive release selection and a cross-service title pipeline.
- A merged torrent/Arr queue with progress, warnings, stuck states, pause/start, removal and blocklist/search actions. Diagnostics would deepen this existing feature.
- Jellyfin Home, Library, user selection, playback and offline downloads.
- Service health, versions, latency, dashboard links and Jellyfin library scanning.
- An in-app notification feed, unread tracking and history-limit preferences.
- Light/dark/system themes, collapsible navigation and controller hints.

Prowlarr and Readarr currently have management/health coverage rather than full native workflows. CleanUparr health support exists in the working tree; the September 20 audit records deployment as pending. Those notes are historical evidence, not a fresh live-server check. Komga is in the existing reading roadmap, with no reader destination found in the current app sources.

## Proposed features

### 1. Download diagnostics — first priority, medium effort

**Experience:** select a problem transfer and open **Why is this stuck?** Show the symptom, observed evidence, likely cause and appropriate next action. A completed download with a missing-path import error should lead to path inspection. A transfer with no progress and no connected seeds should offer alternative releases. Neither should receive a generic retry instruction.

**UI:** Transfers > Needs attention is the primary location. A problem transfer has a visible **Why is this stuck?** action and an equivalent action in its existing controller action sheet. A left list and right diagnosis panel suit the landscape handheld. Keep ordinary transfers in the existing list. The title pipeline's stuck state and a failure notification open that same diagnosis with the exact transfer selected; there is no separate top-level Doctor destination. General service outages stay under Manage, with relevant links from the diagnosis. Show source names, last checked time and degraded-data states. Broaden the contextual label to **Why is this waiting?** when no transfer has started, rather than inventing a torrent failure.

**Implementation:** extract/reuse the join in `hub/internal/api/activity.go` and its existing `ArrRef.Problem`, warnings, client stage and exact hash matches. Add a pure diagnosis model with code, summary, evidence, certainty and allowed actions. Start with explicit upstream errors; maintain bounded timestamped observations for time-dependent stall detection. A single 0 B/s sample is insufficient. Add proposed `GET /v1/downloads/{id}/diagnosis`, then use `DownloadsScreen` and existing in-layout overlays. Reuse already scoped action endpoints. Manual import/file matching is a later extension that needs its own review and identity checks.

**Validation:** paused versus stalled, complete-but-unimported, season packs, unavailable upstreams, uncertain joins and token-scoped actions. Revalidate targets before a mutation and preserve focus on refresh.

### 2. Upcoming calendar and missing episodes — high priority, medium effort

**Experience:** see followed episodes and movie releases in one place, with separate labels for not released, missing, downloading and in library. Select an aired missing episode to use the existing release picker.

**UI, revised after user feedback:** Discover > Upcoming uses one chronological agenda, with a weekday and full date heading above each day's releases. Each release is a card with a portrait series/movie poster, title, episode or batch, time and relevant availability state. Keep the selected title's synopsis, date, status and actions in the right pane. Show “Today” and “Tomorrow” as additional date labels rather than separate, incomplete date buckets. Navigate contiguous calendar weeks with explicit start/end dates: in the illustrative Friday 25 September view, **This week · 25–27 Sep** includes Sunday, and **Next week · 28 Sep–4 Oct** follows without a gap. After that, continue week by week. The week-start preference must be explicit/localizable; the concept uses Monday. Keep days with no releases as a quiet line, and allow consecutive empty days to be compressed with their exact date range. Unknown dates belong in a separate **Date not announced** group, outside dated ordering. Sort releases within a day by known time, then title; date-only releases are clearly labeled. Group same-series/same-day batches without hiding their episode identities. Put a short Upcoming shelf on Home later. Avoid adding another main navigation icon to an already crowded rail.

**Implementation:** add bounded date-range calendar reads to the Sonarr/Radarr adapter, normalize them behind proposed `GET /v1/calendar`, join availability through the existing provider index, and link to current title/release targets. Preserve date-only movie dates as dates; convert actual episode timestamps to the selected local timezone. Label theatrical and digital dates distinctly. Pagination, monitored filters and partial responses belong in the Hub contract.

**Validation:** timezone/DST boundaries, unknown dates, season drops, Specials, duplicate records and an upstream calendar being unavailable.

### 3. Subtitle acquisition and replacement — high priority, medium effort

**Experience:** find a missing Hebrew or English subtitle from the movie/episode page, inspect provider and release compatibility, and add the chosen external track. This extends the existing player subtitle selection.

**UI:** Library title > Subtitles. Existing tracks on the left, provider results and a review sheet on the right. Keep language, hearing-impaired and forced-subtitle flags visible.

**User-requested score and feedback:** display the upstream release-match score in search results and retain it after download alongside provider, language, release and timestamp. Label it **Release match**, not translation quality. Store the score's source and scale; do not fabricate a percentage when normalization is unknown. Join history only when it identifies the currently installed subtitle/file; otherwise display **Score unavailable**. Keep a separate local user assessment: **Good / Out of sync / Wrong translation**. Out of sync offers the existing player subtitle-offset control; poor translation offers replacement search. User feedback does not automatically blacklist/delete a file or submit a rating to a provider. If the media is upgraded or subtitle replaced, do not carry the old rating onto the new file. [Bazarr's subtitle settings](https://wiki.bazarr.media/Additional-Configuration/Settings/Subtitles/) and [setup guide](https://wiki.bazarr.media/Getting-Started/Setup-Guide/) document score thresholds; metadata matching is not a guarantee of perceived translation quality.

**Implementation:** expand the existing Bazarr history/health adapter with item lookup, search and download operations after checking its installed API schema. Resolve Jellyfin items to Radarr movies or Sonarr episodes through stable identifiers; never infer an episode solely from its title. Proposed title-scoped subtitle endpoints return opaque candidate IDs that the Hub validates. Reuse track display data, refresh Jellyfin metadata when necessary and re-query player tracks after success. Embedded tracks are not replaceable sidecar files. Blacklisting/deleting a poor external subtitle is a separate reviewed action.

**Validation:** language codes, provider failure, release mismatch, no Bazarr mapping, subtitle write failure and stale player track lists.

### 4. Download bandwidth and queue controls — high priority, small-to-medium effort

**Experience:** select Normal, Quiet or a custom download/upload limit, and move an important transfer up the queue.

**UI:** a Bandwidth control above Transfers; queue priority in the selected transfer's action sheet. Display whether a limit is global, alternative mode or per transfer, including units.

**Implementation:** extend `hub/internal/adapters/qbittorrent/client.go`, which currently offers torrent reads and stop/start/delete. Add capability/version checks and bounded Hub control endpoints. Use explicit values for state changes, handle queueing-disabled configurations, and read back actual server values after an update. A Quiet preset is our proposed UX, not a verified Quartermaster label.

### 5. Server overview and controlled operations — medium priority, large effort

**Experience:** see free disk space, resource usage and active Jellyfin sessions alongside service health. Drill into a service's recent activity; restart it where a supervisor is configured.

**UI:** Manage > Services / Server / Activity. The service detail shows whether it is managed by Windows, FireDaemon or Docker. Keep paused streams separate from actively playing sessions. Include a review screen for a restart and show its concrete target and effect.

**Implementation:** retain `/v1/health`; add separate host-metrics and Jellyfin-session reads. Introduce a host-control provider interface with a service-ID allowlist and narrow operations. Windows process discovery does not itself provide restart support; an explicit supervisor mapping is required. Docker control needs a separately configured local integration. Initially enable read-only monitoring, then a single supported restart workflow with authorization and an audit record. The Android client must not submit arbitrary shell commands. Host privileges, service names and installed API capabilities must be verified during implementation.

Quartermaster's Companion suggests the convenience, but the existing Go Hub already fills the companion role here. Container updates, rollback, SSH and editing Compose files should not be part of the first milestone.

### 6. Actionable alerts — medium priority, medium-to-large effort

**Experience:** receive a notification when a requested title is ready, an import fails or storage is low; open the exact title or problem transfer from the alert.

**UI:** extend the current Notifications screen with event filters, severity and direct destinations. Quiet hours and per-service preferences belong in Settings.

**Implementation:** keep stable event IDs, add persisted deduplication and transitions so the same failure does not alert repeatedly. Authenticated upstream webhooks can feed a Hub event store; retain polling as a reconciliation fallback. Android background delivery requires a deliberate push/ntfy or scheduled-fetch design; foreground polling is not reliable instant delivery when the app is closed. Partition events and deep links by Hub, token permissions and selected user. Do not expose webhook ingress publicly just to make it convenient.

### 7. Home shortcuts and configurable layout — useful quick win, small effort

**Experience:** pin Upcoming, failed transfers, a chosen library or subtitle problems. Reorder Home shelves and optionally hide irrelevant sections.

**UI:** a compact shortcut row using the current focus ring; Settings > Home controls order and visibility. This is the best low-risk customization to borrow.

**Implementation:** add a small preferences object beside existing settings, store stable destination IDs, and use current navigation/focus helpers. Preserve L1/R1 behavior and a reachable Settings entry. Add new accent palettes only after contrast and service-status distinctions are checked.

### 8. Local/remote Hub routing and multiple profiles — conditional priority, medium-to-large effort

**Experience:** prefer a configured local Hub address at home and use the private remote route away; optionally select a different Hub setup.

**UI:** Settings > Connection with Local / Remote candidates, active route and Test connection. The user should configure Hub addresses, not every upstream service.

**Implementation:** replace the single `HubSettings` URL with a connection profile. Verify both routes identify the same Hub before sharing a token, bind cached state to Hub/user identity, preserve authentication failure suppression and avoid route flapping. Select the route before a playback/download session; changing hosts midstream needs explicit reconnect handling. The existing Tailscale route already handles home and away, so this is lower priority unless performance or multiple homes justifies it.

### 9. Family access, libraries beyond video and analytics — later roadmap

- **Family mode:** reuse scopes but add backend-enforced identity/user restrictions and request ownership. A hidden Manage tab or local PIN alone does not restrict a bearer token that retains control rights. The current Jellyfin profile picker is not a separate household authorization system.
- **Reading:** follow the existing Komga roadmap: health/library adapter, a Reading destination, then a native reader and offline reading. Quartermaster's breadth validates the category; it does not provide implementation compatibility with this Android app.
- **Statistics:** start with library inventory and current sessions. Meaningful historical watch charts need recorded events or a dedicated analytics backend; do not infer complete history from a title's current played flag.
- **Multiple Arr instances:** only when a real HD/4K split is needed. The current server initializes one `radarr` and one `sonarr` adapter. Multi-instance support requires explicit instance IDs throughout routing, media joins, caches, action targets and configuration, not just an extra picker.

## Delivery order and architecture — revised after user feedback

Keep the accepted scope in one backlog, but deliver small vertical slices: Hub contract and logic, Android UI, focused automated checks, build, then real-device verification. Each feature gets its own reviewable change and rollback point. Do not combine the existing uncommitted player/offline work with these additions.

1. **Upcoming and diagnostics.** Build the Upcoming agenda first, then transfer diagnostics, as separate changes within the first test milestone. Validate the real calendar, contiguous dates/week boundaries, artwork fallback, missing/unknown dates, selection restoration and controller/touch access. Exercise actual queue evidence plus deterministic paused/stalled/import-failure fixtures. Start diagnosis with evidence and existing reviewed actions; deeper manual-import repair is later.
2. **Subtitles and download bandwidth.** Build subtitle search/review plus retained match scores and user feedback. Separately add normal/alternative/custom qBittorrent limits and supported queue priority. Verify one real subtitle acquisition and player refresh, score provenance, missing-score handling and download-limit readback.
3. **Server management.** Deliver host/disk/session monitoring first. Then enable a narrowly configured service restart workflow with review, permissions, an audit result and verified post-action health. Test Windows-managed and Docker-managed cases independently; unmanaged processes must not advertise unsupported controls.
4. **Alerts and Home shortcuts.** Add persistent event deduplication and item-specific destinations, then background delivery. Add configurable shortcuts/shelf order to expose Upcoming and Needs attention. Test foreground/background delivery, duplicate suppression, quiet hours, reconnect behavior and restoring focus after customization.
5. **Connection routing.** Add local/remote candidates for the same Hub, explicit active-route display and connection testing. Validate LAN-to-away transitions, authentication failures, cache/user isolation and ongoing playback/download behavior. Multiple distinct Hub profiles can follow if desired.

After each feature's focused checks pass, build a candidate and run the relevant Pocket DS scenario before installing it as the daily version. Retain the prior working APK/Hub version and a compatible config backup. A milestone is done only when its user flow and a short regression check of existing browsing, playback and downloads pass; a green build alone is not a hardware test. User hardware observations should feed the next small correction before expanding scope. No commitment to exact durations until the corresponding upstream API and deployment capabilities are verified.

Family access, reading, historical analytics and multiple Arr instances remain separate later possibilities; the user's endorsement of features 1–8 does not automatically authorize expanding this milestone to those extras.

All proposed route names are design suggestions. Upstream APIs and the installed versions must be checked before coding. Preserve plain Android Views, the single-Activity screen lifecycle, cancellation on exit, the existing theme and in-layout overlays. Every action must work with controller, touch and pointer; settle focus after list changes and recompute contextual hints. Keep source failures explicit instead of showing stale data as healthy.

The proposal was grounded in `HANDOFF.md`, `CLAUDE.md`, `FUNCTIONALITY_AUDIT_2026-09-20.md`, `Theme.kt`, `HubSettings.kt`, `ManageScreen.kt`, `DownloadsScreen.kt`, the Hub router/activity/notifications/manage handlers and the existing Arr/Bazarr/qBittorrent adapters. No app or server functionality was changed for this research task.
