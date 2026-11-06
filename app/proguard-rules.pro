# Keep JNI Native Bridge
-keep class com.kw.ztransfer.NativePtpCore { *; }
-keepclassmembers class com.kw.ztransfer.NativePtpCore {
    native <methods>;
}
