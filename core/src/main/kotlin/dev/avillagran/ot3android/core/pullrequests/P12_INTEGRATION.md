# P12 PR review/write contracts

Status: partial; SDK v0.3.5, source d4bf6054b4480efdcd8a436cb66cb7f2e23664b1.

## Wiring for P20

Pass `PullRequestViewModel::write` as `PullRequestScreen.onWrite`. The default is null;
no write controls are shown until wired. Keep the navigation-entry ViewModel alive
across recompositions. It retains an unresolved-dispatch flag in SavedStateHandle:
a timeout, cancellation, unexpected receipt or connection replacement prevents another
write in that entry, including after saved-state restoration. Reconcile on the provider;
there is no automatic resend or receipt lookup. A new entry is not proof of reconciliation.
Do not route write retries through refresh. Successful typed null responses refresh both
detail and selected content; changing Checks also reloads authoritative detail.

`PullRequestWrites` builds comment/review/action payloads through `pullRequests.payload`.
The three typed writes return null on acknowledged success. An unexpected response is
unknown, not success. Payload validation occurs before dispatch. No handwritten RPC
payload or HTTP diff URL is used. Host viewerPermissions gate visible controls; the
host must enforce authorization. Merge and close require confirmation; merge methods
come from mergeCapabilities and conflicting/draft/closed PRs cannot merge.

`PullRequestThreadCommands.build(threadId, action, reference)` returns the native command
for link/unlink/watch/unwatch. Dispatch exactly once through the existing command/receipt
controller, retaining commandId. The command helper does not dispatch or invent recovery.
`PullRequestBadgeAction` preserves native current reference and single/stack decision;
P20 must wire stack selection and retain the host/environment, not just repository/number.
These helpers do not create/open worktrees or navigate threads.

## SDK and remaining gaps

- U31 listView/reducer is absent. Existing list intentionally appends pages unchanged;
  overlapping results can repeat. Do not describe this as native pagination merge.
- No authenticated PR diff binding in the AAR/T3Transport. The domain diff recipe is an
  HTTP recipe, not an RPC. File diff/review guide and file navigation remain unavailable.
- Activity only exposes the initial comments/reviewThreads response; no native common
  pagination/merge for review-thread comments. Existing truncation notices remain visible.
- Native comment/submitReview/runAction and threadCommand builders are present. Inline
  review comment editing, reviewer/label management, thread/worktree opening and receipt
  reconciliation UI are not implemented here.
- P20 wiring is needed; navigation, ThreadScreen, AppGraph and Gradle were not changed.
- Unknown-result text is supplied in all 20 locales. Existing read-state strings retain
  their existing English fallback. Imported action labels use the shipped catalog.

## Verification boundaries

Fixture tests cover single dispatch, unknown response, transport failure, typed null success
and native badge reference retention. Existing list scope/cancellation, comments truncation
and checks fixtures run with the app suite. Native JNI tests may skip on this host; this is
not live host write or diff authorization acceptance. No device or phone interaction.
