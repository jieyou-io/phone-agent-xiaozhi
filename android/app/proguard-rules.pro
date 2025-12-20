# Proguard rules for PhoneAgent
-keepattributes *Annotation*

# Keep Shizuku classes
-keep class rikka.shizuku.** { *; }

# Keep Gson serialization
-keepattributes Signature
-keep class com.xiaozhi.phoneagent.model.** { *; }


