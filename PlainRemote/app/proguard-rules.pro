# WebRTC calls into these classes/methods from native (JNI) code, so
# R8 must not rename or strip them even though nothing in Java/Kotlin
# appears to reference them directly.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# NanoHTTPD (local Wi-Fi mode's embedded server) - no reflection, but
# keeping it avoids surprises from its internal thread/exception classes.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Firebase Firestore ships its own consumer ProGuard rules inside the
# AAR, which Gradle applies automatically - these are just a safety net.
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keepattributes Signature
-keepattributes *Annotation*

# org.json (used for the WebRTC control-command payloads) ships with
# the Android platform itself, but keep it in case a stricter shrink
# pass ever touches it.
-keep class org.json.** { *; }
