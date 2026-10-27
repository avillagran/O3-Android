# SDK from the public neutral source

Source6b7c04ce2d3d0b9072a6ee97d325adec21c9a3a7 is the initial commit of the
new canonical public OmaBridge repository. The previous repository is a private
read-only archive; this candidate imports no old history. Its source stays clean.

126 native tests across23 suites passed with zero ignored cases using
`cargo test --locked --release --workspace --all-targets -- --test-threads=2`.
Five artifacts were freshly built from that commit. All metadata checksums and
flavors match; each exports only the expected consumer JNI method. All three
Android artifacts have LOAD alignment of at least16KiB. See verification.json.

Regenerate using a clean clone of https://github.com/avillagran/omabridge checked
out at the lock source, set OMABRIDGE_DIR to that clone, then run
`scripts/build-bridge-sdk.sh`. The default clone path is ~/Work/omabridge. The
separate T3 AAR and host JNI remain pinned coherently to lib v0.3.5.

Strict core JVM tests passed (319 passed,5 opt-in skips), including one actual
host-JNI call and one fixed-diagnostic mapping case; core lint passed. Delegated emulator acceptance for
this exact source is still pending. Earlier10/10 delegated acceptance used sourceDD628
and is historical evidence, not substituted for a rerun. Ordinary application
transport streams, productive hosts and physical acceptance remain open.

The strict SDK CI candidate is in ../../../ci/bridge-sdk.yml; it is not installed
as a workflow because the available GitHub credential lacks workflow scope.
