package com.xdreamllc.oplus.hook

import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Main Xposed hook entry point.
 * Dispatches to specific hookers based on the loaded package.
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        when (lpparam.packageName) {
            // Hook our own app to indicate module is active
            "com.xdreamllc.oplus" -> {
                hookModuleActive(lpparam)
            }

            "android" -> {
                XLog.debug("Hooking system_server (android)")
                try {
                    SystemContextHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("SystemContextHooker failed", e)
                }
                try {
                    ContextualSearchHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("ContextualSearchHooker failed", e)
                }
                try {
                    ButtonInterceptorHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("ButtonInterceptorHooker failed", e)
                }
                try {
                    AppBlockerHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("AppBlockerHooker failed", e)
                }
                try {
                    VimsHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("VimsHooker failed", e)
                }
                try {
                    ResourcesHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("ResourcesHooker failed", e)
                }
            }

            "com.android.systemui" -> {
                XLog.debug("Hooking SystemUI")
                try {
                    GestureBarHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("GestureBarHooker failed", e)
                }
            }

            "com.google.android.googlequicksearchbox" -> {
                XLog.debug("Hooking Google Search App")
                try {
                    DeviceSpoofHooker.hook(lpparam)
                } catch (e: Throwable) {
                    XLog.error("DeviceSpoofHooker failed", e)
                }
            }
        }
    }

    /**
     * Hook our own isModuleActive() method to return true when Xposed is active.
     */
    private fun hookModuleActive(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.xdreamllc.oplus.ui.MainActivity",
                lpparam.classLoader,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
            XLog.debug("Hooked isModuleActive -> true")
        } catch (e: Throwable) {
            XLog.error("Failed to hook isModuleActive: ${e.message}")
        }
    }
}
