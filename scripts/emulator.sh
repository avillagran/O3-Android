#!/usr/bin/env bash
# Starts, waits for, drives and stops the project's Android emulator.
#
#   scripts/emulator.sh start [--avd NAME] [--headless]
#   scripts/emulator.sh wait [--timeout SECONDS]
#   scripts/emulator.sh serial
#   scripts/emulator.sh install <apk>
#   scripts/emulator.sh screenshot <out.png>
#   scripts/emulator.sh stop
#   scripts/emulator.sh status
#
# The script only ever talks to the emulator it started: every adb call passes
# `-s emulator-<port>`, and the port is not the emulator default (5554), so a phone on
# wireless adb or another project's emulator is never addressed. See docs/EMULATOR.md.
#
# Exit codes:
#   0  success
#   1  the emulator is not running (serial, status, install, screenshot)
#   2  usage error
#   3  the emulator failed to start or died while booting
#   4  timed out waiting for boot
#   5  an adb command against the emulator failed
#   6  the console port is already taken by an emulator this script did not start
#   7  missing prerequisite (SDK, system image, KVM)
#
# Environment:
#   ANDROID_HOME        SDK root (default ~/.android-sdk, then ~/Android/Sdk)
#   OT3A_EMU_AVD        default AVD name (ot3a)
#   OT3A_EMU_PORT       console port, even, 5554-5682 (default 5580 -> emulator-5580)
#   OT3A_EMU_STATE_DIR  where pid, serial and log live (default ~/.cache/ot3a-emulator)
#   OT3A_EMU_IMAGE      system image for new AVDs
#                       (system-images;android-34;google_apis;x86_64)
#   OT3A_EMU_RAM_MB     guest RAM for new AVDs (2048)
#   OT3A_EMU_CORES      guest vCPUs for new AVDs (4)
set -euo pipefail

die() { local code=$1; shift; echo "emulator.sh: $*" >&2; exit "$code"; }
log() { echo "emulator.sh: $*" >&2; }

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $SDK ]]; then
  for candidate in "$HOME/.android-sdk" "$HOME/Android/Sdk"; do
    [[ -d $candidate ]] && { SDK=$candidate; break; }
  done
fi
[[ -n $SDK && -d $SDK ]] || die 7 "no Android SDK (set ANDROID_HOME)"
export ANDROID_HOME=$SDK ANDROID_SDK_ROOT=$SDK

ADB=$SDK/platform-tools/adb
EMULATOR=$SDK/emulator/emulator
SDKMANAGER=$SDK/cmdline-tools/latest/bin/sdkmanager
AVDMANAGER=$SDK/cmdline-tools/latest/bin/avdmanager

AVD=${OT3A_EMU_AVD:-ot3a}
PORT=${OT3A_EMU_PORT:-5580}
STATE_DIR=${OT3A_EMU_STATE_DIR:-$HOME/.cache/ot3a-emulator}
IMAGE=${OT3A_EMU_IMAGE:-system-images;android-34;google_apis;x86_64}
RAM_MB=${OT3A_EMU_RAM_MB:-2048}
CORES=${OT3A_EMU_CORES:-4}

[[ $PORT =~ ^[0-9]+$ ]] && (( PORT >= 5554 && PORT <= 5682 && PORT % 2 == 0 )) ||
  die 2 "OT3A_EMU_PORT must be an even port between 5554 and 5682 (got $PORT)"
SERIAL=emulator-$PORT
PID_FILE=$STATE_DIR/pid
SERIAL_FILE=$STATE_DIR/serial
AVD_FILE=$STATE_DIR/avd
LOG_FILE=$STATE_DIR/emulator.log

# The only way this script runs adb. Refuses anything that is not an emulator serial.
adb_emu() {
  [[ $SERIAL =~ ^emulator-[0-9]+$ ]] || die 2 "refusing to address non-emulator serial '$SERIAL'"
  "$ADB" -s "$SERIAL" "$@"
}

# PID of the emulator we started, if it is still alive and still ours.
running_pid() {
  [[ -f $PID_FILE ]] || return 1
  local pid
  pid=$(<"$PID_FILE")
  [[ $pid =~ ^[0-9]+$ && -r /proc/$pid/cmdline ]] || return 1
  # The launcher execs qemu in place, so the PID is the qemu process once it is up.
  tr '\0' ' ' <"/proc/$pid/cmdline" | grep -Eq "(emulator|qemu-system)[^ ]* .*-port $PORT( |$)" || return 1
  echo "$pid"
}

booted() {
  [[ $(adb_emu shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]
}

clear_state() { rm -f "$PID_FILE" "$SERIAL_FILE" "$AVD_FILE"; }

ensure_avd() {
  local name=$1
  if "$EMULATOR" -list-avds 2>/dev/null | grep -Fxq "$name"; then
    return 0
  fi
  [[ -x $AVDMANAGER ]] || die 7 "no avdmanager at $AVDMANAGER (install cmdline-tools;latest)"
  local image_dir=$SDK/${IMAGE//;//}
  if [[ ! -d $image_dir ]]; then
    [[ -x $SDKMANAGER ]] || die 7 "system image $IMAGE missing and no sdkmanager to install it"
    log "installing $IMAGE"
    yes | "$SDKMANAGER" --install "$IMAGE" >/dev/null || die 7 "sdkmanager could not install $IMAGE"
  fi
  log "creating AVD $name ($IMAGE, pixel_6, ${RAM_MB} MB, $CORES cores)"
  echo no | "$AVDMANAGER" create avd -n "$name" -k "$IMAGE" -d pixel_6 >/dev/null ||
    die 7 "avdmanager could not create $name"
  local avd_home=${ANDROID_AVD_HOME:-${ANDROID_USER_HOME:-$HOME/.android}/avd}
  local config=$avd_home/$name.avd/config.ini
  [[ -f $config ]] || die 7 "AVD created but $config not found"
  set_config "$config" hw.ramSize "$RAM_MB"
  set_config "$config" hw.cpu.ncore "$CORES"
  set_config "$config" hw.gpu.enabled yes
  set_config "$config" hw.gpu.mode swiftshader_indirect
  set_config "$config" hw.keyboard yes
  set_config "$config" disk.dataPartition.size 6G
}

set_config() {
  local file=$1 key=$2 value=$3
  if grep -q "^$key[[:space:]]*=" "$file"; then
    sed -i "s|^$key[[:space:]]*=.*|$key = $value|" "$file"
  else
    echo "$key = $value" >>"$file"
  fi
}

cmd_start() {
  local avd=$AVD headless=0
  while (( $# )); do
    case $1 in
      --avd) [[ $# -ge 2 ]] || die 2 "--avd needs a name"; avd=$2; shift 2 ;;
      --headless) headless=1; shift ;;
      *) die 2 "start: unknown option $1" ;;
    esac
  done
  [[ -x $EMULATOR ]] || die 7 "no emulator at $EMULATOR (sdkmanager --install emulator)"
  [[ -x $ADB ]] || die 7 "no adb at $ADB (sdkmanager --install platform-tools)"
  [[ -r /dev/kvm && -w /dev/kvm ]] || die 7 "/dev/kvm is not usable by $(id -un) (add the user to group kvm)"

  local pid
  if pid=$(running_pid); then
    log "already running: $SERIAL (pid $pid, avd $(cat "$AVD_FILE" 2>/dev/null || echo '?'))"
    echo "$SERIAL"
    return 0
  fi
  clear_state
  # Another emulator (or anything) on our console port: never take it over.
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    die 6 "port $PORT is in use by something this script did not start; set OT3A_EMU_PORT"
  fi

  ensure_avd "$avd"

  if (( !headless )) && [[ -z ${DISPLAY:-}${WAYLAND_DISPLAY:-} ]]; then
    log "no display; starting headless"
    headless=1
  fi
  local args=(-avd "$avd" -port "$PORT" -no-audio -no-boot-anim -no-snapshot -no-metrics
              -gpu swiftshader_indirect)
  (( headless )) && args+=(-no-window)

  mkdir -p "$STATE_DIR"
  # Root cause of the 2026-10-24 boot failures (docs/EMULATOR.md): processes spawned from
  # the T3 server inherit a soft RLIMIT_NOFILE of 1073741816, and qemu walks every fd
  # number up to that limit (F_GETFD/F_SETFD) before each child it spawns, while holding
  # the big QEMU lock. Its hang detector then aborts it (exit 139). Lowering the soft limit
  # is always allowed and fixes it.
  local soft
  soft=$(ulimit -Sn)
  if [[ $soft == unlimited ]] || (( soft > 4096 )); then
    ulimit -Sn 4096
  fi

  # setsid: own session, so closing the caller's terminal or tool shell does not kill it.
  setsid "$EMULATOR" "${args[@]}" >"$LOG_FILE" 2>&1 </dev/null &
  pid=$!
  echo "$pid" >"$PID_FILE"
  echo "$SERIAL" >"$SERIAL_FILE"
  echo "$avd" >"$AVD_FILE"

  # Give the launcher time to exec qemu and fail fast on early errors.
  local i
  for i in $(seq 1 10); do
    sleep 1
    if ! kill -0 "$pid" 2>/dev/null; then
      clear_state
      tail -n 20 "$LOG_FILE" >&2 || true
      die 3 "emulator exited during startup (log: $LOG_FILE)"
    fi
  done
  log "started $SERIAL (pid $pid, avd $avd, log $LOG_FILE)"
  echo "$SERIAL"
}

cmd_wait() {
  local timeout=300
  while (( $# )); do
    case $1 in
      --timeout) [[ $# -ge 2 && $2 =~ ^[0-9]+$ ]] || die 2 "--timeout needs seconds"; timeout=$2; shift 2 ;;
      *) die 2 "wait: unknown option $1" ;;
    esac
  done
  running_pid >/dev/null || die 1 "not running (scripts/emulator.sh start)"
  local deadline=$((SECONDS + timeout))
  while (( SECONDS < deadline )); do
    if ! running_pid >/dev/null; then
      tail -n 20 "$LOG_FILE" >&2 || true
      clear_state
      die 3 "emulator died while booting (log: $LOG_FILE)"
    fi
    if booted; then
      log "$SERIAL booted"
      return 0
    fi
    sleep 2
  done
  die 4 "$SERIAL did not finish booting within ${timeout}s"
}

cmd_serial() {
  running_pid >/dev/null || die 1 "not running"
  echo "$SERIAL"
}

cmd_install() {
  [[ $# -eq 1 ]] || die 2 "usage: install <apk>"
  [[ -f $1 ]] || die 2 "no such apk: $1"
  running_pid >/dev/null || die 1 "not running"
  adb_emu install -r "$1" || die 5 "adb install -r failed"
}

cmd_screenshot() {
  [[ $# -eq 1 ]] || die 2 "usage: screenshot <out.png>"
  running_pid >/dev/null || die 1 "not running"
  local out=$1 tmp
  tmp=$(mktemp "${out}.XXXXXX")
  if ! adb_emu exec-out screencap -p >"$tmp"; then
    rm -f "$tmp"
    die 5 "screencap failed"
  fi
  # A PNG starts with \x89PNG; anything else is an adb error message.
  if [[ $(head -c 4 "$tmp" | od -An -tx1 | tr -d ' \n') != 89504e47 ]]; then
    rm -f "$tmp"
    die 5 "screencap did not return a PNG"
  fi
  mv "$tmp" "$out"
  echo "$out"
}

cmd_stop() {
  local pid
  if ! pid=$(running_pid); then
    clear_state
    log "not running"
    return 0
  fi
  adb_emu emu kill >/dev/null 2>&1 || true
  local i
  for i in $(seq 1 30); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "emu kill did not stop pid $pid in 30s; sending SIGTERM"
    kill -TERM "$pid" 2>/dev/null || true
    for i in $(seq 1 10); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -0 "$pid" 2>/dev/null && kill -KILL "$pid" 2>/dev/null || true
  fi
  clear_state
  log "stopped $SERIAL"
}

cmd_status() {
  local pid
  if ! pid=$(running_pid); then
    echo "stopped"
    return 1
  fi
  local state=booting
  booted && state=booted
  echo "$state $SERIAL pid=$pid avd=$(cat "$AVD_FILE" 2>/dev/null || echo '?')"
}

usage() { sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; }

(( $# )) || { usage >&2; exit 2; }
command=$1
shift
case $command in
  start) cmd_start "$@" ;;
  wait) cmd_wait "$@" ;;
  serial) cmd_serial "$@" ;;
  install) cmd_install "$@" ;;
  screenshot) cmd_screenshot "$@" ;;
  stop) cmd_stop "$@" ;;
  status) cmd_status "$@" ;;
  -h|--help|help) usage ;;
  *) usage >&2; exit 2 ;;
esac
