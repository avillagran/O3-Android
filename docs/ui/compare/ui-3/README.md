# UI-3 — composer progress

## Queue increment (2026-10-26)

The real thread screen now renders `timeline.view.queue` above its composer. Its order,
held state, steering and reordering capabilities come directly from Rust. The strip
opens a queue sheet; Resume, Remove, Move up, Steer, and Edit in composer call
`queue.command`, then the persisted dispatch path. A failed/lost response uses the
existing error/outbox UI, with byte-identical retries instead of a fresh mutation.
Queue edits retain the unsent Room draft separately; Cancel restores that draft.
Editing retains the queue row's attachments and context. No optimistic removal or
reordering is applied: the thread stream remains authoritative.

References: ot3 `7b5b1de`, `ui/qml/T3Queue.qml:9-34` (strip), `:37-80`
(row controls); lib v0.3.4 `queue::command` / `queue::derive`. Android uses a sheet on
phones, as specified in UI_SPEC 4.4.6. Move up is currently an accessible step control;
source-style drag reorder is still pending.

## Evidence scope

- JVM integration tests exercise the actual v0.3.4 Rust queue builder and fake transport:
  validation before persistence, attachment/context retention and unknown-result retry.
- Compose/Robolectric checks visible pause/resume, correct target run, editing and
  disabled offline/unsupported actions. These are not emulator or live-server evidence.
- Instrumented/emulator screenshots and video are **pending**, after the UI-1 release
  owner's reserved session. No Xiaomi used.

## Remaining composer differences

- Model picker with favorites/provider rail; option descriptors and permission picker.
- Plan / Build and slash/completion workflows.
- New-message queue/steer delivery and attachment sends require the existing common
  `composer.message` builder request (LIB_REQUESTS §2); no Kotlin replacement is added.
- Upload/picker, expanded editor, source toolbar, draft saving feedback and hardware keys.
- Queue drag reorder, source reveal motion and screenshot/video acceptance.
- Queue-edit selections remain in the screen ViewModel through rotation; process death
  returns to the original saved draft and authoritative server queue rather than saving
  an unfinished queue edit as a new message.

This increment does not close UI-3 or claim parity with the desktop composer.
