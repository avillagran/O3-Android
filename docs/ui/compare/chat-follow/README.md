# Follow latest conversation content

LazyColumn preserves the visible row key when a new row arrives. That kept the
previous latest row anchored and left new replies outside the viewport.

The reader now remembers follow intent independently of that anchor and requests
index zero when native row revisions or fallback text change. Scrolling into
history pauses following; returning to the end or tapping Jump to latest resumes
it. Sending through the composer explicitly returns to the end, including when
the reader was reading history. Intent and the reading anchor survive recreation.

The incoming-row regression failed against the old behavior before the fix.
Six Compose JVM tests cover arrival, streaming growth, history, return-to-latest,
actual send-button wiring and recreation. Full app/core JVM tests, lint and APK
build passed. See [verification](verification.json). These checks are not physical
acceptance of a live streaming conversation.
