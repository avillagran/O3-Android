#!/usr/bin/env bash
# Build the separately pinned, unreleased bridge SDK. Does not alter the T3 AAR.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
bridge_source=${OMABRIDGE_DIR:-$HOME/Work/omabridge}
source_pin=$(sed -n 's/^source=//p' "$root/libs/omabridge-native.lock")
[[ $(git -C "$bridge_source" rev-parse HEAD) == "$source_pin" ]] || { echo 'OmaBridge checkout does not match libs/omabridge-native.lock' >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/jdk}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$HOME/.android-sdk/ndk/28.2.13676358}
export OMABRIDGE_JNI_SYMBOL=Java_dev_avillagran_ot3android_core_bridge_NativeBridge_call
"$bridge_source/mobile-sdk/build-jni.sh" host "$root/libs/host/linux-x86_64"
"$bridge_source/mobile-sdk/build-jni.sh" x86_64 "$root/core/src/main/jniLibs/x86_64"
"$bridge_source/mobile-sdk/build-jni.sh" arm64-v8a "$root/core/src/main/jniLibs/arm64-v8a"
