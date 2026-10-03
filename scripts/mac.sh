#!/usr/bin/env bash
# The Apple client's dev.sh, run on the MacBook.
#
#   scripts/mac.sh project          generate apple/Hub.xcodeproj from apple/project.yml
#   scripts/mac.sh test             HubKit tests (swift test), the JVM-test equivalent
#   scripts/mac.sh build            build for the iOS Simulator and for the Mac
#   scripts/mac.sh sims [-demo]     boot iPad Pro 13", iPad mini and iPhone; install,
#                                   launch and screenshot each into shots/apple/
#   scripts/mac.sh shot [-demo]     relaunch on the booted simulators and screenshot again
#   scripts/mac.sh mac [-demo]      build and run the Mac app
#   scripts/mac.sh logs             stream the app's log from the booted simulators
#
# A hub address and token in apple/dev.env (gitignored) are passed to Debug
# builds on launch, the way dev.sh seed does on the Pocket DS:
#   HUB_URL=https://ayaneo-media-pc.tail737e96.ts.net
#   HUB_TOKEN=...
# -demo runs against built-in fixtures instead, for layout work without a hub.
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
    xcrun simctl launch "$udid" "$BUNDLE_ID" $(launch_args "$@") >/dev/null
}

shoot() {
  mkdir -p "$SHOTS"
  local wait="${SHOT_WAIT:-3}"
  sleep "$wait"
  for name in "${SIMS[@]}"; do
    local udid file
    udid="$(udid_of "$name")"
    file="$SHOTS/$(echo "$name" | tr -cd '[:alnum:]-').png"
    xcrun simctl io "$udid" screenshot "$file" >/dev/null 2>&1 && echo "$file"
  done
}

sims() {
  build_ios
  for name in "${SIMS[@]}"; do
    local udid
    udid="$(udid_of "$name")"
    xcrun simctl boot "$udid" >/dev/null 2>&1 || true
    xcrun simctl bootstatus "$udid" -b >/dev/null
    xcrun simctl install "$udid" "$(app_ios)"
    launch_sim "$udid" "$@"
  done
  shoot
}

shot() {
  for name in "${SIMS[@]}"; do
    launch_sim "$(udid_of "$name")" "$@"
  done
  shoot
}

mac() {
  build_mac
  pkill -x Hub >/dev/null 2>&1 || true
  HUB_URL="${HUB_URL:-}" HUB_TOKEN="${HUB_TOKEN:-}" "$(app_mac)/Contents/MacOS/Hub" $(launch_args "$@") &
  echo "started $(app_mac)"
}

case "${1:-build}" in
  project) project ;;
  test) (cd "$APPLE/HubKit" && swift test) ;;
  build) build_ios && build_mac ;;
  sims) shift; sims "$@" ;;
  shot) shift; shot "$@" ;;
  mac) shift; mac "$@" ;;
  logs) xcrun simctl spawn booted log stream --level debug --predicate "subsystem == '$BUNDLE_ID' OR process == 'Hub'" ;;
  *) sed -n '2,17p' "$0"; exit 2 ;;
esac
