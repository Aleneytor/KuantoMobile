# Add project specific ProGuard rules here.

# ── WebView JavaScript Interface ──
-keepclassmembers class com.kuanto.webview.MainActivity$WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Google AdMob ──
-keep class com.google.android.gms.ads.** { *; }

# ── Google Play In-App Review ──
-keep class com.google.android.play.core.** { *; }

# ── WorkManager ──
-keep class androidx.work.** { *; }
-keep class com.kuanto.webview.RateUpdateWorker { *; }

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── General Android ──
-keepattributes *Annotation*
-keepattributes JavascriptInterface

# ── Suppress warnings ──
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
