package com.xdreamllc.oplus.utils

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import com.xdreamllc.oplus.Config

/**
 * Triggers replacement actions from hooked system flows.
 *
 * Design note for Gemini path:
 *
 * Older revisions tried to use VoiceInteractionManagerService.showSessionForActiveService to mimic
 * an assistant gesture and ride the SystemUI handoff. That path is racy on ColorOS: rewriting
 * Settings.Secure.assistant moments before showSession causes the active VoiceInteractionService
 * to rebind asynchronously, and showSession is dispatched before the new service is ready. The
 * result is the well-known "screen pulses but Gemini never appears" symptom that requires the user
 * to manually switch the default assistant to None and back to Google to recover.
 *
 * The intent route below is what Google's own quick-settings tile uses to wake Gemini, doesn't
 * touch Settings.Secure on every press, and is observably reliable on the same hardware.
 */
object TriggerHelper {

    private const val KEY_ASSISTANT = "assistant"
    private const val KEY_VOICE_INTERACTION_SERVICE = "voice_interaction_service"

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
     * Primary Gemini trigger. Fires android.intent.action.VOICE_COMMAND scoped to the Google app
     * package; that activity is registered by Gemini and resolves on every device that has the
     * Google app installed.
     */
    fun triggerGemini(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                setPackage(Config.PKG_GOOGLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val token = Binder.clearCallingIdentity()
            try {
                context.startActivity(intent)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            XLog.debug("Triggered Gemini via VOICE_COMMAND intent")
        } catch (e: Throwable) {
            XLog.error("Gemini intent path failed: ${e.message}, trying shell fallback")
            triggerGeminiFallbackByShell()
        }
    }

    /**
     * Alternate Gemini trigger that goes through VoiceInteractionManagerService. Kept for cases
     * where a caller explicitly wants the SystemUI assistant gesture handoff (screenshot etc.).
     * Not used on the default press path because of the rebind race described in the file header.
     */
    fun triggerGeminiViaVoiceService(context: Context) {
        ensureGoogleAssistant(context)

        try {
            val binder = getService("voiceinteraction")
            if (binder == null) {
                XLog.error("VoiceInteractionService binder is null, falling back to intent")
                triggerGemini(context)
                return
            }

            val stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
            val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val bundle = newAssistantInvocationBundle()

            ResourceHookState.isTempHookEnabled = true
            val invoked = try {
                invokeVoiceInteractionService(service, bundle)
            } finally {
                ResourceHookState.isTempHookEnabled = false
            }

            if (invoked) {
                XLog.debug("Triggered Gemini via VoiceInteractionManagerService")
            } else {
                XLog.error("VIMS showSession returned non-true; falling back to intent")
                triggerGemini(context)
            }
        } catch (e: Throwable) {
            XLog.error("Failed to trigger Gemini via VIMS: ${e.message}")
            ResourceHookState.isTempHookEnabled = false
            triggerGemini(context)
        }
    }

    private fun ensureGoogleAssistant(context: Context) {
        try {
            val component = findGoogleVoiceInteractionService(context)
            if (component == null) {
                XLog.error("Google VoiceInteractionService not found")
                return
            }

            val value = component.flattenToString()
            val resolver = context.contentResolver
            val assistant = Settings.Secure.getString(resolver, KEY_ASSISTANT)
            val voiceService = Settings.Secure.getString(resolver, KEY_VOICE_INTERACTION_SERVICE)

            if (assistant != value || voiceService != value) {
                val token = Binder.clearCallingIdentity()
                try {
                    putSecureString(resolver, KEY_ASSISTANT, value)
                    putSecureString(resolver, KEY_VOICE_INTERACTION_SERVICE, value)
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
                XLog.debug("Default assistant corrected to $value")
            }
        } catch (e: Throwable) {
            XLog.error("Failed to ensure Google default assistant: ${e.message}")
        }
    }

    private fun findGoogleVoiceInteractionService(context: Context): ComponentName? {
        val intent = Intent(VoiceInteractionService.SERVICE_INTERFACE).setPackage(Config.PKG_GOOGLE)
        val services = context.packageManager.queryIntentServices(intent, 0)
        val service = services.firstOrNull { info ->
            info.serviceInfo?.permission == android.Manifest.permission.BIND_VOICE_INTERACTION
        }?.serviceInfo ?: services.firstOrNull()?.serviceInfo ?: return null

        return ComponentName(service.packageName, service.name)
    }

    private fun putSecureString(resolver: ContentResolver, key: String, value: String) {
        try {
            val method = Settings.Secure::class.java.getMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Integer.TYPE
            )
            method.invoke(null, resolver, key, value, currentUserId())
        } catch (_: Throwable) {
            Settings.Secure.putString(resolver, key, value)
        }
    }

    private fun currentUserId(): Int {
        return try {
            Class.forName("android.app.ActivityManager")
                .getMethod("getCurrentUser")
                .invoke(null) as Int
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Treats only literal Boolean.TRUE as success. void / null / false all mean "I had nothing to
     * show" on real ROMs and should fall through to the intent path.
     */
    private fun invokeVoiceInteractionService(service: Any, bundle: Bundle): Boolean {
        val methods = service.javaClass.methods

        methods.firstOrNull { it.name == "showSessionForActiveService" }?.let { method ->
            method.isAccessible = true
            val args = method.parameterTypes.map { type ->
                when {
                    type == Bundle::class.java -> bundle
                    type == Integer.TYPE -> 7
                    type == java.lang.Boolean.TYPE -> true
                    IBinder::class.java.isAssignableFrom(type) -> null
                    else -> null
                }
            }.toTypedArray()
            val result = method.invoke(service, *args)
            return result == java.lang.Boolean.TRUE
        }

        methods.firstOrNull { it.name == "showSessionFromSession" }?.let { method ->
            method.isAccessible = true
            val args = method.parameterTypes.map { type ->
                when {
                    IBinder::class.java.isAssignableFrom(type) -> null
                    type == Bundle::class.java -> bundle
                    type == Integer.TYPE -> 7
                    type == String::class.java -> null
                    else -> null
                }
            }.toTypedArray()
            val result = method.invoke(service, *args)
            return result !is Boolean || result
        }

        return false
    }

    private fun newAssistantInvocationBundle(): Bundle {
        return Bundle().apply {
            putInt("invocation_type", 1)
            putLong("invocation_time_ms", System.currentTimeMillis())
            putBoolean("xiaobu_trigger", true)
            putString("invocation_source", "power_button")
        }
    }

    fun triggerGeminiFallbackByShell() {
        try {
            Runtime.getRuntime().exec(
                arrayOf(
                    "am",
                    "start",
                    "-a",
                    Intent.ACTION_VOICE_COMMAND,
                    "-p",
                    Config.PKG_GOOGLE
                )
            )
        } catch (e: Throwable) {
            XLog.error("Gemini shell fallback failed: ${e.message}")
        }
    }

    fun triggerCircleToSearch() {
        try {
            val binder = getService("contextual_search")
            if (binder == null) {
                XLog.error("ContextualSearchService binder is null; service may not be registered")
                return
            }

            val iface = Class.forName("android.app.contextualsearch.IContextualSearchManager")
            val stubClass = Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
            val service = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!

            val myUid = android.os.Process.myUid()
            val myPid = android.os.Process.myPid()
            XLog.debug("triggerCircleToSearch: calling from UID=$myUid PID=$myPid")

            val startMethod = iface.getDeclaredMethod("startContextualSearch", Integer.TYPE)
            startMethod.invoke(service, 2)
            XLog.debug("Triggered Circle to Search")
        } catch (e: Throwable) {
            XLog.error("Failed to trigger Circle to Search", e)

            val rootCause = findRootCause(e)
            if (rootCause is SecurityException) {
                XLog.error(
                    "SecurityException detected; caller likely lacks contextual_search permission. " +
                        "Root cause: ${rootCause.message}"
                )
            } else if (
                rootCause.message?.contains("permission", ignoreCase = true) == true ||
                rootCause.message?.contains("denied", ignoreCase = true) == true
            ) {
                XLog.error("Permission-related failure detected: ${rootCause.message}")
            }
        }
    }

    private fun getService(name: String): IBinder? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        return serviceManager.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    }

    private fun findRootCause(t: Throwable): Throwable {
        var cause = t
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }
}

object ResourceHookState {
    @Volatile
    var isTempHookEnabled = false
}
