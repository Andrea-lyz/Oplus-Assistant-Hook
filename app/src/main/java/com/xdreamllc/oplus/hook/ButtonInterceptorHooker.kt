package com.xdreamllc.oplus.hook

import android.os.Message
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.PrefsHelper
import com.xdreamllc.oplus.utils.TriggerHelper
import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Intercepts the power button long-press for voice assistant in ColorOS.
 *
 * Hooks:
 * 1. OplusSpeechHandler.handleMessage(Message) - intercepts MSG_POWER_LONG_PRESS_FOR_SPEECH (0x3F3)
 * 2. PhoneWindowManager.powerLongPress(int) - intercepts the long press event
 * 3. PhoneWindowManager.powerLongPress() - overloaded version
 */
object ButtonInterceptorHooker {

    private const val MSG_POWER_LONG_PRESS_FOR_SPEECH = 0x3F3

    fun hook(lpparam: LoadPackageParam) {
        // Hook 1: OplusSpeechHandler.handleMessage
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.policy.PhoneWindowManagerExtImpl\$OplusSpeechHandler",
                lpparam.classLoader,
                "handleMessage",
                Message::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val msg = param.args[0] as? Message ?: return
                            if (msg.what == MSG_POWER_LONG_PRESS_FOR_SPEECH) {
                                if (tryIntercept()) {
                                    param.result = null
                                }
                            }
                        } catch (e: Throwable) {
                            XLog.error("OplusSpeechHandler hook error: ${e.message}")
                        }
                    }
                }
            )
            XLog.debug("Hooked OplusSpeechHandler.handleMessage")
        } catch (e: Throwable) {
            XLog.error("Hook OplusSpeechHandler failed: ${e.message}")
        }

        // Hook 2 & 3: PhoneWindowManager.powerLongPress
        try {
            val pwmCls = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager",
                lpparam.classLoader
            )

            // powerLongPress(int)
            try {
                XposedHelpers.findAndHookMethod(
                    pwmCls,
                    "powerLongPress",
                    Integer.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (tryIntercept()) {
                                    param.result = null
                                }
                            } catch (e: Throwable) {
                                XLog.error("powerLongPress(int) hook error: ${e.message}")
                            }
                        }
                    }
                )
                XLog.debug("Hooked PhoneWindowManager.powerLongPress(int)")
            } catch (e: Throwable) {
                XLog.error("Hook powerLongPress(int) failed: ${e.message}")
            }

            // powerLongPress()
            try {
                XposedHelpers.findAndHookMethod(
                    pwmCls,
                    "powerLongPress",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (tryIntercept()) {
                                    param.result = null
                                }
                            } catch (e: Throwable) {
                                XLog.error("powerLongPress() hook error: ${e.message}")
                            }
                        }
                    }
                )
                XLog.debug("Hooked PhoneWindowManager.powerLongPress()")
            } catch (e: Throwable) {
                XLog.error("Hook powerLongPress() failed: ${e.message}")
            }
        } catch (e: Throwable) {
            XLog.error("PhoneWindowManager hooks failed: ${e.message}")
        }
    }

    /**
     * Checks configuration via XSharedPreferences and triggers the replacement action.
     * Returns true if the event was intercepted.
     */
    private fun tryIntercept(): Boolean {
        val mode = PrefsHelper.getPowerMode()

        if (mode == Config.POWER_MODE_NONE) {
            return false
        }

        // Get context using the same approach as the original module
        val ctx = SystemContextHooker.getContext()

        // Haptic feedback (best-effort)
        if (ctx != null) {
            TriggerHelper.performHapticFeedback(ctx)
        }

        when (mode) {
            Config.POWER_MODE_GEMINI -> {
                if (ctx != null) {
                    // Same approach as original module:
                    // new Intent("android.intent.action.VOICE_COMMAND")
                    //   .setPackage(GSA).addFlags(FLAG_ACTIVITY_NEW_TASK)
                    // context.startActivity(intent)
                    try {
                        val intent = android.content.Intent("android.intent.action.VOICE_COMMAND").apply {
                            setPackage(Config.PKG_GOOGLE)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        XLog.debug("Triggered Gemini via context.startActivity")
                    } catch (e: Throwable) {
                        XLog.error("startActivity failed: ${e.message}, trying am start")
                        try {
                            Runtime.getRuntime().exec(arrayOf(
                                "am", "start",
                                "-a", "android.intent.action.VOICE_COMMAND",
                                "-p", Config.PKG_GOOGLE
                            ))
                        } catch (_: Throwable) {}
                    }
                } else {
                    // Last resort: am start without context
                    XLog.error("No context available, using am start directly")
                    try {
                        Runtime.getRuntime().exec(arrayOf(
                            "am", "start",
                            "-a", "android.intent.action.VOICE_COMMAND",
                            "-p", Config.PKG_GOOGLE
                        ))
                    } catch (_: Throwable) {}
                }
            }
            Config.POWER_MODE_CIRCLE -> {
                TriggerHelper.triggerCircleToSearch()
            }
        }

        return true
    }
}
