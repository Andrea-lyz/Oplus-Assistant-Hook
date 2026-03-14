package com.xdreamllc.oplus.hook

import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.PrefsHelper
import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Blocks original voice assistant services from starting, preventing
 * interference when redirecting to Gemini / Circle to Search.
 *
 * Only active when power_mode != MODE_NONE.
 */
object AppBlockerHooker {

    private val targetClasses = arrayOf(
        "com.oplus.voiceassistant.service.BrenoServiceProxy",
        "com.oplus.voiceassistant.BrenoService",
        "com.heytap.voiceassistant.service.VoiceAssistantService"
    )

    fun hook(lpparam: LoadPackageParam) {
        // Check if power-button mode is active via XSharedPreferences
        val mode = PrefsHelper.getPowerMode()
        if (mode == Config.POWER_MODE_NONE) {
            XLog.debug("AppBlocker: power mode is NONE, skipping")
            return
        }

        for (className in targetClasses) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)

                // Hook all methods starting with "start"
                for (method in clazz.declaredMethods) {
                    if (method.name.startsWith("start")) {
                        XposedHelpers.findAndHookMethod(
                            clazz,
                            method.name,
                            *method.parameterTypes,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    XLog.debug("Blocked: ${className}.${method.name}")
                                    param.result = null
                                }
                            }
                        )
                    }
                }
            } catch (_: Throwable) {
                // Class may not exist on this ROM version, skip silently
            }
        }
    }
}
