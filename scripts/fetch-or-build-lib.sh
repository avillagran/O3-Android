#!/usr/bin/env bash
# Build (or download) the lib-t3-rust Android AAR into libs/ and, on x86_64 Linux,
# a host JNI library for JVM unit tests. Never installs SDKs, targets or toolchains.
# See docs/LIB_BUILD.md.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/fetch-or-build-lib.sh [options]

Default: build the AAR from the current HEAD of the lib-t3-rust checkout (normally
a release tag such as v0.3.1), then build the host JNI library when the host is
x86_64 Linux.

  --release [<tag>] Download the AAR from a GitHub release (e.g. v0.2.0) instead
                    of building it; the release checksum is verified. Without a
                    tag, the tag pinned in libs/lib-t3-rust.lock is used.
  --min-abi <n>     With --release, refuse an AAR whose C ABI is below <n> or
                    unknown (default: T3_MIN_ABI, else 3).
  --sha256 <hex>    With --release, also require this SHA-256 for the AAR.
  --host-jni        Build the host JNI library even when not built by default
                    (with --release, the lib checkout must be at the AAR's commit).
  --host-jni-only   Build only the host JNI library; leave the AAR untouched.
  --no-host-jni     Skip the host JNI library.
  --lib-fixtures    Also run the lib's own host fixtures (tools/test-jni and
                    tools/test-kotlin-jni) against its checkout; needs an org.json
                    jar (JSON_JAR or the Gradle/Maven cache). Exits non-zero if
                    either fixture fails.
  --force           Rebuild/download even if libs/ already matches the source.
  -h, --help        Show this help.

Exit status: 0 on success, 1 on any failure (including a failed fixture), 2 on a
usage error.

Environment overrides: LIB_T3_RUST_DIR, LIB_T3_RUST_REPO, T3_MIN_ABI, JAVA_HOME,
ANDROID_HOME, ANDROID_NDK_HOME, ANDROID_JAR, ANDROID_PLATFORM, KOTLIN_VERSION,
KOTLIN_STDLIB, KOTLIN_COMPILER_CLASSPATH, JSON_JAR, GRADLE_USER_HOME,
CARGO_BUILD_JOBS, CARGO_TARGET_DIR.
EOF
}

die() { printf 'fetch-or-build-lib: %s\n' "$*" >&2; exit 1; }
usage_error() {
  printf 'fetch-or-build-lib: %s\n' "$*" >&2
  printf 'Run %s --help for the options.\n' "$0" >&2
  exit 2
}
tmp_dir=""
trap '[[ -z $tmp_dir ]] || rm -rf -- "$tmp_dir"' EXIT
log() { printf '==> %s\n' "$*"; }

repo_root=$(cd -- "$(dirname -- "$0")/.." && pwd)
libs_dir="$repo_root/libs"
aar="$libs_dir/lib-t3-rust-android.aar"
aar_sum="$aar.sha256"
aar_props="$libs_dir/lib-t3-rust-android.properties"
host_dir="$libs_dir/host/linux-x86_64"
release_lock="$libs_dir/lib-t3-rust.lock"
lib_repo=${LIB_T3_RUST_REPO:-avillagran/lib-t3-rust}

release=0
release_tag=""
expected_sha=""
sha_origin=""
min_abi=${T3_MIN_ABI:-3}
force=0
want_aar=1
host_jni=auto
lib_fixtures=0
opt_host_jni=0
opt_host_jni_only=0
opt_no_host_jni=0
while (($#)); do
  case "$1" in
    --release)
      release=1
      # The tag is optional: without one, libs/lib-t3-rust.lock provides it.
      if (($# >= 2)) && [[ $2 != -* ]]; then release_tag=$2; shift; fi;;
    --release=*) release=1; release_tag=${1#--release=}; [[ -n $release_tag ]] || usage_error "--release= needs a tag";;
    --min-abi) (($# >= 2)) || usage_error "--min-abi needs a number"; min_abi=$2; shift;;
    --min-abi=*) min_abi=${1#--min-abi=};;
    --sha256) (($# >= 2)) || usage_error "--sha256 needs a hex digest"; expected_sha=$2; shift;;
    --sha256=*) expected_sha=${1#--sha256=};;
    --host-jni) opt_host_jni=1;;
    --host-jni-only) opt_host_jni_only=1;;
    --no-host-jni) opt_no_host_jni=1;;
    --lib-fixtures) lib_fixtures=1;;
    --force) force=1;;
    -h|--help) usage; exit 0;;
    *) usage_error "unknown option: $1";;
  esac
  shift
done

((!(opt_host_jni_only && release))) \
  || usage_error "--host-jni-only builds no AAR, so it cannot be combined with --release"
((!((opt_host_jni || opt_host_jni_only) && opt_no_host_jni))) \
  || usage_error "--no-host-jni contradicts --host-jni/--host-jni-only"
[[ $min_abi =~ ^[0-9]+$ ]] || usage_error "--min-abi/T3_MIN_ABI must be a non-negative integer, got: $min_abi"
if [[ -n $expected_sha ]]; then
  ((release)) || usage_error "--sha256 only applies to --release"
  expected_sha=$(tr 'A-F' 'a-f' <<<"$expected_sha")
  [[ $expected_sha =~ ^[0-9a-f]{64}$ ]] || usage_error "--sha256 must be 64 hex digits"
  sha_origin="--sha256"
fi

sha256_of() { sha256sum -- "$1" | cut -d' ' -f1; }
prop() { sed -n "s/^$1=//p" "$2" 2>/dev/null | head -1; }

# A committed libs/lib-t3-rust.lock (tag=..., sha256=...) pins the release path.
if ((release)) && [[ -f $release_lock ]]; then
  lock_tag=$(prop tag "$release_lock")
  lock_sha=$(prop sha256 "$release_lock")
  [[ -n $lock_tag && $lock_sha =~ ^[0-9a-f]{64}$ ]] \
    || die "malformed $release_lock: expected the lines tag=<vX.Y.Z> and sha256=<64 hex digits>"
  if [[ -z $release_tag ]]; then
    release_tag=$lock_tag
  elif [[ $release_tag != "$lock_tag" ]]; then
    die "libs/lib-t3-rust.lock pins $lock_tag but --release asked for $release_tag; update the lock or omit the tag"
  fi
  [[ -z $expected_sha || $expected_sha == "$lock_sha" ]] \
    || die "--sha256 $expected_sha differs from the $lock_sha pinned in libs/lib-t3-rust.lock"
  expected_sha=$lock_sha
  sha_origin="libs/lib-t3-rust.lock"
fi
((!release)) || [[ -n $release_tag ]] \
  || usage_error "--release needs a tag (e.g. v0.2.0) or a libs/lib-t3-rust.lock"
[[ -z $release_tag || $release_tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]] \
  || usage_error "release tag must look like v0.2.0, got: $release_tag"

host_linux_x86_64() { [[ $(uname -s) == Linux && $(uname -m) == x86_64 ]]; }
if ((opt_host_jni_only)); then host_jni=yes; want_aar=0
elif ((opt_host_jni)); then host_jni=yes
elif ((opt_no_host_jni)); then host_jni=no
# The host library is built from source; with --release it is opt-in.
elif host_linux_x86_64 && ((!release)); then host_jni=yes
else host_jni=no
fi
if [[ $host_jni == yes ]] && ! host_linux_x86_64; then die "the host JNI library is only produced for x86_64 Linux"; fi
if ((lib_fixtures)) && ! host_linux_x86_64; then die "the lib's host fixtures run on x86_64 Linux"; fi

# Toolchain defaults for the development machine; any of them can be overridden.
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/jdk}
export ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android-sdk}}
export ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-$ANDROID_HOME}
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}
if [[ -z ${CARGO_BUILD_JOBS:-} ]]; then
  CARGO_BUILD_JOBS=$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
  ((CARGO_BUILD_JOBS <= 8)) || CARGO_BUILD_JOBS=8
fi
export CARGO_BUILD_JOBS
export PATH="$JAVA_HOME/bin:$HOME/.cargo/bin:$ANDROID_HOME/platform-tools:$PATH"
gradle_cache="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

need_unzip() { command -v unzip >/dev/null || die "unzip not found (needed to check an AAR)"; }

# Reads META-INF/lib-t3-rust.properties out of an AAR; call need_unzip first.
aar_meta() { unzip -p "$1" META-INF/lib-t3-rust.properties 2>/dev/null || true; }

check_aar_layout() {
  local listing entry
  need_unzip
  listing=$(unzip -Z1 "$1" 2>/dev/null) || die "not a readable AAR: $1"
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

is_lib_checkout() { [[ -f $1/Cargo.toml && -x $1/tools/build-android ]]; }

lib_dir=""
# An explicit LIB_T3_RUST_DIR must be valid; the default locations are only tried when it is unset.
resolve_lib_dir() {
  local candidate
  if [[ -n ${LIB_T3_RUST_DIR+set} ]]; then
    [[ -n $LIB_T3_RUST_DIR ]] || die "LIB_T3_RUST_DIR is set but empty; unset it to use the default locations"
    [[ -d $LIB_T3_RUST_DIR ]] || die "LIB_T3_RUST_DIR=$LIB_T3_RUST_DIR is not a directory"
    is_lib_checkout "$LIB_T3_RUST_DIR" \
      || die "LIB_T3_RUST_DIR=$LIB_T3_RUST_DIR is not a lib-t3-rust checkout (no Cargo.toml or tools/build-android)"
    lib_dir=$(cd -- "$LIB_T3_RUST_DIR" && pwd)
    return
  fi
  for candidate in "$HOME/Work/lib-t3-rust" "$repo_root/../lib-t3-rust"; do
    is_lib_checkout "$candidate" || continue
    lib_dir=$(cd -- "$candidate" && pwd)
    return
  done
  die "lib-t3-rust checkout not found; set LIB_T3_RUST_DIR (tried \$HOME/Work/lib-t3-rust and ../lib-t3-rust)"
}

# Read-only status: GIT_OPTIONAL_LOCKS=0 keeps git from refreshing the lib's index.
lib_clean() { [[ -z $(GIT_OPTIONAL_LOCKS=0 git -C "$lib_dir" status --porcelain) ]]; }

lib_commit=""
lib_version=""
lib_tag=""
lib_describe=""
lib_abi=""
# The idempotency key is the commit plus `git describe --tags --always`, so a tag
# added to an already built commit is still recorded.
inspect_lib() {
  [[ -z $lib_commit ]] || return 0
  git -C "$lib_dir" rev-parse --git-dir >/dev/null 2>&1 || die "$lib_dir is not a git checkout"
  lib_clean || die "$lib_dir has uncommitted changes; package-android only packages a clean, committed checkout"
  [[ -f $lib_dir/Cargo.toml ]] || die "$lib_dir/Cargo.toml not found"
  [[ -f $lib_dir/include/t3_protocol.h ]] || die "$lib_dir/include/t3_protocol.h not found"
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

# Sets $abs to the colon-separated list of files in <paths>, each made absolute (the
# lib's tools run from its checkout, so relative paths would break there).
abs=""
absolutize() { # <name> <path[:path...]>
  local entry entries=() out=()
  IFS=: read -ra entries <<<"$2"
  for entry in "${entries[@]}"; do
    [[ -n $entry ]] || continue
    [[ -s $entry ]] || die "$1 entry not found: $entry"
    out+=("$(realpath -- "$entry")")
  done
  ((${#out[@]})) || die "$1 is empty"
  abs=$(IFS=:; printf '%s' "${out[*]}")
}

# Finds <artifact>-<version>.jar for a Maven coordinate in the Gradle cache; the
# first path in sort order when several hash directories hold one.
cached_jar() { # <group> <artifact> <version>
  { find "$gradle_cache/$1/$2/$3" -name "$2-$3.jar" 2>/dev/null || true; } | sort | head -1
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
  absolutize KOTLIN_STDLIB "$KOTLIN_STDLIB"; KOTLIN_STDLIB=$abs
  absolutize KOTLIN_COMPILER_CLASSPATH "$KOTLIN_COMPILER_CLASSPATH"; KOTLIN_COMPILER_CLASSPATH=$abs
  export KOTLIN_STDLIB KOTLIN_COMPILER_CLASSPATH
}

# Only building needs a JDK; downloading a release does not.
check_build_env() {
  [[ -x $JAVA_HOME/bin/java && -x $JAVA_HOME/bin/jar ]] || die "JAVA_HOME=$JAVA_HOME is not a JDK"
  command -v cargo >/dev/null || die "cargo not found (expected in \$HOME/.cargo/bin)"
  command -v cmake >/dev/null || die "cmake not found"
}

build_aar() {
  inspect_lib
  if [[ -f $release_lock ]] && [[ $(prop tag "$release_lock") != "${lib_tag:-none}" ]]; then
    log "warning: libs/lib-t3-rust.lock pins $(prop tag "$release_lock") (release path only); building $lib_describe from source"
  fi
  if ((!force)) && aar_current source "$lib_commit" describe "$lib_describe"; then
    log "libs/ already holds lib-t3-rust $lib_version ($lib_describe, $lib_commit); nothing to do (use --force to rebuild)"
    return
  fi
  need_unzip
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
    ANDROID_JAR=$(realpath -- "$ANDROID_JAR")
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

have_gh() { command -v gh >/dev/null && gh auth status >/dev/null 2>&1; }

# Downloads one release asset over HTTPS only; a 404 means the release (or the asset) is not published.
https_get() { # <url> <output>
  local code
  code=$(curl -sSL --proto '=https' --proto-redir '=https' --retry 3 -o "$2" -w '%{http_code}' "$1") \
    || die "cannot download $1"
  [[ $code != 404 ]] || die "release $release_tag is not published on $lib_repo (or lacks $(basename "$2")): $1 returned 404"
  [[ $code == 200 ]] || die "cannot download $1 (HTTP $code)"
}

# T3_PROTOCOL_ABI_VERSION of the release, from include/t3_protocol.h at its tag; empty if unknown.
release_abi() {
  local header
  if have_gh; then
    header=$(gh api -H 'Accept: application/vnd.github.raw' \
      "repos/$lib_repo/contents/include/t3_protocol.h?ref=$release_tag" 2>/dev/null || true)
  else
    header=$(curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 \
      "https://raw.githubusercontent.com/$lib_repo/$release_tag/include/t3_protocol.h" 2>/dev/null || true)
  fi
  abi_of_header <<<"$header"
}

check_abi_floor() { # <abi> <what>
  [[ $1 =~ ^[0-9]+$ ]] \
    || die "$2 has an unknown C ABI version; refusing it (minimum ABI $min_abi, set with --min-abi or T3_MIN_ABI)"
  (($1 >= min_abi)) \
    || die "$2 has C ABI $1, below the minimum ABI $min_abi; use a newer release or pass --min-abi $1 deliberately"
}

# With --release, a host library is only built from the commit the AAR was packaged from.
require_lib_at() { # <aar source commit>
  [[ $host_jni == yes ]] || return 0
  inspect_lib
  [[ $1 == "$lib_commit" ]] \
    || die "the $release_tag AAR was packaged from ${1:-an unknown commit} but $lib_dir is at $lib_commit ($lib_describe); check out the AAR's commit there, or pass --no-host-jni"
}

download_release() {
  local version=${release_tag#v} asset meta commit abi actual
  asset="lib-t3-rust-android-v$version.aar"
  if ((!force)) && aar_current origin "release $release_tag" \
      && [[ -z $expected_sha || $(prop sha256 "$aar_props") == "$expected_sha" ]]; then
    check_abi_floor "$(prop abiVersion "$aar_props")" "the installed $release_tag AAR"
    require_lib_at "$(prop source "$aar_props")"
    log "libs/ already holds the $release_tag release AAR; nothing to do (use --force to download again)"
    return
  fi
  need_unzip
  tmp_dir=$(mktemp -d)
  log "Downloading $asset from $lib_repo release $release_tag"
  if have_gh; then
    if ! gh release download "$release_tag" -R "$lib_repo" -D "$tmp_dir" -p "$asset" -p "$asset.sha256"; then
      gh release view "$release_tag" -R "$lib_repo" >/dev/null 2>&1 \
        || die "release $release_tag is not published on $lib_repo"
      die "cannot download $asset and its checksum from $lib_repo release $release_tag"
    fi
  else
    local base="https://github.com/$lib_repo/releases/download/$release_tag"
    https_get "$base/$asset" "$tmp_dir/$asset"
    https_get "$base/$asset.sha256" "$tmp_dir/$asset.sha256"
  fi
  [[ -s $tmp_dir/$asset && -s $tmp_dir/$asset.sha256 ]] || die "release $release_tag has no $asset and $asset.sha256"
  (cd "$tmp_dir" && sha256sum -c "$asset.sha256") || die "checksum mismatch for $asset"
  actual=$(sha256_of "$tmp_dir/$asset")
  [[ -z $expected_sha || $actual == "$expected_sha" ]] \
    || die "$asset has SHA-256 $actual, not the $expected_sha required by $sha_origin"
  check_aar_layout "$tmp_dir/$asset"
  meta=$(aar_meta "$tmp_dir/$asset")
  [[ $(sed -n 's/^version=//p' <<<"$meta") == "$version" ]] || die "$asset does not declare version $version"
  commit=$(sed -n 's/^source=//p' <<<"$meta")
  # The AAR does not embed its ABI; read it from the header at the same tag.
  abi=$(release_abi)
  check_abi_floor "$abi" "$asset"
  require_lib_at "$commit"
  install_aar "$tmp_dir/$asset" "$version" "${commit:-unknown}" "$release_tag" "$release_tag" "$abi" \
    "release $release_tag"
}

# Desktop libt3_jni.so for JVM tests, built the same way as the lib's tools/test-jni.
build_host_jni() {
  host_linux_x86_64 || die "the host JNI library is only produced for x86_64 Linux"
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
  lib_clean && [[ $(git -C "$lib_dir" rev-parse HEAD) == "$lib_commit" ]] \
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
  if [[ -z ${JSON_JAR:-} ]]; then
    JSON_JAR=$({ find "$gradle_cache/org.json/json" "$HOME/.m2/repository/org/json/json" \
      -name 'json-[0-9]*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null || true; } \
      | awk -F/ '{print $NF "\t" $0}' | sort -V | tail -1 | cut -f2)
    [[ -n $JSON_JAR ]] || die "no org.json jar in the Gradle or Maven cache; set JSON_JAR (e.g. org.json:json:20250517)"
  fi
  [[ -s $JSON_JAR ]] || die "JSON_JAR not found: $JSON_JAR"
  JSON_JAR=$(realpath -- "$JSON_JAR")
  export JSON_JAR
}

# The lib's own host fixtures: Java (tools/test-jni) and Kotlin (tools/test-kotlin-jni).
# Both only write under the lib's target/. Runs both, reports each result and exits
# non-zero if either fails.
run_lib_fixtures() {
  host_linux_x86_64 || die "the lib's host fixtures run on x86_64 Linux"
  inspect_lib
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

# Locate (and validate) the lib checkout before anything is written.
if { ((want_aar)) && ((!release)); } || [[ $host_jni == yes ]] || ((lib_fixtures)); then
  resolve_lib_dir
fi

# One run at a time per repository: concurrent runs would race on libs/.
mkdir -p "$libs_dir"
if command -v flock >/dev/null; then
  exec 9>"$libs_dir/.fetch-or-build.lock"
  flock -n 9 || die "another fetch-or-build-lib run holds $libs_dir/.fetch-or-build.lock"
else
  log "warning: flock not found; concurrent runs are not prevented"
fi

if ((want_aar)); then
  if ((release)); then download_release; else build_aar; fi
fi
if [[ $host_jni == yes ]]; then build_host_jni; fi
if ((lib_fixtures)); then run_lib_fixtures; fi
