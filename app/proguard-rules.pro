# Xposed module - don't obfuscate the hook entry
-keep class com.xdreamllc.oplus.hook.HookEntry { *; }
-keep class com.xdreamllc.oplus.hook.** { *; }

# Xposed framework API (compileOnly)
-keep class io.github.libxposed.api.** { *; }

# Modern Xposed Service - the framework binds to the provider and calls the
# helper via reflection, and the parcelables on the binder boundary need their
# constructors and fields preserved.
-keep class io.github.libxposed.service.** { *; }
-keep class io.github.libxposed.service.XposedProvider { *; }
