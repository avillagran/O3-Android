# Server capability audit corrections

Client base remains 1ffb3bc. The library source pin is
47bb556d57e445a177d5a51265acc6d2cb60efe0 (0.3.8), based on origin/main
1498ad6, retaining gzip and brotli rather than downgrading to 0.3.7.

`NativeHttpTest.gzipAndBrotliAreDecodedThroughJni` exercises both encodings over
loopback HTTP. The large-shell test now runs a fetched 2.5 MiB shell through
ShellReducer, SidebarView.build and JNI and checks the displayed title.
Both tests fail against the old branch's host JNI library. `MaintenanceTest`
reproduces the missing relative hook URL, then tests trusted-origin resolution,
absolute T3 URL preservation and rejection of network-path references.

The maintenance editor already distinguishes rpc_rejected from lost responses.
The new lostScheduleResponseBlocksRetryEvenAfterRefresh test verifies that a
pending receipt blocks a second mutation even after a successful refresh.
No retry identity change is needed here; uncertain writes stay blocked.

The Android shell HTTP/WebSocket limit is now 4 MiB. SidebarView opts into the
library's indexed-v1 representation: presented rows occur once, section entries
reference them with nesting overlays, and common defaults are restored by the
reader. The public Sidebar and raw JSON values are preserved. Identical section
and thread rows share one Kotlin object. Project raw data is copied by property,
without a JSON string round trip. Native vectors and the FFI envelope move values
instead of cloning them through JSON conversion.

The projection bounds aggregate input at 4 MiB, aggregate threads at 1,000 and
output at 8 MiB, rejecting oversize input explicitly without truncation. The row
count is checked in Kotlin before JNI as well. Desktop transport retains 64 MiB.
The old 16 MiB policy was unsafe; byte limits alone cannot bound many short rows.
The candidate boundary passes a Java 21 256 MiB host heap with an old view and
96 MiB reserved; this does not certify ART/Compose memory. Larger profiles need a
native retained-shell API with bounded windows rather than a higher byte cap.

Cross-origin absolute webhook URLs, network-path and non-http(s) values never
become trusted endpoints. The UI retains the original value as non-clickable,
selectable text with an origin warning, including T3's normal external relay URL.
Relative paths and same-origin absolute URLs continue to use the selected origin.

Evidence: /var/tmp/o3-build/client-independent-audit/android-red.log and the
final Android build/test/lint logs in that directory. No real servers,
emulators or devices were used. Owner checkouts and the Obsidian vault were
untouched. Actual server interoperability and device rendering remain unverified;
T3 contract conclusions use source only from nightly 680152e27b2a961686389ca69a1557cbb2c55f08.

Final validation: all four Android ABI libraries and host JNI were rebuilt from
47bb556. Java 21 unit tests, debug APK and both module lint tasks pass with four
workers, no parallel project execution, and temporary files under /var/tmp.
1,250 tests: 1,243 passed, seven optional skips, zero failures/errors (595 core,
623 app, 32 native chat). Three regressions first failed on the old adapter:
indexed row restoration/sharing, aggregate row admission before JNI, and origin
validation of absolute hook values. The Kotlin mapper preserves legacy JSON
values while sharing unmodified rows.

A fresh-JVM host benchmark using JNI, JSONObject and the compiled Kotlin reader
(-Xmx256m) measured empty/375-thread peaks of 60.3/187.8 MiB before and
63.0/146.4 MiB after. Fixtures are synthetic size/count stress representatives,
not a captured phone profile. With an old Kotlin view retained and 96 MiB
reserved, a 1,000-row, 4,175,013-byte request passed at 362.6 MiB process RSS.
Native RSS is outside the Java heap cap. This is host admission evidence,
not phone/ART certification or proof of the reported Home CPU cause.
Larger row counts and 9/16 MiB inputs are rejected explicitly. Logs and the
reproducible harness are in /var/tmp/o3-build/final-fixes/.
