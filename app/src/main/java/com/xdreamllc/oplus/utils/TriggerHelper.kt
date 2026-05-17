package com.xdreamllc.oplus.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import com.xdreamllc.oplus.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Triggers replacement actions from hooked system flows.
 *
 * Design note for Gemini path:
 *
 * From `system_server` (uid 1000), `Intent.ACTION_VOICE_COMMAND` scoped to the Google app on
 * recent ColorOS / Google App combinations resolves only to `HandsFreeActivity`, which inspects
 * the caller and falls back to the Bluetooth SCO hands-free path when called by system uid,
 * `finish()`-ing immediately and producing the well-known "screen pulses, nothing happens"
 * symptom. Verified by inspecting `PackageManager.queryIntentActivities`: the result list contains
 * no other matching component.
 *
 * Earlier versions of the module worked around the failure by rewriting `Settings.Secure.assistant`
 * and calling `VoiceInteractionManagerService.showSessionForActiveService`, but the per-press
 * Settings.Secure rewrite triggered an asynchronous VIS rebind that raced with the showSession
 * dispatch, manifesting as the same symptom for unrelated reasons.
 *
 * The current strategy is:
 *  1. Pre-warm the Google app by `bindService`-ing its VoiceInteractionService so any
 *     frozen / cached process is brought back to a state where it can immediately render the
 *     assistant session. Without this step, showSession dispatches to a VIS that has not yet
 *     been (re)bound; the SystemUI assistant gesture animation finishes first and the user only
 *     sees the screen pulse.
 *  2. Call VIMS.showSessionForActiveService directly without touching Settings.Secure. The user
 *     is responsible for setting Google as the default assistant once (the settings UI offers a
 *     dedicated button for this), so VIMS already has the correct active service bound.
 *  3. If VIMS is unavailable, fall back to the intent route. This still fails on the
 *     HandsFreeActivity-only ROMs but at least covers older devices where the resolver works.
 *  4. Last resort: spawn `am start` via shell.
 */
object TriggerHelper {

    private const val WARMUP_TIMEOUT_MS = 400L

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
     * Primary Gemini trigger.
     *
     * On many ColorOS devices `Intent.ACTION_VOICE_COMMAND` only resolves to
     * `HandsFreeActivity` when fired from system_server (uid 1000). HandsFreeActivity inspects
     * the caller and only redirects to Gemini for shell-style callers; for system uid it falls
     * back to the Bluetooth SCO hands-free path and `finish()`es when no SCO session is active.
     * That is the well-known "screen pulses, nothing happens" symptom and an intent route can
     * not avoid it on these devices.
     *
     * The reliable path is `VoiceInteractionManagerService.showSessionForActiveService`, which
     * is what SystemUI itself uses for the assistant gesture. Earlier versions of this module
     * also rewrote `Settings.Secure.assistant` immediately before calling showSession; that race
     * caused the assistant rebind to overlap with the dispatch and produced occasional failures.
     * This implementation deliberately does not touch `Settings.Secure`. The settings UI exposes
     * a separate one-shot "set Google as default assistant" button instead, so the rebind always
     * happens out-of-band.
     *
     * Even with VIMS, the Google app process gets frozen / cached aggressively by the system on
     * recent Android builds. When that happens, showSession dispatches to a VIS that hasn't been
     * bound yet, the SystemUI assistant gesture animation finishes before Gemini's UI is ready,
     * and the user only sees the screen pulse. We pre-warm the Google app by `bindService`-ing
     * its VoiceInteractionService for ~400ms before issuing showSession; bindService is the
     * documented mechanism for unfreezing a cached app, so by the time showSession runs the
     * process is in a state where it can render its session.
     *
     * Order of attempts:
     *  1. Warm up Google app + VIMS showSessionForActiveService — works whenever the active
     *     VoiceInteractionService is already Google. No rebind, no race.
     *  2. Intent ACTION_VOICE_COMMAND scoped to the Google app — kept for devices where caller
     *     uid does not change HandsFreeActivity behaviour.
     *  3. Shell `am start` — last resort.
     */
    fun triggerGemini(context: Context) {
        val token = Binder.clearCallingIdentity()
        try {
            warmUpGoogleApp(context)
            if (tryShowSessionViaVims()) return

            val voiceCommand = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                setPackage(Config.PKG_GOOGLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, voiceCommand, "VOICE_COMMAND(framework-resolved)")) return

            XLog.error("All Gemini paths failed; falling back to shell")
            triggerGeminiFallbackByShell()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * Eagerly bind to Google's VoiceInteractionService so the framework un-freezes / re-spawns the
     * process, then unbind again. Returns once the connection is established or after a short
     * timeout — whichever comes first. The bind is intentionally synchronous because showSession
     * needs the VIS process to be live by the time it dispatches.
     */
    private fun warmUpGoogleApp(context: Context) {
        val component = findGoogleVoiceInteractionService(context) ?: run {
            XLog.error("warmUpGoogleApp: no Google VoiceInteractionService component")
            return
        }
        val intent = Intent("android.service.voice.VoiceInteractionService").apply {
            this.component = component
        }
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                XLog.debug("warmUpGoogleApp: connected to ${name.shortClassName}")
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}

            override fun onBindingDied(name: ComponentName) {
                XLog.error("warmUpGoogleApp: binding died for ${name.shortClassName}")
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                XLog.debug("warmUpGoogleApp: null binding for ${name.shortClassName} (process is alive though)")
                latch.countDown()
            }
        }

        val bound = try {
            // BIND_AUTO_CREATE forces the framework to start the process if it is not running.
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            XLog.error("warmUpGoogleApp: bindService denied: ${e.message}")
            false
        } catch (e: Throwable) {
            XLog.error("warmUpGoogleApp: bindService threw: ${e.message}")
            false
        }

        if (!bound) {
            // Even if bindService returned false, we still issued the request; some frameworks
            // start the process anyway. Skip the wait.
            try {
                context.unbindService(connection)
            } catch (_: Throwable) {
            }
            return
        }

        try {
            val arrived = latch.await(WARMUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!arrived) {
                XLog.debug("warmUpGoogleApp: timeout after ${WARMUP_TIMEOUT_MS}ms (proceeding anyway)")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                context.unbindService(connection)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Resolves the Google app's VoiceInteractionService component. The standard
     * `BIND_VOICE_INTERACTION` permission filter is preferred, but we fall back to any service
     * that matches the action in case the manifest changes shape.
     */
    private fun findGoogleVoiceInteractionService(context: Context): ComponentName? {
        return try {
            val intent = Intent("android.service.voice.VoiceInteractionService")
                .setPackage(Config.PKG_GOOGLE)
            val services = context.packageManager.queryIntentServices(intent, 0)
            val service = services.firstOrNull { info ->
                info.serviceInfo?.permission == android.Manifest.permission.BIND_VOICE_INTERACTION
            }?.serviceInfo ?: services.firstOrNull()?.serviceInfo ?: return null
            ComponentName(service.packageName, service.name)
        } catch (e: Throwable) {
            XLog.error("findGoogleVoiceInteractionService failed: ${e.message}")
            null
        }
    }

    /**
     * Calls `VoiceInteractionManagerService.showSessionForActiveService` directly. Returns true if
     * the framework reported the session was shown; false otherwise.
     *
     * Notably does NOT touch `Settings.Secure` — that is a separate concern handled in the UI.
     */
    private fun tryShowSessionViaVims(): Boolean {
        return try {
            val binder = getService("voiceinteraction") ?: run {
                XLog.error("VIMS: voiceinteraction binder is null")
                return false
            }
            val stubClass = Class.forName(
                "com.android.internal.app.IVoiceInteractionManagerService\$Stub"
            )
            val service = stubClass.getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return false

            val bundle = newAssistantInvocationBundle()
            val ok = invokeVoiceInteractionService(service, bundle)
            if (ok) {
                XLog.debug("Triggered Gemini via VIMS.showSessionForActiveService")
            } else {
                XLog.error("VIMS.showSession returned non-true; will fall back to intent")
            }
            ok
        } catch (e: Throwable) {
            XLog.error("VIMS path failed: ${e.message}")
            false
        }
    }

    private fun tryStart(context: Context, intent: Intent, label: String): Boolean {
        return try {
            context.startActivity(intent)
            XLog.debug("Triggered Gemini via $label")
            true
        } catch (e: android.content.ActivityNotFoundException) {
            XLog.error("$label: no matching activity")
            false
        } catch (e: Throwable) {
            XLog.error("$label failed: ${e.message}")
            false
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
