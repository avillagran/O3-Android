# Decisions

Dated record of build and structure decisions. Architecture itself is in
[ARCHITECTURE.md](ARCHITECTURE.md).

## 2026-10-24: Manual dependency injection, no Hilt

The dependency graph is a plain `AppGraph` class created once in `Ot3Application` and
reached from Compose through the application context. Every dependency is `lazy`.

- The app is small: one activity, a handful of repositories and one transport.
- Compose does not need injected Android components; screens receive state holders and
  lambdas, which is easy to wire by hand and easy to fake in tests.
- No annotation processing or generated components, so builds stay fast and the graph is
  readable in a single file.
- If the graph grows beyond what is comfortable by hand, Koin is the fallback: it is
  runtime-only and keeps the same constructor-based style.

## 2026-10-24: No Hilt

Stated separately because it is the default in most templates. Hilt adds kapt/KSP
processing, a Gradle plugin and `@AndroidEntryPoint` plumbing for a benefit this app does
not need yet (see the entry above). Revisit only together with the Koin question.

## 2026-10-24: Room for structured data, DataStore Preferences for small state

- Room (with KSP, Kotlin code generation, exported schemas under `core/schemas`) stores
  structured, queryable data: environments now; drafts, outbox and caches later.
- DataStore Preferences stores small, non-secret UI state such as the last selected
  environment id.
- Neither of them ever holds a credential.

## 2026-10-24: security-crypto (EncryptedSharedPreferences) as credential store

Tokens are stored through `CredentialStore`, backed by `EncryptedSharedPreferences` from
`androidx.security:security-crypto`. Its master key is generated and kept in the Android
Keystore, so key material never leaves secure hardware where available.

- Chosen over raw Keystore code because it gives authenticated encryption of keys and
  values with very little code, and works from minSdk 24.
- The library is deprecated upstream as of 1.1.0. It still works and is the shortest
  correct path today, so it is used behind `CredentialStore` with the deprecation warnings
  suppressed in that file only. The migration path is a raw Keystore AES-GCM key wrapping
  values stored in DataStore, behind the same class, with a one-time migration of existing
  entries.

## 2026-10-24: compileSdk and targetSdk 36

API 36 is the latest stable platform. API 37 is still treated as preview, so it is not
used. Consequences:

- Android Gradle Plugin 8.13.x (Gradle 8.14), whose highest supported compileSdk is 36.
- The Compose BOM is pinned to the newest release built for compileSdk 36
  (`2026.06.01`, Compose UI 1.11). BOMs from `2026.08.00` (Compose 1.12) require
  compileSdk 37 and AGP 9.1, and will be adopted together with API 37.
- JVM target 17; CI and the development machine build with JDK 21.

## 2026-10-24: Module split: `app` and `core`

- `app`: the single activity, navigation, Compose UI and theme, and the `AppGraph`.
- `core`: an Android library (not pure JVM, because Room, DataStore and the Keystore
  need Android) with `domain` (plain Kotlin models), `data` (Room, DataStore),
  `security` (credentials) and `transport` (the `T3Transport` boundary to lib-t3-rust).
- The lib-t3-rust AAR will be consumed by `core` only, behind `T3Transport`. Protocol
  logic stays in the Rust library.
- More modules (for example per feature) only when build times or ownership ask for it.
