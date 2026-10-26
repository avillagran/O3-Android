# lib-t3-rust JNI adapter: classes and members are looked up by name from native code.
-keep class dev.avillagran.t3.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Candidate OmaBridge JNI, built from a committed companion source.
-keep class dev.avillagran.ot3android.core.bridge.NativeBridge { *; }
