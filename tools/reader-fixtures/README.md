# Reader fixture export

`Export.java` calls the AAR's JNI `T3Client.domain("timeline.view", …)` offline for each
`*.projection.json`, writing `*.view.json`. It prints capabilities first: this export used
libraryVersion **0.3.4**, ABI **3**, 108 domain functions. Host JNI source
`e2ec5467389adf0885623480d7e2006a7ae3d019`, SHA256
`f432095e10380c2c125c9ef24f7994046504f2b56e7276fa0f1c0d034eb4c55c`.

To re-export, compile/run with the pinned AAR's `classes.jar`, `org.json` and Kotlin
stdlib on the classpath and `-Djava.library.path=libs/host/linux-x86_64`.
Arguments are the two files under `app/src/test/resources/timeline/*.projection.json`.
`ReaderContractTest` checks both committed outputs against the real JNI on every JVM run.
The Robolectric tests read these verified views, avoiding loading a single native library
from multiple incompatible Robolectric sandboxes. Instrumented rich reader tests execute
real Rust on Android through the graph/ViewModels/session and fake transport.
