# P05 integration contribution

Baseline: Desktop `feat/o3-brand` 55c0ce3; Android starts at origin/main;
lib-t3-rust v0.3.5, source d4bf6054b4480efdcd8a436cb66cb7f2e23664b1.

P20 must register `ProjectSettingsRoute(environmentId, projectId)`, construct
`ProjectConfiguration` using exactly that host's transport, collect `state`, call
`refresh` on entry, and connect `ProjectSettingsScreen` callbacks. Refresh reads
host state; updates use native scopedSettings.writePatch + server.updateSettings.
Only settings:write permits edits; native scopeWritable additionally gates each
field. Unknown writes block editing until explicit refresh; no automatic replay.
The selected project's t3.json is not fetched yet, so file provenance is unverified.
Model choices come from targetView.catalog. Structured descriptors remain read-only.
Project rename/remove/autoPull are not substituted with scoped settings patches.

Keep one `ThreadMenuOperations` per thread across sheet dismissal. Feed current
shell titleRegeneration changes into observeTitleRegeneration, combine its busy
and titleRegenerating into ActionContext/ThreadMenuTarget, and render
ThreadMenuRecoveryContent for unknown receipts. Retry uses MessageSender.retry
and its original durable command id; never call execute again for that receipt.
Existing HomeViewModel/AppScreens do not yet consume this contribution.

ProjectPresentation takes a dedicated SharedPreferences file. Thread keys and
project keys must be environment-qualified. It stores Desktop-compatible
threadGroups/groupProjects/sidebarProjectGroupingOverrides; thread membership
wins over project membership. Clearing a thread override restores inheritance.
P04/P20 consume snapshot and group results; invoke the local controls in
ProjectMenuContribution. Supply the physical project identity from the native
project projection when changing grouping. Do not infer it from display text.
Group names are bounded to 48 code points, trimmed and reject control characters.
This is local presentation state, like Desktop; it does not mutate host threads.

## Pinned AAR limits / remaining acceptance

* sidebar.threadAction accepts pin/unpin but no orderKey or reorder plan. Move
  up/down stay visibly disabled; do not port fractional ordering into Kotlin.
* There is no projects.command domain binding for project rename/settings/remove.
  Scoped settings defaults are available; direct project metadata mutations need
  a released native builder, including removal confirmation/force semantics.
* Named groups are local Desktop view preferences, not native mutations.
* Native menu eligibility/preset projection is absent. Existing catalog capability
  and metadata.canSnooze gates remain; snooze presets retain existing policy.
* Same-branch creation needs P06/P20 recipe integration and remains gated off.
* Project settings route, local group controls, titleRegeneration metadata and
  unknown-receipt recovery require P20 wiring; current navigation is untouched.
* Copy path/branch/thread ID remain on the existing callback. An environment-aware
  copy-reference serializer is not exposed in the pinned domain; not fabricated.
* No phone/device/JNI runtime or accessibility acceptance is claimed.

This package is partial parity until these bindings and integration land.
