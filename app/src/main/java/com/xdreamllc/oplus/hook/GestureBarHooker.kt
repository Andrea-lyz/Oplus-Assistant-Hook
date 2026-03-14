package com.xdreamllc.oplus.hook

import android.content.Context
import com.xdreamllc.oplus.utils.PrefsHelper
import com.xdreamllc.oplus.utils.TriggerHelper
import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Hooks the SystemUI nav bar gesture indicator long-press handler.
 * Replaces OplusOcrScreenBusiness.onLongPressed with Circle to Search.
 *
 * This is the functionality from the first module.
 * Note: This hook only fires when the system setting for gesture bar wakeup is enabled.
 *
 * Controlled by Config.KEY_GESTURE_BAR_ENABLED preference.
 */
object GestureBarHooker {

    fun hook(lpparam: LoadPackageParam) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenBusiness",
            lpparam.classLoader
        )

        if (clazz == null) {
            XLog.error("GestureBarHooker: OplusOcrScreenBusiness class not found")
            return
        }

        // Log all declared methods named onLongPressed for diagnostic purposes
        try {
            val methods = clazz.declaredMethods.filter { it.name == "onLongPressed" }
            XLog.debug("GestureBarHooker: found ${methods.size} onLongPressed method(s)")
            for (m in methods) {
                val paramTypes = m.parameterTypes.joinToString(", ") { it.simpleName }
                XLog.debug("GestureBarHooker:   -> onLongPressed($paramTypes)")
            }
        } catch (_: Throwable) {}

        // Use hookAllMethods to cover ALL overloads (with or without parameters)
        val hookedSet = XposedBridge.hookAllMethods(
            clazz,
            "onLongPressed",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        XLog.debug("GestureBarHooker: onLongPressed triggered (args=${param.args?.size ?: 0})")

                        // Check if gesture bar hook is enabled via XSharedPreferences
                        if (!PrefsHelper.isGestureBarEnabled()) {
                            XLog.debug("GestureBarHooker: disabled by pref, letting original method run")
                            return // Let original method execute normally
                        }

                        // Stop the original method from executing
                        param.result = null

                        // Get context for haptic feedback
                        val ctx = getContextFromThisObject(param)
                        XLog.debug("GestureBarHooker: context obtained = ${ctx != null}")

                        // Haptic feedback
                        if (ctx != null) {
                            TriggerHelper.performHapticFeedback(ctx)
                        } else {
                            XLog.error("GestureBarHooker: context is null, no haptic feedback")
                        }

                        // Trigger Circle to Search
                        TriggerHelper.triggerCircleToSearch()
                    } catch (e: Throwable) {
                        // MUST catch everything to prevent SystemUI crash
                        XLog.error("GestureBarHooker: error in before hook", e)
                    }
                }
            }
        )
        XLog.debug("Hooked ${hookedSet.size} OplusOcrScreenBusiness.onLongPressed method(s)")
    }

    private fun getContextFromThisObject(param: XC_MethodHook.MethodHookParam): Context? {
        // Try getContext() method
        try {
            val method = param.thisObject.javaClass.getMethod("getContext")
            return method.invoke(param.thisObject) as? Context
        } catch (_: Throwable) {}

        // Try mContext field (direct)
        try {
            return XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
        } catch (_: Throwable) {}

        // Try context field
        try {
            return XposedHelpers.getObjectField(param.thisObject, "context") as? Context
        } catch (_: Throwable) {}

        // Try mOcrContext field (some ColorOS versions)
        try {
            return XposedHelpers.getObjectField(param.thisObject, "mOcrContext") as? Context
        } catch (_: Throwable) {}

        // Walk all declared fields to find any Context-typed field
        try {
            val fields = param.thisObject.javaClass.declaredFields
            for (field in fields) {
                if (Context::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val value = field.get(param.thisObject) as? Context
                    if (value != null) {
                        XLog.debug("GestureBarHooker: found context in field '${field.name}'")
                        return value
                    }
                }
            }
            // Also check superclass fields
            var superCls = param.thisObject.javaClass.superclass
            while (superCls != null && superCls != Any::class.java) {
                for (field in superCls.declaredFields) {
                    if (Context::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        val value = field.get(param.thisObject) as? Context
                        if (value != null) {
                            XLog.debug("GestureBarHooker: found context in superclass field '${field.name}'")
                            return value
                        }
                    }
                }
                superCls = superCls.superclass
            }
        } catch (e: Throwable) {
            XLog.error("GestureBarHooker: field scan for Context failed: ${e.message}")
        }

        return null
    }
}
