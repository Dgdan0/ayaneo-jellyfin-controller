#!/usr/bin/env bash
# One command per thing you actually do while building this app on the Ayaneo.
#
#   scripts/dev.sh              build, install, relaunch, then print state
#   scripts/dev.sh test         JVM unit tests -- the one that runs fifty times
#                               a feature, so it gets the short name
#   scripts/dev.sh pad          what the kernel and framework think the gamepad
#                               is: sources, axes, and their declared dead zones
#   scripts/dev.sh display      screen size and density; every dp number in the
#                               app depends on these, so run it once up front
#   scripts/dev.sh keys off|on  remove/restore the vendor's key-filtering
#                               accessibility service. This is the A/B test for
#                               "is something eating our face buttons?"
#   scripts/dev.sh seed         push hub URL + token from scripts/dev.env
#   scripts/dev.sh log          follow our own trace lines only
#   scripts/dev.sh log-all      follow everything except known vendor spam
#   scripts/dev.sh trace        one-shot: the last 40 of our trace lines
#   scripts/dev.sh cache clear  drop the HTTP/image caches, keeping the token
#   scripts/dev.sh shot         grab both screens into ./shots/
#   scripts/dev.sh state        one-shot summary
#
# Set POCKETDS_DEVICE to pin a device, otherwise the sole attached one is used.
set -euo pipefail

# $USER is not always set (Git Bash sets USERNAME instead, and neither is
# guaranteed), so the home directory is what the default path hangs off.
ADB="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}/platform-tools/adb.exe"
PKG=com.pocketds.hub
ACTIVITY="$PKG/.HubActivity"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# The vendor accessibility service that filters hardware keys. Matched on class
# name rather than the full string, because the same service is written either
# fully-qualified (pkg/pkg.path.Class) or shorthand (pkg/.path.Class) depending
# on who wrote the setting last.
VENDOR_A11Y_CLASS="WindowKeyEventService"
A11Y_BACKUP="$ROOT/.dev-a11y-backup"

# The wireless-debugging port is reassigned every time the service restarts, so
# never hardcode it: mDNS discovery finds the device on its own as long as
# Wireless debugging is on and we have been paired once (pairing is permanent).
resolve_device() {
  if [[ -n "${POCKETDS_DEVICE:-}" ]]; then echo "$POCKETDS_DEVICE"; return; fi
  local found
  found="$("$ADB" devices | awk '/_adb-tls-connect|^emulator|:[0-9]+\tdevice/ {print $1}' | head -1)"
  if [[ -z "$found" ]]; then
    "$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $1}' | head -1 \
      | while read -r name; do "$ADB" connect "$name" >/dev/null 2>&1 || true; done
    found="$("$ADB" devices | awk '/_adb-tls-connect|:[0-9]+\tdevice/ {print $1}' | head -1)"
  fi
  if [[ -z "$found" ]]; then
    echo "no device found. Turn on Settings > Developer options > Wireless debugging" >&2
    exit 1
  fi
  echo "$found"
}
DEVICE="$(resolve_device)"
adbx() { "$ADB" -s "$DEVICE" "$@"; }

# The vendor's screencap wants the display's uniqueId, not the 0/2 index the
# rest of the framework uses. Top screen is the default, so it needs no flag.
# Looked up rather than hardcoded: the bottom display gets torn down and
# recreated (its framework id has been seen going 2 -> 4), and a stale id
# silently captures the wrong buffer. port=132 is its fixed hardware address.
bottom_display_id() {
  adbx shell dumpsys SurfaceFlinger --display-id | awk '/port=132/ {print $2; exit}'
}

# Vendor firmware logs thermal sensors and display-compositor housekeeping many
# times a second, which buries anything useful within a couple of seconds.
NOISE='thermal|SDM *:|ANDR-PERF|libdisplayconfigqti|InputDispatcher|xsu|vendor.qti|CoreBackPreview|avc: denied'

read_a11y() {
  local current
  current="$(adbx shell settings get secure enabled_accessibility_services | tr -d '\r')"
  [[ "$current" == "null" ]] && current=""
  echo "$current"
}

case "${1:-deploy}" in
  deploy)
    echo "== build =="
    (cd "$ROOT" && ./gradlew.bat assembleDebug -q)
    echo "== install =="
    adbx install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    # am start immediately after install -r occasionally races the package
    # manager and reports the activity does not exist.
    sleep 1
    echo "== launch =="
    adbx shell am start -n "$ACTIVITY" >/dev/null
    sleep 1
    "$0" state
    ;;

  test)
    (cd "$ROOT" && ./gradlew.bat testDebugUnitTest --console=plain)
    ;;

  pad)
    echo "== framework view: input devices =="
    # Wanted: a device whose sources include JOYSTICK. Gamepad-but-not-joystick
    # means the sticks are not reporting axes, which is the firmware being in a
    # stick-to-mouse or stick-to-WASD mode -- the app's stick navigation is dead
    # in the water until that is turned off.
    adbx shell dumpsys input | sed -n '/Input Devices/,/^$/p'
    echo
    echo "== kernel view: raw devices and their axes =="
    # Below any Android-level filtering, so it also shows buttons the framework
    # never hands us.
    adbx shell getevent -pl 2>/dev/null | grep -E 'add device|name:|ABS_|BTN_' || true
    ;;

  display)
    echo "size    : $(adbx shell wm size | tr -d '\r')"
    echo "density : $(adbx shell wm density | tr -d '\r')"
    adbx shell dumpsys display | grep -iE 'mBaseDisplayInfo|density' | head -8
    ;;

  keys)
    case "${2:-}" in
      off)
        current="$(read_a11y)"
        if [[ "$current" != *"$VENDOR_A11Y_CLASS"* ]]; then
          echo "vendor service is already absent; nothing to do"
          exit 0
        fi
        # Back up the whole original string rather than reconstructing it, so
        # restoring cannot mangle entries this script never understood.
        printf '%s' "$current" > "$A11Y_BACKUP"
        # The setting is a colon-separated list. Filtering one entry out and
        # writing the rest is safe; writing only ours would silently disable
        # everything else the user relies on.
        filtered="$(printf '%s' "$current" | tr ':' '\n' \
          | grep -v "$VENDOR_A11Y_CLASS" | paste -sd: -)"
        adbx shell settings put secure enabled_accessibility_services "$filtered"
        echo "vendor $VENDOR_A11Y_CLASS disabled (backup in $(basename "$A11Y_BACKUP"))"
        echo "now compare the probe screen: buttons that appear only in this state"
        echo "were being eaten by the vendor service."
        ;;
      on)
        if [[ ! -f "$A11Y_BACKUP" ]]; then
          echo "no backup at $A11Y_BACKUP -- re-enable it in Settings > Accessibility" >&2
          exit 1
        fi
        adbx shell settings put secure enabled_accessibility_services "$(cat "$A11Y_BACKUP")"
        adbx shell settings put secure accessibility_enabled 1
        rm -f "$A11Y_BACKUP"
        echo "vendor service restored"
        ;;
      *)
        echo "current: $(read_a11y)"
        echo "usage: $0 keys off|on" >&2
        exit 1
        ;;
    esac
    ;;

  seed)
    # Typing a 40-character bearer token on a handheld after every clean install
    # is a reason not to test, so it goes in by intent extra instead.
    ENV_FILE="$ROOT/scripts/dev.env"
    if [[ ! -f "$ENV_FILE" ]]; then
      echo "create $ENV_FILE (gitignored) with:" >&2
      echo "  HUB_URL=https://myjellydan.duckdns.org" >&2
      echo "  HUB_TOKEN=..." >&2
      exit 1
    fi
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    adbx shell am start -n "$ACTIVITY" \
      -e hub_url "${HUB_URL:?set HUB_URL in scripts/dev.env}" \
      -e hub_token "${HUB_TOKEN:?set HUB_TOKEN in scripts/dev.env}" >/dev/null
    echo "seeded ${HUB_URL}"
    ;;

  log)
    adbx logcat -c
    echo "following PocketDSHub trace (ctrl-c to stop)"
    adbx logcat -s PocketDSHub:D
    ;;

  log-all)
    adbx logcat -c
    adbx logcat | grep -Ev "$NOISE"
    ;;

  trace)
    adbx logcat -d -s PocketDSHub:D | tail -40
    ;;

  cache)
    if [[ "${2:-}" != "clear" ]]; then echo "usage: $0 cache clear" >&2; exit 1; fi
    # run-as, not pm clear: pm clear would also wipe the stored hub URL and
    # token, which is the opposite of convenient when measuring cold loads.
    adbx shell run-as "$PKG" rm -rf cache
    echo "caches dropped; token and settings kept"
    ;;

  shot)
    mkdir -p "$ROOT/shots"
    adbx exec-out screencap -p > "$ROOT/shots/top.png"
    bottom="$(bottom_display_id)"
    if [[ -z "$bottom" ]]; then
      echo "could not resolve the bottom display id; only wrote top.png" >&2
    else
      adbx exec-out screencap -p -d "$bottom" > "$ROOT/shots/bottom.png"
    fi
    echo "wrote shots/top.png${bottom:+ and shots/bottom.png (display $bottom)}"
    ;;

  state)
    echo "device   : $DEVICE"
    echo "resumed  : $(adbx shell dumpsys activity activities \
      | grep -m1 topResumedActivity | tr -d '\r' | sed 's/^ *//')"
    a11y="$(read_a11y)"
    if [[ "$a11y" == *"$VENDOR_A11Y_CLASS"* ]]; then
      echo "vendor a11y: ON  (it sees key events before we do)"
    else
      echo "vendor a11y: off (dev.sh keys on to restore)"
    fi
    pads="$(adbx shell dumpsys input | grep -c 'Classes:.*JOYSTICK' || true)"
    echo "joystick devs: ${pads:-0}"
    echo "-- last trace lines --"
    adbx logcat -d -s PocketDSHub:D | tail -8
    ;;

  *)
    echo "unknown command: $1" >&2
    exit 1
    ;;
esac
