# Add project specific ProGuard rules here.
-keep class io.socket.** { *; }
-keep class com.touchmirror.** { *; }
-dontwarn io.socket.**
-dontwarn okhttp3.**
-dontwarn okio.**
