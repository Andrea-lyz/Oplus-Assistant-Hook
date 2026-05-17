package com.xdreamllc.oplus.hook

import android.os.Message
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.TriggerHelper
import com.xdreamllc.oplus.utils.XLog

/**
 * Intercepts the power button long-press assistant entry in ColorOS.
 */
object ButtonInterceptorHooker {

    private const val MSG_POWER_LONG_PRESS_FOR_SPEECH = 0x3F3

    fun hook(classLoader: ClassLoader) {
        hookOplusSpeechHandler(classLoader)
        hookPhoneWindowManager(classLoader)
    }

    private fun hookOplusSpeechHandler(classLoader: ClassLoader) {
        try {
            val owner = XposedApi.requireClass(
                "com.android.server.policy.PhoneWindowManagerExtImpl\$OplusSpeechHandler",
                classLoader
            )
            val method = XposedApi.getMethod(owner, "handleMessage", Message::class.java)
            XposedApi.hook(method) { chain ->
                try {
                    val message = chain.getArg(0) as? Message
                    if (message?.what == MSG_POWER_LONG_PRESS_FOR_SPEECH && tryIntercept()) {
                        return@hook null
                    }
                } catch (e: Throwable) {
                    XLog.error("OplusSpeechHandler hook error: ${e.message}")
                }
                chain.proceed()
            }
            XLog.debug("Hooked OplusSpeechHandler.handleMessage")
        } catch (e: Throwable) {
            XLog.error("Hook OplusSpeechHandler failed: ${e.message}")
        }
    }

    private fun hookPhoneWindowManager(classLoader: ClassLoader) {
        try {
            val owner = XposedApi.requireClass("com.android.server.policy.PhoneWindowManager", classLoader)

            hookPowerLongPress(owner, arrayOf(Integer.TYPE), "powerLongPress(int)")
            hookPowerLongPress(owner, emptyArray(), "powerLongPress()")
        } catch (e: Throwable) {
            XLog.error("PhoneWindowManager hooks failed: ${e.message}")
        }
    }

    private fun hookPowerLongPress(owner: Class<*>, parameterTypes: Array<Class<*>>, label: String) {
        try {
            val method = XposedApi.getMethod(owner, "powerLongPress", *parameterTypes)
            XposedApi.deoptimize(method)
            XposedApi.hook(method) { chain ->
                try {
                    if (tryIntercept()) {
                        return@hook null
                    }
                } catch (e: Throwable) {
                    XLog.error("$label hook error: ${e.message}")
                }
                chain.proceed()
            }
            XLog.debug("Hooked PhoneWindowManager.$label")
        } catch (e: Throwable) {
            XLog.error("Hook $label failed: ${e.message}")
        }
    }

    private fun tryIntercept(): Boolean {
        val mode = HookGuard.powerMode()
        if (!HookGuard.shouldInterceptPowerButton()) {
            return false
        }

        val context = SystemContextHooker.getContext()
        if (context != null) {
            TriggerHelper.performHapticFeedback(context)
        }

        when (mode) {
            Config.POWER_MODE_GEMINI -> {
                if (context != null) {
                    TriggerHelper.triggerGemini(context)
                } else {
                    XLog.error("No context available for Gemini trigger")
                    TriggerHelper.triggerGeminiFallbackByShell()
                }
            }

            Config.POWER_MODE_CIRCLE -> {
                TriggerHelper.triggerCircleToSearch()
            }
        }

        return true
    }
}
