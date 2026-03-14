package com.xdreamllc.oplus.utils

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import com.xdreamllc.oplus.Config

/**
 * Utility to trigger the replacement actions:
 * - Gemini via VoiceInteractionManagerService
 * - Circle to Search via ContextualSearchManager
 * - Haptic feedback
 */
object TriggerHelper {

    /**
     * Perform a short haptic feedback (click).
     */
    fun performHapticFeedback(context: Context) {
        try {
            val vibrator = context.getSystemService("vibrator") as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                XLog.error("Haptic: vibrator is null or has no vibrator")
            }
        } catch (e: Throwable) {
            XLog.error("Haptic feedback failed: ${e.message}")
        }
    }

    /**
     * Launch Gemini via a VOICE_COMMAND intent to the Google app.
     * This uses the real Gemini path via VoiceInteractionManagerService if available,
     * otherwise falls back to a simple intent.
     */
    fun triggerGemini(context: Context) {
        try {
            // Try the VoiceInteractionManagerService path first
            val serviceManagerCls = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerCls.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "voiceinteraction") as? IBinder
            if (binder == null) {
                XLog.error("VoiceInteractionService binder is null, falling back to intent")
                triggerGeminiFallback(context)
                return
            }

            val stubCls = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
            val asInterfaceMethod = stubCls.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)!!

            val bundle = Bundle().apply {
                putInt("invocation_type", 1)
                putLong("invocation_time_ms", System.currentTimeMillis())
                putBoolean("xiaobu_trigger", true)
            }

            // Enable temp resource hook for VIMS to spoof resources
            ResourceHookState.isTempHookEnabled = true

            val showSessionMethod = service.javaClass.getMethod(
                "showSessionFromSession",
                IBinder::class.java,
                Bundle::class.java,
                Integer.TYPE,
                String::class.java
            )
            showSessionMethod.invoke(service, null, bundle, 7, null)
            XLog.debug("🚀 Triggered Gemini via VIMS")
        } catch (e: Throwable) {
            XLog.error("Failed to trigger Gemini via VIMS: ${e.message}")
            ResourceHookState.isTempHookEnabled = false
            triggerGeminiFallback(context)
        }
    }

    private fun triggerGeminiFallback(context: Context) {
        try {
            val intent = Intent("android.intent.action.VOICE_COMMAND").apply {
                setPackage(Config.PKG_GOOGLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            XLog.debug("🚀 Triggered Gemini via fallback intent")
        } catch (e: Throwable) {
            XLog.error("Gemini fallback intent also failed: ${e.message}")
        }
    }

    /**
     * Trigger Circle to Search by calling the ContextualSearchManager service directly.
     */
    fun triggerCircleToSearch() {
        try {
            val serviceManagerCls = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerCls.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, "contextual_search") as? IBinder
            if (binder == null) {
                XLog.error("ContextualSearchService binder is null — service may not be registered")
                return
            }

            val iface = Class.forName("android.app.contextualsearch.IContextualSearchManager")
            val stubCls = Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
            val asInterfaceMethod = stubCls.getMethod("asInterface", IBinder::class.java)
            val service = asInterfaceMethod.invoke(null, binder)!!

            // Log calling process info for diagnostics
            val myUid = android.os.Process.myUid()
            val myPid = android.os.Process.myPid()
            XLog.debug("triggerCircleToSearch: calling from UID=$myUid PID=$myPid")

            val startMethod = iface.getDeclaredMethod("startContextualSearch", Integer.TYPE)
            startMethod.invoke(service, 2) // invocation type = 2
            XLog.debug("🚀 Triggered Circle to Search")
        } catch (e: Throwable) {
            // Log full exception with stack trace for remote debugging
            XLog.error("Failed to trigger Circle to Search", e)

            // Special handling for security-related exceptions
            val rootCause = findRootCause(e)
            if (rootCause is SecurityException) {
                XLog.error("⚠️ SecurityException detected — caller process likely lacks permission for contextual_search service. " +
                        "Root cause: ${rootCause.message}")
            } else if (rootCause.message?.contains("permission", ignoreCase = true) == true ||
                       rootCause.message?.contains("denied", ignoreCase = true) == true) {
                XLog.error("⚠️ Permission-related failure detected: ${rootCause.message}")
            }
        }
    }

    /**
     * Find the root cause of a throwable chain.
     */
    private fun findRootCause(t: Throwable): Throwable {
        var cause = t
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }
}

/**
 * Thread-safe shared state for the VIMS resource hook.
 */
object ResourceHookState {
    @Volatile
    var isTempHookEnabled = false

    val identityThreadLocal = ThreadLocal<Long>()
}
