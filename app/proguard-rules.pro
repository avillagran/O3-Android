# lib-t3-rust JNI adapter: classes and members are looked up by name from native code.
-keep class dev.avillagran.t3.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
