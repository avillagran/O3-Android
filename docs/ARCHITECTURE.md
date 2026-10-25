# ot3-Android architecture

Decided 2026-10-24. The official T3 Code mobile app is Expo/React Native and is
not reused. ot3-Android is native Kotlin on a shared Rust core.

## Layers

1. **Rust core: `t3-protocol` (lib-t3-rust) as an AAR.** HTTP, WebSocket RPC with
   tickets, DPoP, T3 Connect relay, subscriptions with bounded queues, attachments by
   file descriptor, signed assets, and the portable domain modules shared with ot3
   (projection, sidebar, activity, changes, composer, queue, questions, approvals,
   history, models, compaction, documents, plan workflow, checkpoint revert, prompt
   recall, settings contracts, diff review, tool inspector, failure context, quotas).
   The typed API (`api::*`, `call`/`describe` over the JSON C ABI) replaces hand-written
   RPC tags. **Rule: no protocol logic in Kotlin.** If something is missing, add it to
   the lib and consume it; do not re-implement it in Kotlin.
2. **Kotlin domain.** Environment catalog and ordered routes (pairing link, URL + token,
   OmaBridge, Tailscale; T3 Connect later), reconnection supervisor with jittered
   backoff (host-owned per the lib's contract), token choice per scope, persistence
   (Room + DataStore), credentials in Android Keystore / EncryptedSharedPreferences,
   drafts per thread, outbox for non-repeatable mutations with stable command ids.
3. **UI: Jetpack Compose, Material 3, in ot3's visual language** on T3 Mobile's screen
   structure ([UI_SPEC.md](UI_SPEC.md)). Single activity. Home with the threads of every
   environment; thread timeline with streaming; composer with attachments, model picker,
   favorites, mentions and skills; approvals and questions; changes and diff; pull
   requests; preferences. Omarchy themes by default, Material You as an option. Phones and tablets.
4. **Notifications without a relay.** Optional foreground service following active
   threads over WebSocket with the same working / approval / input semantics as ot3's
   bar widget. FCM is out of scope until there is an own relay.

## Phases

| Phase | Deliverable |
|---|---|
| 0 | lib-t3-rust 0.3.x: vendor sync, transport options, portable modules, typed API, Android stream fixes; four-ABI AAR. |
| 1 | MVP: connect by pairing link, URL + token or OmaBridge; live sidebar; read a thread with streaming; send messages and attachments; approve and answer; choose model. |
| 2 | Changes and diff, pull requests, queues, favorites, mentions, skills, archive, search, subscription usage. |
| 3 | T3 Connect (Clerk Android SDK), follow service with notifications, terminal. |

## Toolchain

Development machine: **node** (x86_64, Ubuntu, JDK 21, Android SDK with NDK 27 and
28.2.13676358, Rust Android targets installed). minSdk 24, target the latest stable
SDK, Kotlin 2.x, AGP current stable, Gradle wrapper, Compose BOM. The AAR comes from
the lib-t3-rust GitHub release pinned in `libs/lib-t3-rust.lock` (v0.3.3), or is built
with `tools/build-android` and `tools/package-android` for unreleased lib work; see
[LIB_BUILD.md](LIB_BUILD.md). Device QA on a physical Android phone and an
emulator.

## Reference material

- ot3 is the behavioural spec: `docs/SCREEN_PARITY_AUDIT.md`, `docs/FEATURE_PARITY.md`,
  the QML in `ui/qml` and the JS in `ui/js` show every screen and its contract with the
  Rust side. Port behaviour, not code.
- `docs/audit/T3_TOUCHPOINTS.md` and `docs/audit/T3_TYPED_API.md` in ot3 describe what
  the lib exposes and why.
- The official Expo app under `apps/mobile` of the T3 Code repository is useful only
  for UX reference and for its small Kotlin modules (notification presentation, review
  diff drawing, terminal canvas), which may be adapted within their licence.

## Conventions

- Commits by the repository owner only; no AI attribution anywhere.
- Tests: JVM unit tests for the Kotlin domain against a fake T3 (same fixtures idea as
  the lib), Compose UI tests for the main screens, instrumented smoke on an emulator.
- Never store a credential in logs, preferences or crash reports.
