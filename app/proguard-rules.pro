# Xposed module - don't obfuscate the hook entry
-keep class com.xdreamllc.oplus.hook.HookEntry { *; }
-keep class com.xdreamllc.oplus.hook.** { *; }

# Keep Xposed related
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * {
    @de.robv.android.xposed.* <methods>;
}

# Keep MainActivity.isModuleActive so Xposed can hook it
-keepclassmembers class com.xdreamllc.oplus.ui.MainActivity {
    private boolean isModuleActive();
}
