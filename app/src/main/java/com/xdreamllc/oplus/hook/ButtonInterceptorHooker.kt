package com.xdreamllc.oplus.hook

import android.os.Message
import android.os.SystemClock
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.TriggerHelper
import com.xdreamllc.oplus.utils.XLog

/**
 * Intercepts the power button long-press assistant entry in ColorOS.
 *
 * Two distinct system paths fire on a single physical long-press in ColorOS:
 *   1. `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage` receives
 *      `MSG_POWER_LONG_PRESS_FOR_SPEECH` (0x3F3) on the OPPO speech handler thread.
 *   2. `PhoneWindowManager.powerLongPress` runs on the PWM power runnable.
 *
 * Both paths arrive within tens of milliseconds and used to call [tryIntercept] twice in parallel.
 * The two concurrent calls then raced inside [TriggerHelper.triggerGemini]:
 *   - Two `bindService` warm-ups against the Google VoiceInteractionService — the second connection
 *     interrupted the first VIS's `onServiceConnected` lifecycle.
 *   - Two `showSessionForActiveService` requests into VIMS — VIMS's anti-overlap policy tears
 *     down the half-rendered first session, which is exactly the "vibrate, screen pulses,
 *     Gemini disappears" symptom users reported.
 *   - The `ResourceHookState.isTempHookEnabled` flag in [VimsHooker] is a single global boolean,
 *     so a second concurrent showSession would flip it off mid-flight for the first one.
 *
 * The fix is to debounce at the intercept entry point: the *first* power long-press inside the
 * debounce window dispatches the assistant; subsequent calls inside the window still consume the
 * event (return true so the chain returns null and Xiaobu never gets a turn) but skip the
 * dispatch. We keep both upstream hooks because either of them can be the only one that fires on
 * a given ColorOS build / state, and we want maximum coverage; debouncing collapses them back
 * into a single logical trigger.
 */
object ButtonInterceptorHooker {

    private const val MSG_POWER_LONG_PRESS_FOR_SPEECH = 0x3F3

    /**
     * Window during which a second power long-press intercept is treated as a duplicate of the
     * first physical event and swallowed without re-dispatching the assistant. 1000 ms is
     * comfortably longer than the few-ms gap between OPPO's two notification paths and shorter
     * than any realistic intentional double press.
     */
    private const val DEBOUNCE_WINDOW_MS = 1000L

    @Volatile
    private var lastTriggerUptimeMs = 0L

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

        // Debounce: collapse OPPO's dual notification paths (OplusSpeechHandler message +
        // PhoneWindowManager.powerLongPress) into a single logical assistant invocation. The
        // duplicate is still consumed (return true) so neither AOSP nor Xiaobu fall through.
        val now = SystemClock.uptimeMillis()
        val previous = lastTriggerUptimeMs
        if (previous != 0L && now - previous < DEBOUNCE_WINDOW_MS) {
            XLog.debug("Power long-press debounced: ${now - previous}ms since last trigger")
            return true
        }
        lastTriggerUptimeMs = now

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
