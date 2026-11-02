# Server capability audit corrections

Client base remains 1ffb3bc. The library source pin is
6e17bb2276da5aee0912fbe084bc024819f65899 (0.3.8), based on origin/main
1498ad6, retaining gzip and brotli rather than downgrading to 0.3.7.

`NativeHttpTest.gzipAndBrotliAreDecodedThroughJni` exercises both encodings over
loopback HTTP. The large-shell test now runs the fetched 9 MiB shell through
ShellReducer, SidebarView.build and JNI and checks the displayed title.
Both tests fail against the old branch's host JNI library. `MaintenanceTest`
reproduces the missing relative hook URL, then tests trusted-origin resolution,
absolute T3 URL preservation and rejection of network-path references.

The maintenance editor already distinguishes rpc_rejected from lost responses.
The new lostScheduleResponseBlocksRetryEvenAfterRefresh test verifies that a
pending receipt blocks a second mutation even after a successful refresh.
No retry identity change is needed here; uncertain writes stay blocked.

The library's Android build now defaults to 16 MiB for shell HTTP and WebSocket
snapshots. The desktop limit remains 64 MiB. Host Java 21/JNI presentation of
10,000 synthetic threads peaked at 769196, 913080, 1189672 and 1966304 KiB RSS
for nominal 9, 16, 32 and 64 MiB title payloads. These include JVM/native copies
and JSON output strings; they do not measure ART or Compose on a device.
The 16 MiB phone default balances compatibility above 8 MiB with a bounded
cost. It does not establish low-memory-device acceptance.

Evidence: /var/tmp/o3-build/client-independent-audit/android-red.log and the
final Android build/test/lint logs in that directory. No real servers,
emulators or devices were used. Owner checkouts and the Obsidian vault were
untouched. Actual server interoperability and device rendering remain unverified;
T3 contract conclusions use source only from nightly 680152e27b2a961686389ca69a1557cbb2c55f08.

Final validation: all four Android ABI libraries and host JNI built from the pin.
Java 21 `testDebugUnitTest lint --max-workers=4 --no-parallel --no-daemon`
completed successfully: 1247 tests, 1240 passed, 7 optional skips, no failures or
errors (592 core, 623 app, 32 native chat). Both app and core lint passed.
The pinned-version smoke assertion was updated from 0.3.7 to 0.3.8 after it
failed against the corrected build. About notice consistency checks pass.
