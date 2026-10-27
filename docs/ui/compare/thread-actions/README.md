# Thread actions and swipe

Home's swipe and long-press sheet now send real thread management to the
environment instead of only Copy and Filter ([4.1.5](../../../UI_SPEC.md#415-actions)).

`ThreadManagement` derives the action context from `models.catalog` (pinning,
settlement, snooze, title regeneration, auto-settle opt-out) and from the
`authSession` permissions, requiring `orchestration:operate` for writes.
`HomeViewModel` and `AppScreens` pass it to the sheet, which keeps ot3's order and
preserves capability visibility and disables visible unavailable items.

Available: pin/unpin, settle/unsettle, snooze/unsnooze (presets plus a custom date
and time), rename, regenerate title, mark unread, auto-settle, archive and delete
with confirmation. Commands go through `MessageSender.sidebar` to the durable
outbox; an unknown outcome stays queued for explicit same-id recovery and is never
replayed automatically.

Swipe: partial drag reveals the primary and secondary actions, a full drag commits
the primary once with a haptic, RTL mirrors it, and Remove animations snaps the
reveal.

Still open: Move up/Move down have no portable fractional `pinOrderKey` builder,
New thread on {branch} and Project settings are not connected from the menu, the
header card is partial (title and project · environment · branch), and there is no
productive physical acceptance yet. Five shared Compose checks passed on the JVM;
the androidTest wrapper compiled and awaits emulator CI. Four core checks passed
with real JNI builders, a fake RPC and an in-memory outbox. Full JVM suite: 669
passed, 6 optional skipped; app/core lint and APK build passed. The known Xiaomi
received the signed APK with install-r; its installed digest matches the build.
No real thread was pinned, archived or deleted for acceptance. This is not a full
parity claim. See [verification](verification.json).
