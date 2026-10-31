# P03 integration contract (P02 / P20)

Baseline: Desktop `55c0ce3e`, Android origin/main at branch creation; pinned
lib-t3-rust v0.3.5 source `d4bf6054b4480efdcd8a436cb66cb7f2e23664b1`.

P02 registers `GeneralSettingsRoute` (`general`, title resource, scoped=true).
Render the single public composable `GeneralSettingsScreen(controller, scope,
clientPreferences, modifier, query)`. The contribution is metadata, deliberately
independent of P02's concurrently developed route interface; adapt it there.
No navigation, SettingsScreen, AppGraph, ThreadScreen or Gradle edits are included.

P20 constructs one `GeneralSettingsController(ScopedSettings(connections))` per
page. Implement `SettingsConnections.resolve(environment)` with the current
supervisor's connected transport for that exact environment. Return null when
unavailable; never substitute a default connection. Pass the shell's explicit
scope as `{"targets":[{"environment":"host-id","projectId":null}]}` or project
IDs for selected project/checkouts. All-target scope is an explicit list, not `*`.
Checkout selections map to their actual shell project ID, not a filesystem path.
The native validator rejects mixed environment/project scopes, duplicates and
more than 64 targets. Changing the serialized scope clears stale content/drafts.
The controller's generation prevents old loads/writes from replacing the new page.

`ScopedSettings` is the shared P03 transport for other preference packages:
`read(scope)` / `write(scope,{key,action:update|clear,value})` return the native
`scopedSettings.page` JSON. It keeps native descriptors/catalogs, per-target
errors, savedTargets, mixed/source/inheritance/reset/capability policy. Mutations
are serialized and native-validated against fresh config/settings/auth/project.
No raw settings/config is exposed to the composable. Optional project t3.json
reads are host-side and parsed by scopedSettings.projectFile; unreadable files
are absent, matching Desktop behavior. No project file writes are made.

A dispatched write with failed receipt/read-back is uncertain and not retried.
Refresh performs a read only. Check `errors[].uncertain` and inspect authoritative
values before explicitly resubmitting. Accepted-but-dropped writes are errors,
not saved targets. Multi-target writes are not atomic. Successful destinations
remain listed alongside failures; errors disable editing until refresh.
Cancellation propagates; a cancelled controller disables further writes until
reload. Leaving/re-entering the page reads the host rather than restoring drafts.

## Coverage

All 21 general keys from audit server rows 188–208 are owned by
`GeneralSettingsFields`. This includes guest links, worktree submodules, title
instructions and all three telemetry URLs. Source-control fields in rows 209–221
belong to P05; the shared transport supports them without page-specific policy.
Controls are rendered only from returned descriptors. Model selections and
boolean/select model options use the native catalog; choices are not invented.
Days/text use explicit Save with descriptor limits, instead of committing during
mobile focus/IME transitions. Native patch validation is authoritative.

`GeneralClientPreferences(context)` is a separate Android DataStore. P20 must
consume `settings` for composer followUpBehavior (queue/steer, inverse modifier
behavior remains composer work), timestampFormat (locale/12-hour/24-hour),
planModeEnabled, quickRepliesButton, quickReplyLanguage and `quickReplies.<lang>`.
It does not change existing appearance preferences. Twenty languages retain
Desktop quick reply defaults and trim to 32 nonempty lines of at most 250 chars.
Reset restores the language default. These are device-wide, never project/host
scoped. The screen identifies them as This device. This package exports storage
and controls; effect in existing composer/formatters awaits P20 wiring.

## Unbound operations and explicit limits

The pinned typed API has server.getSettings/updateSettings and all ten
scopedSettings domain functions. It has no Desktop client-settings revision
read/write endpoint: follow-up, timestamps and legacy plan are persisted locally,
not synchronized with Desktop client-settings. No host-sync control is shown.
No bound host update check/install/restart action, telemetry restart/apply action,
or host idle-agent-release/compaction status/action is fabricated. Update policy,
idle compaction preference and telemetry endpoint fields do have bindings and
are included when advertised. Other packages own agent memory/runtime controls.

Unknown future descriptor labels fall back to host text. Known P03 text is in
hand-kept strings_general_settings.xml across all 20 locales, separate from the
pinned generated catalog. No changes to the shared importer or shared strings.
Manual device layout/RTL/IME/accessibility, actual multi-host host writes and
Desktop client synchronization remain unverified. No phone was accessed.
