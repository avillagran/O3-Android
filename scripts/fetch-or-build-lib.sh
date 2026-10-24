#!/usr/bin/env bash
# Build (or download) the lib-t3-rust Android AAR into libs/ and, on x86_64 Linux,
# a host JNI library for JVM unit tests. Never installs SDKs, targets or toolchains.
# See docs/LIB_BUILD.md.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/fetch-or-build-lib.sh [options]

Default: build the AAR from the current HEAD of the lib-t3-rust checkout (normally
a release tag such as v0.3.0), then build the host JNI library when the host is
x86_64 Linux.

  --release <tag>   Download the AAR from a GitHub release (e.g. v0.2.0) instead
                    of building it; the checksum is verified.
  --host-jni        Build the host JNI library even when not built by default
                    (for example together with --release).
  --host-jni-only   Build only the host JNI library; leave the AAR untouched.
  --no-host-jni     Skip the host JNI library.
  --lib-fixtures    Also run the lib's own host fixtures (tools/test-jni and
                    tools/test-kotlin-jni) against its checkout; needs an org.json
                    jar (JSON_JAR or the Gradle/Maven cache).
  --force           Rebuild/download even if libs/ already matches the source.
  -h, --help        Show this help.

Environment overrides: LIB_T3_RUST_DIR, LIB_T3_RUST_REPO, JAVA_HOME, ANDROID_HOME,
ANDROID_NDK_HOME, ANDROID_JAR, ANDROID_PLATFORM, KOTLIN_VERSION, KOTLIN_STDLIB,
KOTLIN_COMPILER_CLASSPATH, JSON_JAR, GRADLE_USER_HOME, CARGO_BUILD_JOBS,
CARGO_TARGET_DIR.
EOF
}

die() { printf 'fetch-or-build-lib: %s\n' "$*" >&2; exit 1; }
tmp_dir=""
trap '[[ -z $tmp_dir ]] || rm -rf -- "$tmp_dir"' EXIT
log() { printf '==> %s\n' "$*"; }

repo_root=$(cd -- "$(dirname -- "$0")/.." && pwd)
libs_dir="$repo_root/libs"
aar="$libs_dir/lib-t3-rust-android.aar"
aar_sum="$aar.sha256"
aar_props="$libs_dir/lib-t3-rust-android.properties"
host_dir="$libs_dir/host/linux-x86_64"
lib_repo=${LIB_T3_RUST_REPO:-avillagran/lib-t3-rust}

release_tag=""
force=0
want_aar=1
host_jni=auto
lib_fixtures=0
while (($#)); do
  case "$1" in
    --release) (($# >= 2)) || die "--release needs a tag, e.g. v0.2.0"; release_tag=$2; shift;;
    --release=*) release_tag=${1#--release=};;
    --host-jni) host_jni=yes;;
    --host-jni-only) host_jni=yes; want_aar=0;;
    --no-host-jni) host_jni=no;;
    --lib-fixtures) lib_fixtures=1;;
    --force) force=1;;
    -h|--help) usage; exit 0;;
    *) usage >&2; die "unknown option: $1";;
  esac
  shift
done
[[ -z $release_tag || $release_tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]] \
  || die "release tag must look like v0.2.0, got: $release_tag"
if [[ $host_jni == auto ]]; then
  # The host library is built from source; with --release it is opt-in.
  if [[ $(uname -s) == Linux && $(uname -m) == x86_64 && -z $release_tag ]]; then host_jni=yes; else host_jni=no; fi
fi

# Toolchain defaults for the development machine; any of them can be overridden.
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/jdk}
export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android-sdk}}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$ANDROID_HOME}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}
export CARGO_BUILD_JOBS=${CARGO_BUILD_JOBS:-8}
export PATH="$JAVA_HOME/bin:$HOME/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
command -v unzip >/dev/null || die "unzip not found"

sha256_of() { sha256sum -- "$1" | cut -d' ' -f1; }
prop() { sed -n "s/^$1=//p" "$2" 2>/dev/null | head -1; }

# Reads META-INF/lib-t3-rust.properties out of an AAR.
aar_meta() { unzip -p "$1" META-INF/lib-t3-rust.properties 2>/dev/null || true; }

check_aar_layout() {
  local listing entry
  listing=$("$JAVA_HOME/bin/jar" --list --file "$1") || die "not a readable AAR: $1"
  for entry in classes.jar AndroidManifest.xml proguard.txt META-INF/lib-t3-rust.properties \
      jni/arm64-v8a/libt3_jni.so jni/armeabi-v7a/libt3_jni.so jni/x86/libt3_jni.so jni/x86_64/libt3_jni.so; do
    grep -qxF "$entry" <<<"$listing" || die "AAR $1 lacks $entry"
  done
}

# Provenance lines shared by the AAR and host properties files.
provenance() { # <version> <source> <tag> <describe> <abi>
  printf 'version=%s\nsource=%s\ntag=%s\ndescribe=%s\nabiVersion=%s\n' "$@"
}

# Installs an AAR under the stable name with its checksum and provenance.
install_aar() { # <aar> <version> <source> <tag> <describe> <abi> <origin>
  mkdir -p "$libs_dir"
  install -m644 "$1" "$aar.tmp"
  mv -f "$aar.tmp" "$aar"
  (cd "$libs_dir" && sha256sum "$(basename "$aar")" > "$(basename "$aar_sum")")
  {
    provenance "$2" "$3" "$4" "$5" "$6"
    printf 'origin=%s\n' "$7"
    printf 'repository=https://github.com/%s\n' "$lib_repo"
    printf 'sha256=%s\n' "$(sha256_of "$aar")"
    printf 'installed=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } > "$aar_props"
  log "Installed $aar (lib-t3-rust $2, $5, $3, ABI $6, from $7)"
}

# True when every <key> <value> pair matches a properties file.
props_match() { # <file> <key> <value> [<key> <value>...]
  local file=$1; shift
  [[ -s $file ]] || return 1
  while (($#)); do [[ $(prop "$1" "$file") == "$2" ]] || return 1; shift 2; done
}

# True when libs/ already holds an intact AAR whose properties match every pair.
aar_current() { # <key> <value> [<key> <value>...]
  [[ -s $aar && -s $aar_sum ]] || return 1
  props_match "$aar_props" "$@" || return 1
  (cd "$libs_dir" && sha256sum --status -c "$(basename "$aar_sum")")
}

lib_dir=""
resolve_lib_dir() {
  local candidate
  for candidate in "${LIB_T3_RUST_DIR:-}" "$HOME/Work/lib-t3-rust" "$repo_root/../lib-t3-rust"; do
    [[ -n $candidate && -f $candidate/Cargo.toml && -x $candidate/tools/build-android ]] || continue
    lib_dir=$(cd -- "$candidate" && pwd)
    return
  done
  die "lib-t3-rust checkout not found; set LIB_T3_RUST_DIR (tried \$HOME/Work/lib-t3-rust and ../lib-t3-rust)"
}

lib_commit=""
lib_version=""
lib_tag=""
lib_describe=""
lib_abi=""
# The idempotency key is the commit plus `git describe --tags --always`, so a tag
# added to an already built commit is still recorded.
inspect_lib() {
  git -C "$lib_dir" rev-parse --git-dir >/dev/null 2>&1 || die "$lib_dir is not a git checkout"
  [[ -z $(git -C "$lib_dir" status --porcelain) ]] \
    || die "$lib_dir has uncommitted changes; package-android only packages a clean, committed checkout"
  lib_commit=$(git -C "$lib_dir" rev-parse HEAD)
  lib_describe=$(git -C "$lib_dir" describe --tags --always)
  lib_tag=$(git -C "$lib_dir" describe --tags --exact-match 2>/dev/null || true)
  lib_version=$(sed -n 's/^version = "\([^"]*\)"/\1/p' "$lib_dir/Cargo.toml" | head -1)
  [[ -n $lib_version ]] || die "cannot read the package version from $lib_dir/Cargo.toml"
  lib_abi=$(abi_of_header < "$lib_dir/include/t3_protocol.h")
  [[ -n $lib_abi ]] || die "cannot read T3_PROTOCOL_ABI_VERSION from $lib_dir/include/t3_protocol.h"
  [[ -z $lib_tag || $lib_tag == "v$lib_version" ]] \
    || log "warning: tag $lib_tag does not match the Cargo version $lib_version"
}

# T3_PROTOCOL_ABI_VERSION from a t3_protocol.h on stdin.
abi_of_header() { sed -n 's/^#define T3_PROTOCOL_ABI_VERSION \([0-9]\{1,\}\).*/\1/p' | head -1; }

# Finds <artifact>-<version>.jar for a Maven coordinate in the Gradle cache.
cached_jar() { # <group> <artifact> <version>
  find "$gradle_cache/$1/$2/$3" -name "$2-$3.jar" -print -quit 2>/dev/null || true
}

# Assembles KOTLIN_STDLIB and KOTLIN_COMPILER_CLASSPATH unless both are supplied.
resolve_kotlin() {
  local kv=${KOTLIN_VERSION:-2.3.21} missing=() entries=() coord group artifact version jar
  if [[ -z ${KOTLIN_STDLIB:-} ]]; then
    KOTLIN_STDLIB=$(cached_jar org.jetbrains.kotlin kotlin-stdlib "$kv")
    [[ -n $KOTLIN_STDLIB ]] || missing+=("org.jetbrains.kotlin:kotlin-stdlib:$kv")
  fi
  if [[ -z ${KOTLIN_COMPILER_CLASSPATH:-} ]]; then
    # kotlin-compiler-embeddable's runtime dependencies, as declared in its POM.
    for coord in \
        "org.jetbrains.kotlin:kotlin-compiler-embeddable:$kv" \
        "org.jetbrains.kotlin:kotlin-stdlib:$kv" \
        "org.jetbrains.kotlin:kotlin-script-runtime:$kv" \
        "org.jetbrains.kotlin:kotlin-daemon-embeddable:$kv" \
        "org.jetbrains.kotlin:kotlin-reflect:${KOTLIN_REFLECT_VERSION:-1.6.10}" \
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${KOTLINX_COROUTINES_VERSION:-1.8.0}" \
        "org.jetbrains:annotations:${JETBRAINS_ANNOTATIONS_VERSION:-13.0}"; do
      IFS=: read -r group artifact version <<<"$coord"
      jar=$(cached_jar "$group" "$artifact" "$version")
      if [[ -n $jar ]]; then entries+=("$jar")
      elif [[ " ${missing[*]} " != *" $coord "* ]]; then missing+=("$coord"); fi
    done
    jar=$(cached_jar org.jetbrains.intellij.deps trove4j 1.0.20200330)
    [[ -z $jar ]] || entries+=("$jar")  # older compilers need it; harmless otherwise
    KOTLIN_COMPILER_CLASSPATH=$(IFS=:; printf '%s' "${entries[*]}")
  fi
  if ((${#missing[@]})); then
    printf 'fetch-or-build-lib: the Kotlin compiler classpath is incomplete; missing from %s:\n' "$gradle_cache" >&2
    printf '  %s\n' "${missing[@]}" >&2
    printf 'Populate the Gradle cache (any Kotlin %s Gradle build does) or set KOTLIN_STDLIB and KOTLIN_COMPILER_CLASSPATH.\n' "$kv" >&2
    exit 1
  fi
  export KOTLIN_STDLIB KOTLIN_COMPILER_CLASSPATH
}

check_build_env() {
  [[ -x $JAVA_HOME/bin/java && -x $JAVA_HOME/bin/jar ]] || die "JAVA_HOME=$JAVA_HOME is not a JDK"
  command -v cargo >/dev/null || die "cargo not found (expected in \$HOME/.cargo/bin)"
  command -v cmake >/dev/null || die "cmake not found"
}

build_aar() {
  inspect_lib
  if ((!force)) && aar_current source "$lib_commit" describe "$lib_describe"; then
    log "libs/ already holds lib-t3-rust $lib_version ($lib_describe, $lib_commit); nothing to do (use --force to rebuild)"
    return
  fi
  local dist_aar="$lib_dir/dist/lib-t3-rust-android-v$lib_version.aar"
  # A packaged AAR already in the lib's dist/ for this exact commit is reused as is.
  if ((!force)) && [[ -s $dist_aar && -s $dist_aar.sha256 ]] \
      && (cd "$lib_dir/dist" && sha256sum --status -c "$(basename "$dist_aar").sha256") \
      && [[ $(aar_meta "$dist_aar" | sed -n 's/^source=//p') == "$lib_commit" ]]; then
    log "Reusing $dist_aar packaged from $lib_commit"
  else
    check_build_env
    : "${ANDROID_JAR:=$ANDROID_HOME/platforms/${ANDROID_PLATFORM:-android-36}/android.jar}"
    [[ -s $ANDROID_JAR ]] || die "ANDROID_JAR not found: $ANDROID_JAR"
    [[ -d $ANDROID_NDK_HOME ]] || die "ANDROID_NDK_HOME not found: $ANDROID_NDK_HOME"
    export ANDROID_JAR
    resolve_kotlin
    log "Building lib-t3-rust $lib_version ($lib_describe, $lib_commit) for four ABIs (CARGO_BUILD_JOBS=$CARGO_BUILD_JOBS)"
    (cd "$lib_dir" && tools/build-android arm64-v8a x86_64 armeabi-v7a x86)
    log "Packaging the AAR"
    (cd "$lib_dir" && tools/package-android)
  fi
  check_aar_layout "$dist_aar"
  [[ $(aar_meta "$dist_aar" | sed -n 's/^source=//p') == "$lib_commit" ]] \
    || die "$dist_aar was not packaged from $lib_commit"
  install_aar "$dist_aar" "$lib_version" "$lib_commit" "${lib_tag:-none}" "$lib_describe" "$lib_abi" "source"
}

download_release() {
  local version=${release_tag#v} asset meta commit abi
  asset="lib-t3-rust-android-v$version.aar"
  if ((!force)) && aar_current origin "release $release_tag"; then
    log "libs/ already holds the $release_tag release AAR; nothing to do (use --force to download again)"
    return
  fi
  [[ -x $JAVA_HOME/bin/jar ]] || die "JAVA_HOME=$JAVA_HOME is not a JDK"
  tmp_dir=$(mktemp -d)
  local tmp=$tmp_dir
  log "Downloading $asset from $lib_repo release $release_tag"
  if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
    gh release download "$release_tag" -R "$lib_repo" -D "$tmp" -p "$asset" -p "$asset.sha256" \
      || die "cannot download $asset and its checksum from $lib_repo release $release_tag"
  else
    local base="https://github.com/$lib_repo/releases/download/$release_tag"
    curl -fsSL --retry 3 -o "$tmp/$asset" "$base/$asset" \
      && curl -fsSL --retry 3 -o "$tmp/$asset.sha256" "$base/$asset.sha256" \
      || die "cannot download $asset and its checksum from $base"
  fi
  [[ -s $tmp/$asset.sha256 ]] || die "release $release_tag has no $asset.sha256"
  (cd "$tmp" && sha256sum -c "$asset.sha256") || die "checksum mismatch for $asset"
  check_aar_layout "$tmp/$asset"
  meta=$(aar_meta "$tmp/$asset")
  [[ $(sed -n 's/^version=//p' <<<"$meta") == "$version" ]] || die "$asset does not declare version $version"
  commit=$(sed -n 's/^source=//p' <<<"$meta")
  # The AAR does not embed its ABI; read it from the header at the same tag.
  abi=$(curl -fsSL --retry 3 "https://raw.githubusercontent.com/$lib_repo/$release_tag/include/t3_protocol.h" \
    2>/dev/null | abi_of_header || true)
  install_aar "$tmp/$asset" "$version" "${commit:-unknown}" "$release_tag" "$release_tag" "${abi:-unknown}" \
    "release $release_tag"
}

# Desktop libt3_jni.so for JVM tests, built the same way as the lib's tools/test-jni.
build_host_jni() {
  [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] \
    || die "the host JNI library is only produced for x86_64 Linux"
  inspect_lib
  local host_props="$host_dir/lib-t3-rust.properties"
  if ((!force)) && [[ -s $host_dir/libt3_jni.so ]] \
      && props_match "$host_props" source "$lib_commit" describe "$lib_describe" \
      && [[ $(prop sha256 "$host_props") == "$(sha256_of "$host_dir/libt3_jni.so")" ]]; then
    log "Host JNI library already built from $lib_describe ($lib_commit); nothing to do"
    return
  fi
  check_build_env
  local output=${CARGO_TARGET_DIR:-target}
  [[ $output == /* ]] || output="$lib_dir/$output"
  log "Building the host JNI library from $lib_describe ($lib_commit)"
  (cd "$lib_dir" && cargo build --release --locked)
  cmake -S "$lib_dir/bindings/android/cpp" -B "$output/jni-host" -DCMAKE_BUILD_TYPE=MinSizeRel \
    -DT3_RUST_ARCHIVE="$(realpath "$output/release/libt3_protocol.a")"
  cmake --build "$output/jni-host" --parallel "$CARGO_BUILD_JOBS"
  [[ -z $(git -C "$lib_dir" status --porcelain) && $(git -C "$lib_dir" rev-parse HEAD) == "$lib_commit" ]] \
    || die "$lib_dir changed during the host build"
  mkdir -p "$host_dir"
  install -m755 "$output/jni-host/libt3_jni.so" "$host_dir/libt3_jni.so"
  {
    provenance "$lib_version" "$lib_commit" "${lib_tag:-none}" "$lib_describe" "$lib_abi"
    printf 'sha256=%s\n' "$(sha256_of "$host_dir/libt3_jni.so")"
  } > "$host_props"
  log "Installed $host_dir/libt3_jni.so (lib-t3-rust $lib_version, $lib_describe, $lib_commit)"
}

# Newest org.json jar from JSON_JAR, the Gradle cache or the local Maven repository.
resolve_json_jar() {
  [[ -n ${JSON_JAR:-} ]] && { [[ -s $JSON_JAR ]] || die "JSON_JAR not found: $JSON_JAR"; export JSON_JAR; return; }
  JSON_JAR=$({ find "$gradle_cache/org.json/json" "$HOME/.m2/repository/org/json/json" \
    -name 'json-[0-9]*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null || true; } \
    | awk -F/ '{print $NF "\t" $0}' | sort -V | tail -1 | cut -f2)
  [[ -n $JSON_JAR ]] || die "no org.json jar in the Gradle or Maven cache; set JSON_JAR (e.g. org.json:json:20250517)"
  export JSON_JAR
}

# The lib's own host fixtures: Java (tools/test-jni) and Kotlin (tools/test-kotlin-jni).
# Both only write under the lib's target/. Runs both and reports each result.
run_lib_fixtures() {
  [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || die "the lib's host fixtures run on x86_64 Linux"
  [[ -n $lib_commit ]] || inspect_lib
  check_build_env
  resolve_kotlin
  resolve_json_jar
  local failed=() tool
  log "Running lib-t3-rust host fixtures at $lib_describe ($lib_commit) with $(basename "$JSON_JAR")"
  for tool in test-jni test-kotlin-jni; do
    [[ -x $lib_dir/tools/$tool ]] || { log "warning: $lib_dir/tools/$tool not found; skipped"; continue; }
    if (cd "$lib_dir" && timeout "${LIB_FIXTURE_TIMEOUT:-900}" "tools/$tool"); then log "tools/$tool passed"
    else failed+=("$tool"); log "tools/$tool FAILED"; fi
  done
  ((${#failed[@]} == 0)) || die "lib fixtures failed: ${failed[*]} (see docs/LIB_REQUESTS.md for known findings)"
}

if ((want_aar)); then
  if [[ -n $release_tag ]]; then download_release; else resolve_lib_dir; build_aar; fi
fi
if [[ $host_jni == yes ]]; then
  [[ -n $lib_dir ]] || resolve_lib_dir
  build_host_jni
fi
if ((lib_fixtures)); then
  [[ -n $lib_dir ]] || resolve_lib_dir
  run_lib_fixtures
fi
