#!/usr/bin/env bash
# The Apple client's dev.sh, run on the MacBook.
#
#   scripts/mac.sh project          generate apple/Hub.xcodeproj from apple/project.yml
#   scripts/mac.sh test             HubKit tests (swift test), the JVM-test equivalent
#   scripts/mac.sh build            build for the iOS Simulator and for the Mac
#   scripts/mac.sh sims [-demo]     boot iPad Pro 13", iPad mini and iPhone; install,
#                                   launch and screenshot each into shots/apple/
#   scripts/mac.sh shot [-demo]     relaunch on the booted simulators and screenshot again
#   scripts/mac.sh capture          screenshot the booted simulators as they are, no relaunch
#   scripts/mac.sh transparency reduce|normal
#                                   turn the simulators' Reduce transparency on or off
#   scripts/mac.sh uitest           the UI tests on the iPhone simulator, against -demo
#   scripts/mac.sh mac [-demo]      build and run the Mac app
#   scripts/mac.sh logs             stream the app's log from the booted simulators
#
# A hub address and token in apple/dev.env (gitignored) are passed to Debug
# builds on launch, the way dev.sh seed does on the Pocket DS:
#   HUB_URL=https://ayaneo-media-pc.tail737e96.ts.net
#   HUB_TOKEN=...
# -demo runs against built-in fixtures instead, for layout work without a hub.
#
# Debug launches also take HUB_PLAY=<item id> (the player opens on it; "demo-e5"
# with -demo), HUB_PLAY_EXIT=<seconds> (it leaves through Back's own path, so a
# run against the real hub never leaves a session open), HUB_PLAY_CHROME=pinned
# and, with -demo, HUB_PLAY_FROM_END=<seconds>. SHOT_SIMS="iPad Pro 13-inch (M5),
# iPhone 17 Pro" limits sims and shot to those simulators.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APPLE="$ROOT/apple"
DERIVED="$APPLE/build"
BUNDLE_ID="com.pocketds.hub"
SHOTS="$ROOT/shots/apple"
# The sizes the user owns: iPad Pro 12.9"/13", iPad mini, iPhone.
SIMS=("iPad Pro 13-inch (M5)" "iPad mini (A17 Pro)" "iPhone 17 Pro")

# scripts/mac-remote.sh runs this from a build copy that has no dev.env of its
# own and points HUB_DEV_ENV at the Mac checkout's, so the token is never copied.
DEV_ENV="${HUB_DEV_ENV:-$APPLE/dev.env}"
if [[ -f "$DEV_ENV" ]]; then
  # shellcheck disable=SC1090
  source "$DEV_ENV"
fi

project() {
  (cd "$APPLE" && xcodegen generate --spec project.yml --quiet)
}

# The simulators to use: SHOT_SIMS, comma-separated, or all three.
selected_sims() {
  if [[ -z "${SHOT_SIMS:-}" ]]; then
    printf '%s\n' "${SIMS[@]}"
  else
    tr ',' '\n' <<< "$SHOT_SIMS"
  fi
}

udid_of() {
  xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
for runtime, devices in json.load(sys.stdin)["devices"].items():
    for d in devices:
        if d["name"] == name and "iOS" in runtime:
            print(d["udid"]); sys.exit(0)
sys.exit("no simulator named " + name)' "$1"
}

build_ios() {
  project
  xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
    -destination 'generic/platform=iOS Simulator' -derivedDataPath "$DERIVED" -quiet build
}

# Until the Apple Developer Program enrolment gives a team id, the Mac app is
# signed ad hoc so its sandbox entitlements apply. Set APPLE_TEAM_ID in
# apple/dev.env once there is one.
build_mac() {
  project
  local signing=(CODE_SIGN_STYLE=Manual CODE_SIGN_IDENTITY=- DEVELOPMENT_TEAM=)
  if [[ -n "${APPLE_TEAM_ID:-}" ]]; then
    signing=(DEVELOPMENT_TEAM="$APPLE_TEAM_ID" -allowProvisioningUpdates)
  fi
  xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
    -destination 'platform=macOS' -derivedDataPath "$DERIVED" -quiet "${signing[@]}" build
}

app_ios() { echo "$DERIVED/Build/Products/Debug-iphonesimulator/Hub.app"; }
app_mac() { echo "$DERIVED/Build/Products/Debug/Hub.app"; }

launch_args() {
  local args=()
  for arg in "$@"; do
    [[ "$arg" == "-demo" ]] && args+=("-demo")
  done
  echo "${args[@]:-}"
}

launch_sim() {
  local udid="$1"; shift
  xcrun simctl terminate "$udid" "$BUNDLE_ID" >/dev/null 2>&1 || true
  # SIMCTL_CHILD_ variables reach the app's environment without the token
  # ever being written into the simulator.
  SIMCTL_CHILD_HUB_URL="${HUB_URL:-}" SIMCTL_CHILD_HUB_TOKEN="${HUB_TOKEN:-}" \
    SIMCTL_CHILD_HUB_SECTION="${HUB_SECTION:-}" SIMCTL_CHILD_HUB_OPEN="${HUB_OPEN:-}" \
    SIMCTL_CHILD_HUB_SIDE="${HUB_SIDE:-}" SIMCTL_CHILD_HUB_SHEET="${HUB_SHEET:-}" \
    SIMCTL_CHILD_HUB_PLAY="${HUB_PLAY:-}" SIMCTL_CHILD_HUB_PLAY_EXIT="${HUB_PLAY_EXIT:-}" \
    SIMCTL_CHILD_HUB_PLAY_CHROME="${HUB_PLAY_CHROME:-}" SIMCTL_CHILD_HUB_PLAY_FROM_END="${HUB_PLAY_FROM_END:-}" \
    xcrun simctl launch "$udid" "$BUNDLE_ID" $(launch_args "$@") >/dev/null
}

shoot() {
  mkdir -p "$SHOTS"
  local wait="${SHOT_WAIT:-3}"
  sleep "$wait"
  while IFS= read -r name; do
    local udid file
    udid="$(udid_of "$name")"
    file="$SHOTS/$(echo "$name" | tr -cd '[:alnum:]-').png"
    xcrun simctl io "$udid" screenshot "$file" >/dev/null 2>&1 && echo "$file"
  done < <(selected_sims)
}

sims() {
  build_ios
  while IFS= read -r name; do
    local udid
    udid="$(udid_of "$name")"
    xcrun simctl boot "$udid" >/dev/null 2>&1 || true
    xcrun simctl bootstatus "$udid" -b >/dev/null
    xcrun simctl install "$udid" "$(app_ios)"
    launch_sim "$udid" "$@"
  done < <(selected_sims)
  shoot
}

shot() {
  while IFS= read -r name; do
    launch_sim "$(udid_of "$name")" "$@"
  done < <(selected_sims)
  shoot
}

# The UI tests (apple/HubUITests) on the iPhone simulator. They launch the app
# with -demo, so they never touch the real hub.
uitest() {
  project
  local udid
  udid="$(udid_of "iPhone 17 Pro")"
  xcrun simctl boot "$udid" >/dev/null 2>&1 || true
  xcodebuild -project "$APPLE/Hub.xcodeproj" -scheme Hub -configuration Debug \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath "$DERIVED" test 2>&1 |
    grep -E "Test Case|Test Suite|error:|failed|passed|TEST (SUCCEEDED|FAILED)|\*\*" || true
}

# Reduce transparency on the three simulators: an accessibility setting inside
# each simulator, not the Mac's. Relaunch with `shot` to see it.
transparency() {
  local value
  case "${1:-}" in
    reduce) value=YES ;;
    normal) value=NO ;;
    *) echo "scripts/mac.sh transparency reduce|normal"; exit 2 ;;
  esac
  for name in "${SIMS[@]}"; do
    local udid
    udid="$(udid_of "$name")"
    xcrun simctl spawn "$udid" defaults write com.apple.Accessibility EnhancedBackgroundContrastEnabled -bool "$value"
    xcrun simctl spawn "$udid" notifyutil -p com.apple.accessibility.cache.enhance.background.contrast >/dev/null 2>&1 || true
    echo "$name: reduce transparency $1"
  done
}

mac() {
  build_mac
  # Only this build of the Mac app: the simulators run apps named Hub too.
  pkill -f "$(app_mac)/Contents/MacOS/Hub" >/dev/null 2>&1 || true
  # Detached, with its output in a file: an SSH session that starts it
  # (scripts/mac-remote.sh) otherwise stays open as long as the app runs.
  HUB_URL="${HUB_URL:-}" HUB_TOKEN="${HUB_TOKEN:-}" nohup "$(app_mac)/Contents/MacOS/Hub" $(launch_args "$@") \
    > "$DERIVED/mac-app.log" 2>&1 < /dev/null &
  echo "started $(app_mac); its output goes to $DERIVED/mac-app.log"
}

case "${1:-build}" in
  project) project ;;
  test) (cd "$APPLE/HubKit" && swift test) ;;
  build) build_ios && build_mac ;;
  sims) shift; sims "$@" ;;
  shot) shift; shot "$@" ;;
  capture) SHOT_WAIT=0 shoot ;;
  transparency) shift; transparency "$@" ;;
  uitest) uitest ;;
  mac) shift; mac "$@" ;;
  logs) xcrun simctl spawn booted log stream --level debug --predicate "subsystem == '$BUNDLE_ID' OR process == 'Hub'" ;;
  *) sed -n '2,27p' "$0"; exit 2 ;;
esac
