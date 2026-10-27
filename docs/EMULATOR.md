# Emulator

The project's emulator is the reference device for UI work: the screenshots in
`docs/ui/compare/<pr>/` and the instrumented tests run on it. `scripts/emulator.sh`
starts, waits for, drives and stops it. It never addresses any other device.

## Usage

```sh
scripts/emulator.sh start --headless   # create the AVD if missing, start detached, print the serial
scripts/emulator.sh wait               # block until sys.boot_completed=1 (default timeout 300 s)
scripts/emulator.sh install app/build/outputs/apk/debug/app-debug.apk   # adb install -r
scripts/emulator.sh screenshot /tmp/screen.png
scripts/emulator.sh status             # "booted emulator-5580 pid=... avd=ot3a"; exit 1 when stopped
scripts/emulator.sh stop               # adb emu kill, waits for the process to exit
```

Instrumented tests must target the emulator explicitly. Without `ANDROID_SERIAL`, AGP runs
`connectedAndroidTest` on **every** connected device, including a phone on wireless adb,
and uninstalls the app from each one afterwards:

```sh
ANDROID_SERIAL=$(scripts/emulator.sh serial) ./gradlew :core:connectedDebugAndroidTest
```

| Command | Notes |
|---|---|
| `start [--avd NAME] [--headless]` | AVD `ot3a` by default (`OT3A_EMU_AVD`). Without `--headless` the window opens if there is a display. Prints the serial and writes it, the PID and the log to `~/.cache/ot3a-emulator/` (`OT3A_EMU_STATE_DIR`). If the emulator is already running, it prints the serial and exits 0. |
| `wait [--timeout S]` | Exit 3 if the emulator dies while booting (prints the log tail), 4 on timeout. |
| `serial` | Prints `emulator-5580`; exit 1 if not running. |
| `install <apk>` | Always `install -r`; never uninstalls. |
| `screenshot <out.png>` | Fails (exit 5) unless adb returned a real PNG. |
| `stop` | `emu kill`, then SIGTERM/SIGKILL of its own PID only if it is still alive after 30 s. Idempotent. |
| `status` | Exit 0 when running, 1 when stopped. |

Exit codes: 0 ok, 1 not running, 2 usage, 3 failed to start or died, 4 boot timeout,
5 adb command failed, 6 console port taken by an emulator the script did not start,
7 missing SDK, system image or KVM.

Device isolation: the console port is 5580 (`OT3A_EMU_PORT`), not the default 5554, so the
serial is `emulator-5580`. Every adb call goes through one function that passes
`-s emulator-<port>` and refuses any serial that is not `emulator-N`. The script never runs
`adb devices`, `adb kill-server` or adb without `-s`.

## The working command

What `start --headless` runs (fd limit first, then the emulator detached):

```sh
ulimit -Sn 4096
setsid ~/.android-sdk/emulator/emulator -avd ot3a -port 5580 \
  -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect -no-window
```

On node (Ryzen 9 5900X, KVM through `kvm_amd`, emulator 37.2.12, image
`system-images;android-34;google_apis;x86_64`, AVD `ot3a`: pixel_6, 1080×2400 @ 420 dpi)
it reaches `sys.boot_completed=1` in about 30 s from a cold boot.

- **`ulimit -Sn 4096`** is the fix; see the root cause below. Lowering a soft limit needs
  no privileges.
- **`-gpu swiftshader_indirect`**: CPU rendering. It is deterministic across runs and hosts,
  which is what screenshot comparisons need, and it does not depend on node's GPU or on a
  display. It is also what CI uses.
- **`-no-snapshot`**: always a cold boot. A snapshot written while the host is under memory
  pressure, or by an emulator that was killed, can fail to load on the next start. 30 s is
  cheap. App data persists in the AVD's `userdata-qemu.img`.
- **Guest size**: 2048 MB RAM. New AVDs get 4 vCPUs (`OT3A_EMU_RAM_MB`, `OT3A_EMU_CORES`);
  the existing `ot3a` has 2 and boots just as fast. That is enough for the app and its
  tests and keeps the host footprint at about 4–5 GB RSS (qemu plus SwiftShader).
- `-no-metrics` and `-no-boot-anim` cut noise and boot time. `setsid` detaches it from the
  calling shell, so closing a terminal or an agent's tool shell does not kill it.

## Root cause of the 2026-10-24 breakage

From 2026-10-24 ~19:00, every boot on node ended the same way. About 15–20 s after
"Started GRPC server", the emulator logged `detected a hanging thread 'QEMU2 CPU0 thread'`
and `'QEMU2 main loop'` and exited with 139. The guest kernel never printed a line. This
happened with KVM and with `-accel off`, on emulator 37.2.12 and 36.6.11, and with every
GPU mode and AVD.

**Cause:** the soft `RLIMIT_NOFILE` of the launching process was **1073741816** (2³⁰−8).
At startup qemu spawns helpers (`adb version` several times, `netsimd`). Before each
spawn, the spawning thread walks **every fd number up to the soft limit**, calling
`fcntl(fd, F_GETFD)` / `fcntl(fd, F_SETFD, FD_CLOEXEC)` on each, while holding the big
QEMU lock. With a limit of 2³⁰ that walk takes minutes. The vCPU threads and the main loop
wait for the lock, and the emulator's own hang detector aborts the process after 15 s
(exit 139, a crashpad minidump in `/tmp/android-$USER/emu-crash-*.db`).

**Where the limit comes from:** `t3code.service` (systemd user unit, soft 1024 / hard
1073741816) starts the T3 server, which raises its own soft limit to the hard limit. Every
agent shell and every command it runs inherits soft = hard = 1073741816
(`grep 'open files' /proc/self/limits`). Processes from other trees on node, such as a
systemd service with the default soft 1024, are not affected. That is why the emulator
looked "broken on the machine" after it had worked. The emulator earlyoom killed at 19:00
must have been started from a context with a lower limit (inferred: its process and parent
are gone, so this cannot be checked); every relaunch afterwards came from an agent shell.
The earlyoom kill was a coincidence in time, not the cause: nothing it left
behind (stale `/tmp/android-node/*.qcow2`, crash dbs, lock files) mattered, and a fresh
`ANDROID_AVD_HOME`/`ANDROID_EMULATOR_HOME` failed the same way.

**Fix:** lower the soft limit before launching (`ulimit -Sn 4096`), as
`scripts/emulator.sh` does. Any other way of starting the emulator from a T3/agent shell
(Android Studio launched from it, `connectedAndroidTest` with a managed device, a bare
`emulator` command) needs the same `ulimit -Sn 4096` first.

## Avoiding earlyoom

earlyoom runs on node with its defaults (`EARLYOOM_ARGS="-r 3600"`). It sends SIGTERM to
the process with the highest `oom_score` once **both** available RAM and free swap fall to
10% or less (about 3 GB and 1.6 GB). The emulator is usually that process. On 2026-10-24
it killed `qemu-system-x86_64-headless -avd rvtest` at 14.6 GB RSS, from a 1536 MB guest
that had been running for hours, during a large Rust + Gradle build.

- **Stop it when you are done** (`scripts/emulator.sh stop`). qemu plus SwiftShader RSS
  grows with uptime (4.6 GB after 12 minutes of tests here, 14.6 GB for the long-running
  `rvtest`), and a fresh start costs 30 s.
- Keep the guest at 2 GB. A bigger guest adds nothing for this app and raises the RSS.
- When building in parallel, cap the builds rather than the emulator. Gradle is already
  limited to `-Xmx4g` (`gradle.properties`); use `--no-daemon` or `./gradlew --stop`
  afterwards so idle daemons do not hold memory, and limit Rust builds with
  `CARGO_BUILD_JOBS=8`. Check `free -m` before a big build: swap on node is often half full
  from other services, and swap being exhausted is what arms earlyoom.
- Optional, needs root: tell earlyoom to spare qemu and prefer compilers in
  `/etc/default/earlyoom`, e.g.
  `EARLYOOM_ARGS="-r 3600 --avoid '^qemu-system-x86$' --prefer '^(java|rustc|cc1plus|ld)$'"`
  (earlyoom matches the 15-character process name). Not applied.
- A cgroup memory cap (`systemd-run --user --scope -p MemoryMax=...`) does not help: the
  kernel OOM killer would kill qemu inside the scope instead.

`journalctl -u earlyoom` shows each kill with name, PID and RSS.

## CI

The `instrumented` job in `.github/workflows/android.yml` runs
`:core:connectedDebugAndroidTest` and `:app:connectedDebugAndroidTest` on ubuntu-latest with
`reactivecircus/android-emulator-runner@v2`: same image (API 34, google_apis, x86_64,
pixel_6, 2 GB), `-gpu swiftshader_indirect -no-snapshot`, animations off, KVM enabled
through a udev rule. Until UI-1f only `:core` ran there; `:app` joined once all its
instrumented tests passed on the reference emulator (2026-10-25: core 4/4, app 17/17). The
CI emulator is fresh per run, so the app's data starts empty.

### Window focus (2026-10-26)

Before either module’s connected tests, the shared Gradle task
`prepareInstrumentedEmulator` runs `scripts/emulator-ready.sh emulator-5554` on GitHub
Actions only (`ANDROID_SERIAL` overrides the serial). It is skipped on developer hosts
and never prepares a physical device. The existing workflow command is unchanged. It sets
`settings put global hide_error_dialogs 1`, closes any system dialog already showing, and
waits until no "Application Not Responding" or "keeps stopping" window has focus.

The reason: on a slow first boot (2 vCPUs, swiftshader) Pixel Launcher can hit an ANR
(`am_anr ... com.google.android.apps.nexuslauncher ... Input dispatching timed out
(Application does not have a focused window)`). The system ANR dialog then keeps input
focus while the activity under test is RESUMED. The app window never gets focus, which
breaks every test that needs it:

- the IME is never served (`ImeTracker ... onFailed at PHASE_CLIENT_REQUEST_IME_SHOW`, and
  the server never logs a `startInput` for the app),
- `Activity.hasWindowFocus()` stays false,
- `ClipboardManager.getPrimaryClip()` returns null, because Android 10+ only lets the
  focused app read the clipboard.

PR #23 (791b471, run 40273773467) and PR #25 (e7e33db, 672758d) failed this way. To reproduce
it locally, create a fresh AVD (`avdmanager create avd --force -n ot3a-ci ... --device
pixel_6`, plus `hw.cpu.ncore=2` and `hw.ramSize=2048M`). Boot it with `-wipe-data` and the CI
flags, with the emulator pinned to two host cores (`taskset -c 0,1`). Then send
`input keyevent 82` and run the full suite. When it fails, `dumpsys window` shows
`mCurrentFocus=Window{... Application Not Responding: com.google.android.apps.nexuslauncher}`.

When dialogs are hidden, the ANR still happens, but the system handles it without a window.
The tests' own assertions are unchanged.

## Evidence log (2026-10-25)

All runs were headless, from an agent shell on node, never addressing a physical device.

| # | What | Result |
|---|---|---|
| 1 | `free -m`, top RSS, `journalctl -u earlyoom` | 23 GB available; earlyoom's only kill was 2026-10-24 19:00:06 (rvtest, 14.6 GB RSS). No kills during any later attempt. |
| 2 | `/dev/kvm`, `kvm_amd` parameters, `emulator -accel-check`, other VMs | KVM usable (`npt=Y nested=1`), user in group `kvm`, no other qemu/firecracker/crosvm. Docker containers do not use `/dev/kvm`. |
| 3 | Kernel journal since 2026-10-24 18:50, apt/dpkg logs | No segfault, KVM or OOM lines; no package changes. `coredumpctl` not installed; apport is the core handler. |
| 4 | Fresh `ANDROID_AVD_HOME`/`ANDROID_EMULATOR_HOME`/`ANDROID_USER_HOME` under `/tmp/emu-home-20261025`, new AVD `iso1`, `-verbose -show-kernel` | Same failure: hanging `QEMU2 main loop` + CPU threads after ~15 s, exit 139 after 36 s, no guest kernel output. Shared state ruled out. |
| 5 | `/proc/<pid>/task/*/{stat,wchan}` sampled at 4/8/12 s | All threads asleep except one, always in state R. |
| 6 | gdb (`ptrace_scope=1`, so gdb exec'd from the parent script) `thread apply all bt` | The four vCPU threads block reacquiring the big QEMU lock in `pthread_cond_wait`; the running thread is in `fcntl`. |
| 7 | `strace -f -e fcntl,flock,openat,ioctl` | That thread made 1.1 M `fcntl(fd, F_GETFD)`/`F_SETFD FD_CLOEXEC` calls on fd 3, 4, … 548084 (EBADF) before the kill. `ulimit -n` = 1073741816. |
| 8 | `/proc/<pid>/limits` up the process tree, `systemctl --user show t3code.service` | `zsh` ← `claude` ← T3 server ← systemd user: soft = hard = 1073741816; the unit's soft limit is 1024, so the T3 server raises it itself. |
| 9 | Run 4 with only `ulimit -Sn 4096` added | `sys.boot_completed=1` after 30 s. |
| 10 | `strace -e execve,clone` with the fix | Children spawned during boot: crashpad_handler, `adb version` ×6, netsimd. |
| 11 | `scripts/emulator.sh start --headless` + `wait` on `ot3a` | Booted in ~40 s total; qemu's limits show soft 4096. `install -r`, launch, `screenshot` OK (1080×2400 PNG of the connect screen). |
| 12 | `ANDROID_SERIAL=emulator-5580 ./gradlew :core:connectedDebugAndroidTest` | 4/4 passed on `ot3a(AVD) - 14`. |
| 13 | `ANDROID_SERIAL=emulator-5580 ./gradlew :app:connectedDebugAndroidTest` | First run ever: 17/20 passed. 3 test failures unrelated to the emulator (see below). |
| 14 | `scripts/emulator.sh stop`, then a `/proc` scan | Stopped in 2 s; no qemu, netsimd or crashpad left. |

The app instrumented tests that fail (deterministic Compose assertions, to be fixed
separately): `ConnectScreenTest.aRejectedTokenIsReportedAndNeverShown` (assertion at
`ConnectScreenTest.kt:101`), `SidebarScreenTest.rendersSectionsGroupsAndStatuses` (the
status text is only in the unmerged semantics tree), and
`SidebarScreenTest.anErrorThatIsNotRetriedOffersEdit` (the connection chip lacks the
expected error text).

## Android 36.1 Live Update image

Use a separate AVD; API34 remains the regression reference. On node:

```sh
OT3A_EMU_TOOLS="$ANDROID_HOME/cmdline-tools/22.0" \
OT3A_EMU_IMAGE='system-images;android-36.1;google_apis;x86_64' \
OT3A_EMU_GPU=swiftshader scripts/emulator.sh start --avd ot3a361 --headless
```

The directory called cmdline-tools/latest on this host contained tools12.0. It created
36.1 image rev4 metadata with target=android-0. Both swiftshader_indirect and swiftshader
then reached boot_completed=1 but SurfaceFlinger repeatedly aborted in RegionSampling
with `!rcEnc->featureInfo()->hasReadColorBufferDma`; package installation could also
fail while system_server restarted. Changing the renderer alone did not fix it.
Tools22.0 installed alongside latest recreated only this new QA AVD with target=android-36.1.
The next run kept system_server623/surfaceflinger767 stable through APK install, native
Settings navigation and notification permission flow, with an empty crash buffer.
The script now rejects target=android-0 and exposes explicit tools/renderer overrides.
This is a local A/B observation, not proof that every newer image is compatible.
A [matching first-hand runner-images report](https://github.com/actions/runner-images/issues/14484)
was used to select the metadata hypothesis; the verification above is local.

## A private COW clone when the disk is tight

A new API34 AVD needs about 7.4 GB of userdata (the emulator refuses to start below its
disk check), which the host does not always have. A clone that shares `ot3a`'s images
through qcow2 overlays boots in the same ~30 s and costs a few hundred MB:

```sh
avd=~/.android/avd
clone=$avd/ot3a-sky.avd
mkdir -p "$clone"
cp "$avd/ot3a.avd"/{config.ini,initrd,userdata.img,version_num.cache,netsim.ini} "$clone"/
cp --sparse=always "$avd/ot3a.avd"/{userdata-qemu.img,cache.img,encryptionkey.img} "$clone"/
qemu-img create -q -f qcow2 -F qcow2 -b "$avd/ot3a.avd/userdata-qemu.img.qcow2" "$clone/userdata-qemu.img.qcow2"
qemu-img create -q -f qcow2 -F qcow2 -b "$avd/ot3a.avd/cache.img.qcow2" "$clone/cache.img.qcow2"
qemu-img create -q -f qcow2 -F qcow2 -b "$avd/ot3a.avd/encryptionkey.img.qcow2" "$clone/encryptionkey.img.qcow2"
sed -i 's/^avd.ini.displayname=.*/avd.ini.displayname=ot3a-sky/' "$clone/config.ini"
printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/ot3a-sky.avd\ntarget=android-34\n' "$clone" > "$avd/ot3a-sky.ini"
OT3A_EMU_AVD=ot3a-sky OT3A_EMU_STATE_DIR=~/.cache/ot3a-sky-emulator scripts/emulator.sh start --headless
```

The emulator writes only to the overlays; the backing images are opened read-only. Hash
`userdata-qemu.img.qcow2`, `cache.img.qcow2` and `encryptionkey.img.qcow2` before and
after a session to prove the original AVD was not touched. Copy the small raw images as
well: without `userdata-qemu.img` in the clone the emulator decides there is no data
partition and stops at `Not enough space to create userdata partition` asking for 7.37 GB.
The Device panel's `device_open` uses its own launcher, which fails the same way on a tight
disk; `scripts/emulator.sh` is the working path. A clone is not a snapshot: it inherits
the source AVD's apps and settings, so install the APK under test and set or reset the
animation scales yourself (`settings put global animator_duration_scale 1` for motion
captures, `0` for one static frame — the emulator images ship with 0).
