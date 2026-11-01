# Composer recovery: refusals, composing text and offline attachments

Audit gaps 5, 7 and 8 of the mobile parity audit. Emulator evidence comes from API 34
(`ot3a`, 1080x2400) with the real Gboard and real DocumentsUI and Camera2 activities,
against `WorkspaceFixtureActivity` (`--ez parity true --es request editor`): real
navigation, view models and Rust, synthetic host. No live host or physical device.

The fixture accepts `--es connection offline|online` and `--es reject <reason>` through
`am start -f 0x20000000`. `ComposerFixture` in logcat records each upload and dispatch.

## Refused sends (gap 5)

Before: a refusal removed the outbox row, but `attachmentPending` stayed set, so the
composer said "Send outcome unknown" and offered to resend bytes the host had refused.

Now `SendResult.Failed.rejected` marks a definite answer. The draft is written first,
then the pending state goes, and the chips stay. The reason stays in a dismissible row
above the composer until it is dismissed or the message is resent, and the snackbar is
skipped. The feedback slot of `ThreadQueue` is inside its sheet, so the row cannot live
there: a first version did, and on the emulator the reason appeared nowhere.

`rpc_outcome_unknown`, `client_closed` and unexpected local failures keep the unknown
path. A row that is resent and refused returns its text to the draft, merged with any
newer typing, because the row was the only copy.

Observed on the emulator:

| Scenario | Result |
|---|---|
| Before the fix, refused send | "Send outcome unknown", Resend, Discard, Edit |
| Refused while composing | Reason row, `hello` in the editor, Send enabled, no Resend |
| Reason after 12 s | Still shown; the close button removes it |
| Journal locked by the previous build, Resend refused | Composer freed, text restored |

## Send while composing (gap 7)

Before: with Gboard, `hello` under its composing underline left Send grey and a tap did
nothing, so the gate was real. `sendable` no longer reads `composing`; `transformable`
still does. `ComposerParity.send` commits through `ComposerEditorState.finishComposition`,
publishes that edit, then sends, for every send surface. The late-command handling of
`composer-ime-selection-race.md` is unchanged and has a test for this order.

Observed: Send enabled during composition, logcat `dispatch text=[hello]` once, editor
emptied. The device suites around the composer pass 28/28, including
`ExpandedComposerDeviceTest` 6/6.

## Attachments and connection (gap 8)

Before: a file picked while disconnected left no chip and no message.

Now a result always lands. The chip appears at once, the bytes move to
`composer-attachments-v1/<key>.spool/<id>`, the journal records it, and a per-connection
pump uploads queued files one at a time. A capture is deleted only after its bytes are in
the spool, and a spool file only after upload or removal. A failed copy keeps the capture
until the chip is removed. A failed upload keeps its bytes: tapping the chip retries, and
so does the next connection. A file that arrives during a send joins the draft, not the
command. Picker and camera origins are saved, because rotation recreates the Activity.

Observed on the emulator:

| Scenario | Result |
|---|---|
| Before the fix, picked offline | No chip |
| DocumentsUI pick offline | "Waiting to upload notes.txt…", 17-byte spool file, Send held |
| Reconnect | One upload of 17 bytes, spool empty, chip ready, send carries `attachments=1` |
| Process killed with a queued chip | Chip restored from the journal, then uploaded |
| Rotated while the picker was open | Chip present after the Activity recreated |
| Camera2 capture offline | Chip shown, capture moved to the spool, 72584 bytes uploaded on reconnect |

## Limits

- A forgotten environment leaves its spool files behind, as it already left its journals:
  the key hashes environment and thread, so nothing can enumerate them.
- Nothing bounds the spool beyond 100 chips of 50 MiB per thread.
- Send stays disabled while offline. Queuing the message itself is audit gap 1.
- After a send the field is disabled, so the IME closes. That is unchanged.
