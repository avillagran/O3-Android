#!/usr/bin/env bash
# Makes a freshly booted emulator ready for instrumented tests that need window focus.
#
#   scripts/emulator-ready.sh <emulator-serial> [--timeout SECONDS]
#
# On a slow first boot (CI runners, 2 vCPUs, swiftshader) the Pixel Launcher can hit an
# ANR. Its "Application Not Responding" dialog is a system window that keeps input focus
# while the activity under test is RESUMED: the app window never gets focus, so the real
# IME is never served and the clipboard denies reads (Android 10+). Observed with
# `dumpsys window`: mCurrentFocus=Window{... Application Not Responding:
# com.google.android.apps.nexuslauncher}. See docs/EMULATOR.md.
#
# This hides system error dialogs (ANR/crash dialogs of other apps; the app's own
# failures still fail the tests), closes any dialog already shown and waits until no
# such dialog holds focus. It never touches a non-emulator serial.
#
# Exit codes: 0 ready, 2 usage, 4 a system dialog still holds focus after the timeout,
# 5 an adb command failed.
set -euo pipefail

die() { local code=$1; shift; echo "emulator-ready.sh: $*" >&2; exit "$code"; }

serial=${1:-}
shift || true
timeout=60
while (( $# )); do
  case $1 in
    --timeout) timeout=${2:?}; shift 2 ;;
    *) die 2 "unknown argument $1" ;;
  esac
done
[[ $serial =~ ^emulator-[0-9]+$ ]] || die 2 "usage: emulator-ready.sh <emulator-serial> (got '$serial')"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android-sdk}}
adb_emu() { "$SDK/platform-tools/adb" -s "$serial" "$@"; }

focus() { adb_emu shell dumpsys window | sed -n 's/^ *mCurrentFocus=//p' | tail -1; }

adb_emu shell settings put global hide_error_dialogs 1 || die 5 "cannot hide error dialogs"
deadline=$(( SECONDS + timeout ))
while :; do
  current=$(focus) || die 5 "cannot read the focused window"
  if [[ $current != *"Not Responding"* && $current != *"isn't responding"* && $current != *"keeps stopping"* ]]; then
    echo "emulator-ready.sh: focus $current"
    exit 0
  fi
  (( SECONDS < deadline )) || die 4 "a system dialog still holds focus: $current"
  echo "emulator-ready.sh: closing system dialog $current"
  adb_emu shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null || die 5 "cannot close system dialogs"
  sleep 2
done
