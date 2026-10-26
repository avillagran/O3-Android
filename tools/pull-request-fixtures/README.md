# Pull request fixture export

`Export.java` projects the synthetic inputs in `app/src/test/resources/pullrequests/`
with the pinned 0.3.5 AAR's `T3Client.domain` offline: `sidebarBadge` for list entries,
`markdown.blocks` for detail and discussion. Compile/run with the AAR's classes.jar,
org.json and Kotlin stdlib; set `-Djava.library.path=libs/host/linux-x86_64`.
Pass list.json, detail.json, checks.json and activity.json as arguments.

`PullRequestFixturesContractTest` verifies all four committed views against the actual
JNI reader every JVM run. Robolectric uses views to avoid loading JNI in incompatible
sandboxes. Android instrumentation executes the real reader from debug input assets.
