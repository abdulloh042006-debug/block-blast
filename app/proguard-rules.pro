# Keep Google Mobile Ads SDK & UMP classes
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }

# The page calls these by name: R8 must not strip or rename them
-keepclassmembers class com.ultimategames.gridbloom.ui.MainActivity$NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class com.ultimategames.gridbloom.ads.** { *; }
