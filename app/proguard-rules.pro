# Default ProGuard rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.meetingapp.api.openai.** { *; }
-keep class com.meetingapp.data.db.entity.** { *; }
