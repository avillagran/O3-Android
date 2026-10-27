# UI-3 — composer progress

## Queue increment (2026-10-26)

The real thread screen now renders `timeline.view.queue` above its composer. Its order,
held state, steering and reordering capabilities come directly from Rust. The strip
opens a compact queue sheet; Resume, Remove, drag/step reorder, Steer, and Edit in composer call
`queue.command`, then the persisted dispatch path. A failed/lost response uses the
existing error/outbox UI, with byte-identical retries instead of a fresh mutation.
Queue edits retain the unsent Room draft separately; Cancel restores that draft.
Editing retains the queue row's attachments and context. No optimistic removal or
reordering is applied: the thread stream remains authoritative.

References: ot3 `7b5b1de`, `ui/qml/T3Queue.qml:9-34` (strip), `:37-80`
(row controls); lib v0.3.4 `queue::command` / `queue::derive`. Android uses a sheet on
phones, as specified in UI_SPEC 4.4.6. A 48 dp grip supports touch drag and an Up/Down step menu plus TalkBack actions;
see the touch-reorder follow-up below.

## Evidence scope

- JVM integration tests exercise the actual v0.3.4 Rust queue builder and fake transport:
  validation before persistence, attachment/context retention and unknown-result retry.
- Compose/Robolectric checks visible pause/resume, correct target run, editing and
  disabled offline/unsupported actions. These are not emulator or live-server evidence.
- Initial queue delivery had JVM-only evidence; touch-reorder emulator evidence is
  recorded in the follow-up below. No Xiaomi used.

## Remaining composer differences

- Model picker now reads models.catalog, renders provider/favorites sections and server-owned favorites, and sends the selected instance/model/options with the next message. Boolean/select descriptors render directly. Per-thread model/option drafts persist in DataStore. Source provider icons, rail sizing, fuzzy search ordering and legacy badges still need visual completion; search currently preserves native catalog order with a text filter. Permission picker remains open.
- Plan / Build and slash/completion workflows.
- New-message queue/steer delivery and attachment sends require the existing common
  `composer.message` builder request (LIB_REQUESTS §2); no Kotlin replacement is added.
- Upload/picker, expanded editor, source toolbar, draft saving feedback and hardware keys.
- Desktop inline T3Reveal is adapted to the existing phone Ot3Sheet. Touch edge
  autoscroll and direct desktop Up/Down grip key bindings remain absent; long queues
  remain operable with ordinary list scrolling and the accessible step menu.
- Queue-edit selections remain in the screen ViewModel through rotation; process death
  returns to the original saved draft and authoritative server queue rather than saving
  an unfinished queue edit as a new message.

This increment does not close UI-3 or claim parity with the desktop composer.

## Model/options increment (2026-10-26)

- Source: ot3 T3ModelPicker.qml and T3ModelOptions.qml; model selection shape follows
  T3Backend.qml:739-755. Native catalog controls enabled/hidden/order/availability.
- Server calls: server.getConfig -> models.catalog; favorite mutations use
  models.favoritePayload -> server.updateSettings -> models.preferences. No local copy
  of server favorites. An uninitialized favorites list stays read-only.
- Model and descriptor options persist per environment/thread in a separate non-secret
  DataStore. DataStore recreation and separator-containing identities are tested.
- Compose tests cover duplicate model slugs on distinct provider instances, advertised
  option ids/values and unavailable favorite preferences.
- Complete app/core JVM and lint passed after this change. No model-picker device
  evidence yet; emulator remains reserved for UI-1 integration.

## Touch reorder and queue-edit contract follow-up (2026-10-26)

Source: ot3 `7b5b1de`, `T3Queue.qml:13-17,37-80`; UI_SPEC §4.4.6. The grip uses
the exact inline `IconAssets.js` SVG and the shared 48 dp Ot3IconButton. Rows now keep
grip/text/attachment marker and icon actions on one compact line (up to two text
lines), with the source edit highlight (accent@0.13). This is a deliberate phone
adaptation of the desktop 42 px row, preserving full touch targets.

- Drag starts after Android touch slop on the grip. The source stays in server order
  at opacity0.5; a 2 dp accent line marks the selected insertion gap. No floating
  ghost, scale, spring, local reorder, persisted reorder or added animation. Feedback
  is immediate with motion Off as well; kit press colors/sheet retain shared policy.
- Pointer midpoints of visible rows select `beforeRunId`, or explicit null at the
  visible end of the whole queue. Same/adjacent gaps, taps, pointer cancel and stale
  drags dispatch nothing. A valid drop emits one existing queue intent. There is no
  touch edge autoscroll: scroll normally over text, then drag among visible rows or
  use the grip's Up/Down menu. Step Down can reach null/end and TalkBack custom
  actions expose both steps. A tap only opens that menu.
- Cancellation keys use ordered run IDs/pending flags, native canReorder, busy/live
  eligibility, queue-edit identity, thread/Connection transport identity+epoch and
  orientation. Harmless recreation of equivalent JSON or text refresh does not
  cancel. Modal dismissal disposes the gesture. Pending/edit/remove gates remain
  native-field driven; composer busy, unconfirmed and stale state also disable it.
- `queue.derive` owns capability presentation; `queue.command` validates documented
  IDs and payload shape and constructs the command. It does NOT recheck current
  membership or provider permissions. UI cancellation protects stale gestures; the
  eventual server is authoritative. No Kotlin capability/domain reorder policy.
- `MessageSender.queue` persists one command ID before dispatch. The stream alone
  changes visible order. Pending response leaves rows in place. Unknown outcomes
  retain the same outbox bytes/ID; the sheet reuses existing notice/unconfirmed retry
  UI so Resend is reachable there. A definite rejection clears outbox and shows the
  existing error inside the sheet; another explicit gesture is a fresh command.

A queue-edit bug predated this drag work: `send()` passed an entire projected row
(including available/messageId) into strict `queue.command`. The rotation/attachment
device test exposed it. Before and after Send, diagnostics showed stable portrait
1080×2400 window/root, valid Send bounds (911,2058)-(1037,2184), IME=false, connected,
not stale, no sending/pending; the tap reached the VM and native validation failed.
An offline JNI reproduction returned `invalid_input` for that full row, and succeeded
with only documented inputs. `queueEditArguments` now maps action/runId/text/original
attachments/context object, without copying row metadata or constructing a protocol
command. Real queue.derive→input mapping→queue.command regression covers metadata,
attachments and object/null context. No rotation sleeps/timeouts/retries were added
to hide this failure.

Validation (2026-10-26):

- Full app JVM: 253/253 passed. Queue UI: 8 tests cover valid/end drop without local
  reorder, same/adjacent/tap/cancel, live/busy/capability/pending/owner/order changes,
  harmless JSON recreation, step Down to null/end and sheet dismiss. Native edit
  mapping regression uses actual queue.derive rows. Core MessageSender targeted suite:
  17/17 passed, including actual JNI end-target builder and byte-identical retry.
- Debug app/test APK and lintDebug passed. Six Tokyo Night/Latte component goldens
  (strip, sheet, active drag) recorded and verified; motion Off uses the same immediate
  source opacity/marker. Importer check: 210 labels ×20 locales from pinned 0.3.5 AAR.
- API36.1 emulator-5580 software renderer: ordinary suite4/4 passed in15.048s; opt-in
  evidence4/4 passed in20.255s. Tests separate pending dispatch from explicit synthetic
  run.updated stream publication through the real projection reducer, and prove no
  local reorder. Cover no-op/cancel, authoritative update during drag, actual offline,
  rejection feedback/fresh IDs, unknown retry with exact original ID/bytes, and queue
  edit retaining attachments/context plus the original draft through keyboard/rotation.
- `queue-drag/` has six inspected full-resolution PNGs: Tokyo/Latte same-content queue
  and drag, Tokyo authoritative stream order and Latte unknown/retry. `queue.mp4` is a
  23s 720px export. The 600ms pre-capture pause is opt-in only, never normal CI. Native
  capability projection may omit Steer in that fixture; component goldens also show
  the native canSteer=true presentation. No production provider dispatch, live-provider
  acceptance or Xiaomi action; emulator stopped after evidence.

Limits remain explicit: phone sheet/48dp compact rows, no touch edge autoscroll or
source direct arrow-key grip binding, no productive-provider/physical acceptance.
UI-3 is not closed. New-message common builder, attachment upload/picker, broader
composer toolbar/editor/completions and other listed gaps remain outside this change.

## Expanded editor increment (2026-10-26)

The real ThreadContent square-pen opens the same composer in the thread surface.
Reference: ot3 `7b5b1de` T3Composer.qml:218–239,263–271,385–429,701–709 and
T3Panel.qml:225–236,2021–2031. The source toggle is always available; it does not
require three lines. Android adapts desktop margins to a 48 dp Back/Send header,
bounded scrollable notices and a model/options toolbar above IME. No modal hides
send errors or byte-identical retry. Back/Esc returns to the preserved timeline and
closes IME; this is a deliberate phone adaptation. Reduced-motion tokens govern
the existing focus-border transition; no new expansion animation is introduced.

Selection/focus are UI state scoped by canonical environment/thread identity;
draft text remains in the existing VM/Room owner. Expansion, queue edit/cancel and
rotation retain selection. Retired rendering/paused-Activity blur callbacks cannot
collapse the saved range. Transient UI echo tokens prevent delayed combined-flow
emissions overwriting typing, without adding text to the saved-instance bundle or
RPC. External text changes clamp selection. Explicit edits to empty before initial
Room load win and persist; accepted/unknown sends clear normally through the
existing state machine. Queue edits retain original draft, attachments and context.

Soft/virtual Enter is newline. For nonvirtual hardware events, compact Enter sends,
Shift+Enter and expanded Enter insert newline; expanded Ctrl+Enter sends. Repeats,
composition and unsupported alternate-delivery/new-thread shortcuts never send.
Instrumented keyboard coverage distinguishes soft events injected through
UiAutomation from nonvirtual-device events dispatched to Activity. This exercises
the Android/Compose native event route, not an OEM IME or a physical Bluetooth
keyboard. JVM policy tests cover composing/repeat/virtual-device guards.

UI-3 remains open: project draft browsing, prompt recall/list continuation,
completion chips, attachment upload/new-message builder and alternate-delivery
shortcuts are outside this increment and await their common domain contracts.
The full desktop composer/attachment layout is not claimed complete.

Validation: full app JVM267/267 and ThreadComposer8/8 passed before the final
selection-only lifecycle guard; final focused editor JVM10/10, lintDebug, app/test
APK builds and eight affected Tokyo/Latte golden captures passed. API36.1 software
emulator5580 passed the four real-graph/Rust/synthetic-transport cases (19.997s):
mode/selection/rotation/IME, exact keyboard paths, queue-edit attachment/context
retention, and unknown response with byte-identical retry. The final lifecycle guard
also passed the rotation/IME case alone (5.355s), and the final rapid-edit/expand/Back
case passed (7.024s) with recording. No production or phone dispatches.

`expanded-editor/editor-{tokyo,latte}-expanded.png` show the same draft and IME,
with controls above the keyboard; these are synthetic emulator screenshots.
The short `expanded-editor/editor.mp4` demonstrates rapid editing, expansion and
Back with no dispatch. An API36.1 MessageQueue.nextLegacy/Espresso idle hang was observed during
concurrent screenrecord and rotation; its cause is unproven, and the hang did not
reproduce without recording. Rotation verification ran
without recording. This recording does not claim physical/OEM keyboard acceptance.
