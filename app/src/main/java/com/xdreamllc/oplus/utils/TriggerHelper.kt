package com.xdreamllc.oplus.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.xdreamllc.oplus.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Triggers replacement actions from hooked system flows.
 *
 * Design note for Gemini path:
 *
 * From `system_server` (uid 1000), `Intent.ACTION_VOICE_COMMAND` scoped to the Google app on
 * recent ColorOS / Google App combinations resolves only to `HandsFreeActivity`, which inspects
 * the caller and falls back to the Bluetooth SCO hands-free path when called by system uid,
 * `finish()`-ing immediately and producing the well-known "screen pulses, nothing happens"
 * symptom.
 *
 * The current strategy is:
 *  1. Pre-warm the Google app by `bindService`-ing its VoiceInteractionService so any
 *     frozen / cached process is brought back to a state where it can immediately render the
 *     assistant session, then give it a short settle window once `onServiceConnected` fires so
 *     the VIS finishes its own internal initialisation before showSession dispatches.
 *  2. Call VIMS.showSessionForActiveService directly without touching Settings.Secure.
 *  3. If the first attempt reports failure (VIMS returned false / VIS produced no UI), perform
 *     one aggressive retry: re-warm the app with a longer window and dispatch again. This
 *     covers the "VIS binding went stale after Google App was killed" case, which is the main
 *     reason power-button wake fails intermittently and needs a default-assistant toggle to
 *     recover.
 *  4. Intent ACTION_VOICE_COMMAND scoped to the Google app — kept for legacy ROMs.
 *  5. Last resort: spawn `am start` via shell.
 */
object TriggerHelper {

    private const val WARMUP_TIMEOUT_MS = 600L
    private const val WARMUP_TIMEOUT_AGGRESSIVE_MS = 1000L
    private const val POST_CONNECT_SETTLE_MS = 120L
    private const val POST_CONNECT_SETTLE_AGGRESSIVE_MS = 250L
    private const val SHOW_SESSION_RETRY_DELAY_MS = 80L

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
     * Order of attempts:
     *  1. Warm up Google app + VIMS showSessionForActiveService.
     *  2. If that reports failure, aggressively re-warm and try once more — this is the case
     *     that previously required the user to toggle the default assistant to recover.
     *  3. Intent ACTION_VOICE_COMMAND scoped to the Google app.
     *  4. Shell `am start`.
     */
    fun triggerGemini(context: Context) {
        val token = Binder.clearCallingIdentity()
        try {
            warmUpGoogleApp(context, aggressive = false)
            if (tryShowSessionViaVims(attempt = 1)) return

            // First attempt failed. The most common reason is that VIMS still holds a stale
            // binding to a Google App process that the system killed; bindService brings the
            // process back but VIMS's own session pipe needs an extra moment to recover.
            XLog.debug("First showSession attempt failed; retrying with aggressive warmup")
            try {
                Thread.sleep(SHOW_SESSION_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            warmUpGoogleApp(context, aggressive = true)
            if (tryShowSessionViaVims(attempt = 2)) return

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
     * process, then unbind again. Returns once the connection is established (plus a short settle
     * window) or after [WARMUP_TIMEOUT_MS] / [WARMUP_TIMEOUT_AGGRESSIVE_MS] — whichever comes
     * first.
     *
     * The aggressive variant is used after a failed showSession dispatch: it waits longer for
     * `onServiceConnected` and gives the VIS a noticeably bigger settle window, on the
     * assumption that we just observed an unresponsive VIS and the most likely cause is a slow
     * cold start.
     */
    private fun warmUpGoogleApp(context: Context, aggressive: Boolean) {
        val component = findGoogleVoiceInteractionService(context) ?: run {
            XLog.error("warmUpGoogleApp: no Google VoiceInteractionService component")
            return
        }
        val intent = Intent("android.service.voice.VoiceInteractionService").apply {
            this.component = component
        }
        val latch = CountDownLatch(1)
        val connected = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                XLog.debug("warmUpGoogleApp: connected to ${name.shortClassName}")
                connected.set(true)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {}

            override fun onBindingDied(name: ComponentName) {
                XLog.error("warmUpGoogleApp: binding died for ${name.shortClassName}")
                latch.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                XLog.debug("warmUpGoogleApp: null binding for ${name.shortClassName} (process is alive though)")
                connected.set(true)
                latch.countDown()
            }
        }

        // BIND_AUTO_CREATE forces the framework to start the process if it is not running.
        // BIND_IMPORTANT raises the binding's oom_adj briefly, which prevents the system from
        // immediately re-freezing the process before showSession runs.
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        val bound = try {
            context.bindService(intent, connection, flags)
        } catch (e: SecurityException) {
            XLog.error("warmUpGoogleApp: bindService denied: ${e.message}")
            false
        } catch (e: Throwable) {
            XLog.error("warmUpGoogleApp: bindService threw: ${e.message}")
            false
        }

        if (!bound) {
            try {
                context.unbindService(connection)
            } catch (_: Throwable) {
            }
            return
        }

        val timeoutMs = if (aggressive) WARMUP_TIMEOUT_AGGRESSIVE_MS else WARMUP_TIMEOUT_MS
        val settleMs = if (aggressive) POST_CONNECT_SETTLE_AGGRESSIVE_MS else POST_CONNECT_SETTLE_MS
        try {
            val arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!arrived) {
                XLog.debug("warmUpGoogleApp: timeout after ${timeoutMs}ms (proceeding anyway, aggressive=$aggressive)")
            } else if (connected.get()) {
                // Give the VIS a moment to finish its own initialisation. Without this, showSession
                // routinely dispatches into a VIS that has accepted the bind but not yet wired up
                // its session UI, which is one of the main causes of the "screen pulses but
                // Gemini never appears" symptom.
                val sleepDeadline = SystemClock.uptimeMillis() + settleMs
                while (true) {
                    val remaining = sleepDeadline - SystemClock.uptimeMillis()
                    if (remaining <= 0) break
                    try {
                        Thread.sleep(remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
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
    private fun tryShowSessionViaVims(attempt: Int): Boolean {
        return try {
            val binder = getService("voiceinteraction") ?: run {
                XLog.error("VIMS: voiceinteraction binder is null (attempt=$attempt)")
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
                XLog.debug("Triggered Gemini via VIMS.showSession (attempt=$attempt)")
            } else {
                XLog.error("VIMS.showSession returned non-true on attempt=$attempt; will fall back")
            }
            ok
        } catch (e: Throwable) {
            XLog.error("VIMS path failed (attempt=$attempt): ${e.message}")
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
     * Reflectively invokes the most appropriate showSession-style method on
     * `IVoiceInteractionManagerService`.
     *
     * Return semantics intentionally treat `void` and `null` as success: many OEM-modified
     * ROMs change the upstream `boolean showSessionForActiveService(...)` signature to `void`,
     * and earlier versions of this module mis-classified those calls as failures, which then
     * triggered the intent fallback (which itself fails on the same ROMs because of
     * HandsFreeActivity). Only an explicit `Boolean.FALSE` is treated as failure.
     */
    private fun invokeVoiceInteractionService(service: Any, bundle: Bundle): Boolean {
        val methods = service.javaClass.methods

        methods.firstOrNull { it.name == "showSessionForActiveService" }?.let { method ->
            method.isAccessible = true
            val args = method.parameterTypes.map { type ->
                when {
                    type == Bundle::class.java -> bundle
                    type == Integer.TYPE -> SHOW_SOURCE_ASSIST_GESTURE
                    type == java.lang.Boolean.TYPE -> true
                    IBinder::class.java.isAssignableFrom(type) -> null
                    else -> null
                }
            }.toTypedArray()
            val result = method.invoke(service, *args)
            return interpretShowSessionResult(method.returnType, result)
        }

        methods.firstOrNull { it.name == "showSessionFromSession" }?.let { method ->
            method.isAccessible = true
            val args = method.parameterTypes.map { type ->
                when {
                    IBinder::class.java.isAssignableFrom(type) -> null
                    type == Bundle::class.java -> bundle
                    type == Integer.TYPE -> SHOW_SOURCE_ASSIST_GESTURE
                    type == String::class.java -> null
                    else -> null
                }
            }.toTypedArray()
            val result = method.invoke(service, *args)
            return interpretShowSessionResult(method.returnType, result)
        }

        return false
    }

    private fun interpretShowSessionResult(returnType: Class<*>, result: Any?): Boolean {
        // void: only success path is "didn't throw" — caller cannot know whether the session
        // actually rendered, so trust the framework not to silently no-op.
        if (returnType == Void.TYPE) return true
        // boolean: literal true means success; literal false means the framework rejected the
        // request and we should fall through to the next attempt / intent path.
        if (returnType == java.lang.Boolean.TYPE) return result == true
        // Anything else: treat null/void-ish as success, only an explicit Boolean.FALSE as failure.
        return result != java.lang.Boolean.FALSE
    }

    /**
     * `SHOW_SOURCE_ASSIST_GESTURE`. SystemUI itself uses this when dispatching the assistant
     * gesture, so VIMS treats it as the canonical "user explicitly asked for the assistant"
     * source.
     */
    private const val SHOW_SOURCE_ASSIST_GESTURE = 7

    /**
     * AOSP SystemUI [AssistManager] invocation-type identifier for "power button long press".
     *
     * GSA's `dvmx` classifier reads `invocation_type` from the showSession Bundle and maps it
     * to an internal entry-point enum. Setting this to `6` produces
     * `ENTRYPOINT_POWER_BUTTON_LONG_PRESS`, which is enabled on every Gemini-aware GSA build.
     *
     * Setting any other value (or omitting it) causes GSA to fall back to `ENTRYPOINT_SESSION`,
     * a generic catch-all that is **not** enabled. The user-visible symptom is exactly what we
     * were chasing: the assistant disclosure animation flashes (SystemUI side), GSA logs
     *
     *   E/dwgh: Voice interaction session invocation propagation failed.
     *   E/dwgh: dvmx: Invocation source ENTRYPOINT_SESSION is not enabled
     *
     * and the Gemini window never renders.
     */
    private const val INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS = 6

    private fun newAssistantInvocationBundle(): Bundle {
        return Bundle().apply {
            // Tells GSA's invocation classifier this is the canonical power-button-long-press
            // entry point. Without this, the request is classified as ENTRYPOINT_SESSION and
            // rejected before any Gemini UI is rendered.
            putInt("invocation_type", INVOCATION_TYPE_POWER_BUTTON_LONG_PRESS)
            // SystemUI uses uptime, not wall clock. GSA mostly logs this for telemetry but we
            // mirror SystemUI exactly to avoid any anomaly heuristics keying off the value.
            putLong("invocation_time_ms", SystemClock.uptimeMillis())
            // Phone state (idle / ringing / off-hook). 0 = TelephonyManager.CALL_STATE_IDLE.
            // We don't have a TelephonyManager handle here and resolving it from system_server
            // is brittle; reporting "idle" matches the common case (user is on the launcher /
            // some app, not in a call) and GSA tolerates a missing or zero value.
            putInt("invocation_phone_state", 0)
            // Module-internal marker so VimsHooker can recognise its own requests when the
            // call happens to be routed through showSessionFromSession on certain ColorOS
            // builds. GSA ignores unknown keys, so this is free to keep.
            putBoolean("xiaobu_trigger", true)
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
