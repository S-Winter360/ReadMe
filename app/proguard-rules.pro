# ReadMe ProGuard / R8 Optimization & Obfuscation Rules

# Preserve Kotlin Reflection & Coroutines metadata needed at runtime
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# AndroidX DataStore Preferences
-keepclassmembers class * extends androidx.datastore.preferences.core.Preferences {
    <fields>;
    <methods>;
}

# AndroidX PDF Viewer & OCR Service
-keep class androidx.pdf.** { *; }
-dontwarn androidx.pdf.**

# Compose runtime rules (R8 has built-in Compose rules, but keep Composable target methods safe)
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
    void <init>(androidx.compose.runtime.Composer, int);
}

# Parcelable / Serializable state models
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Android Framework Components & Accessibility Service
-keep public class com.readme.app.MainActivity extends androidx.activity.ComponentActivity
-keep public class com.readme.app.accessibility.ReadMeAccessibilityService extends android.accessibilityservice.AccessibilityService
-keep public class com.readme.app.reading.service.ReadMeReadingService extends android.app.Service
-keep public class com.readme.app.ReadMeApplication extends android.app.Application

# Preserve settings and state data class property getters/setters if accessed via reflection
-keepclassmembers class com.readme.app.settings.ReadMeSettings {
    <fields>;
}
-keepclassmembers class com.readme.app.reading.progress.ReadingProgress {
    <fields>;
}
-keepclassmembers class com.readme.app.accessibility.autonav.PageTurnCalibration {
    <fields>;
}

# Strip Android logging in release builds to improve performance and protect internal state
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
