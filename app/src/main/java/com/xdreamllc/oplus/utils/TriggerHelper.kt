package com.xdreamllc.oplus.utils

import android.app.ActivityManager
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
 *  1. Pre-warm the Google app by `bindService`-ing its VoiceInteractionService so a
 *     potentially-frozen process is brought back to a state where it can immediately render
 *     the assistant session, then give it a short settle window once `onServiceConnected`
 *     fires so the VIS finishes its own internal initialisation before showSession dispatches.
 *  2. Call VIMS.showSessionForActiveService directly without touching Settings.Secure.
 *  3. Schedule an asynchronous verdict check ~250ms later. The check observes whether GSA
 *     reached an `importance <= IMPORTANCE_VISIBLE` state — i.e. whether the assistant
 *     window actually appeared. If not, the request was silently rejected (typically by
 *     GSA 17.26+'s in-process classifier returning the cached
 *     "ENTRYPOINT_SESSION not enabled" verdict), so we force-stop GSA, re-warm, and dispatch
 *     showSession again. The user perceives the kill+restart only as a small extra latency
 *     in the failure path; the happy path pays nothing.
 *  4. If the *synchronous* showSession failed (VIMS itself returned false), force-stop +
 *     aggressive re-warm + retry inline. The user already lost their first press, so the
 *     side effects of force-stopping GSA cost nothing here.
 *  5. Intent ACTION_VOICE_COMMAND scoped to the Google app — kept for legacy ROMs.
 *  6. Last resort: spawn `am start` via shell.
 */
object TriggerHelper {

    private const val WARMUP_TIMEOUT_MS = 600L
    private const val WARMUP_TIMEOUT_AGGRESSIVE_MS = 1000L
    private const val POST_CONNECT_SETTLE_MS = 120L
    private const val POST_CONNECT_SETTLE_AGGRESSIVE_MS = 250L
    private const val SHOW_SESSION_RETRY_DELAY_MS = 80L

    /**
     * Time to wait after `forceStopPackage` returns before issuing `bindService`. The kill is
     * asynchronous (SIGKILL + AMS bookkeeping), and `bindService` will silently bind to the
     * not-yet-cleared zombie ProcessRecord if we race it. 100ms comfortably exceeds the
     * observed AMS cleanup time on ColorOS while staying well under the user-perceptible
     * threshold for the long-press flow.
     */
    private const val FORCE_STOP_SETTLE_MS = 100L

    /**
     * How long to wait after dispatching showSession before sampling GSA's process importance
     * to decide whether the assistant window actually appeared. Tuned from the on-device
     * timeline: in successful runs the GSA process reaches `IMPORTANCE_VISIBLE` (or better)
     * within ~150ms of `Triggered Gemini via VIMS.showSession`; the rejection log
     * (`E/dwkq: dvrg: ... ENTRYPOINT_SESSION is not enabled`) lands at ~50ms. 250ms gives
     * both paths time to settle while keeping the "fail-and-recover" round-trip below ~1s.
     */
    private const val VERDICT_DELAY_MS = 250L

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
     * Strategy:
     *  - Fast-path: warm GSA, dispatch showSession. If VIMS itself rejected (synchronous
     *    failure), kill GSA and aggressively retry inline since we already lost the press.
     *  - Asynchronous verdict: 250ms after a successful dispatch, check whether GSA reached
     *    a foreground-equivalent process importance. If not, the request was silently
     *    rejected by GSA's in-process classifier (`ENTRYPOINT_SESSION not enabled`); kill
     *    GSA and re-dispatch. The user perceives this as a one-shot extra delay rather than
     *    a failed press they have to repeat manually.
     *
     * The verdict probe runs on a single-shot worker thread so the system_server hook caller
     * (`PhoneWindowManager` / `OplusSpeechHandler`) returns immediately.
     */
    fun triggerGemini(context: Context) {
        val token = Binder.clearCallingIdentity()
        try {
            warmUpGoogleApp(context, aggressive = false)
            if (!tryShowSessionViaVims(attempt = 1)) {
                // Synchronous failure path. Always kill GSA here — we have nothing to lose.
                XLog.debug("First showSession reported failure; force-stopping GSA and retrying with aggressive warmup")
                forceStopGoogleApp(context)
                try {
                    Thread.sleep(SHOW_SESSION_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                warmUpGoogleApp(context, aggressive = true)
                if (!tryShowSessionViaVims(attempt = 2)) {
                    val voiceCommand = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        setPackage(Config.PKG_GOOGLE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (!tryStart(context, voiceCommand, "VOICE_COMMAND(framework-resolved)")) {
                        XLog.error("All Gemini paths failed; falling back to shell")
                        triggerGeminiFallbackByShell()
                    }
                    return
                }
            }

            // VIMS accepted the request. We can't tell from VIMS alone whether GSA actually
            // rendered the assistant — that decision happens inside GSA a few tens of ms
            // later, on a background thread, with no IPC back to us. Probe asynchronously.
            scheduleAssistantVerdictCheck(context)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * Single-shot worker thread that, [VERDICT_DELAY_MS] after we dispatched a showSession,
     * checks GSA's process importance and force-stops + re-dispatches if it doesn't look
     * foregrounded.
     *
     * `IMPORTANCE_FOREGROUND` (100) is the assistant-window-rendered case;
     * `IMPORTANCE_VISIBLE` (200) covers a few transitional states GSA passes through; both
     * are accepted as success. Anything coarser (`IMPORTANCE_PERCEPTIBLE` = 230 and up) is
     * treated as "the request was silently dropped" — we never observed a successful Gemini
     * launch sit at >= 230 importance during the verdict window.
     */
    private fun scheduleAssistantVerdictCheck(context: Context) {
        Thread {
            try {
                Thread.sleep(VERDICT_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }

            if (didAssistantWindowAppear(context)) {
                XLog.debug("Verdict: GSA reached foreground after showSession; assistant rendered")
                return@Thread
            }

            XLog.error(
                "Verdict: GSA did not reach a foreground-equivalent importance within " +
                    "${VERDICT_DELAY_MS}ms; assuming the request was silently rejected and " +
                    "force-stopping ${Config.PKG_GOOGLE} before re-dispatching"
            )

            // Re-arm with caller identity cleared, otherwise force-stop / showSession will
            // run with the worker thread's identity (no FORCE_STOP_PACKAGES).
            val recoveryToken = Binder.clearCallingIdentity()
            try {
                forceStopGoogleApp(context)
                warmUpGoogleApp(context, aggressive = true)
                if (tryShowSessionViaVims(attempt = 3)) {
                    XLog.debug("Verdict-recovery showSession dispatched")
                } else {
                    XLog.error("Verdict-recovery showSession also failed; giving up silently")
                }
            } finally {
                Binder.restoreCallingIdentity(recoveryToken)
            }
        }.apply {
            name = "OplusHook-AssistantVerdict"
            isDaemon = true
            start()
        }
    }

    /**
     * @return `true` iff the Google app process is currently at a process importance that
     * indicates a visible UI. Returns `false` if the process is not running, only
     * cached/perceptible/service-bound, or if the query fails.
     */
    private fun didAssistantWindowAppear(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val processes = am.runningAppProcesses ?: return false
            val record = processes.firstOrNull { info ->
                info.pkgList?.any { it == Config.PKG_GOOGLE } == true
            } ?: run {
                XLog.error("Verdict: ${Config.PKG_GOOGLE} has no running process")
                return false
            }
            // FOREGROUND = 100, VISIBLE = 200. PERCEPTIBLE = 230 means audible but not on
            // screen, which is what we see when GSA is bound (warmup) but the assistant
            // window never appeared.
            val ok = record.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            if (!ok) {
                XLog.error("Verdict: GSA importance=${record.importance} (process=${record.processName})")
            }
            ok
        } catch (e: Throwable) {
            XLog.error("Verdict probe failed: ${e.message}")
            false
        }
    }

    /**
     * Force-stop `com.google.android.googlequicksearchbox` via reflection on
     * `ActivityManager.forceStopPackage(String)`. The method is `@hide` (annotation
     * `@RequiresPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)`) but the call is
     * issued from `system_server`, which holds that permission, so it succeeds.
     *
     * Why kill GSA at all:
     *
     * GSA 17.26+ caches its invocation-classifier verdicts in-process. Once the classifier
     * decides `ENTRYPOINT_SESSION` is disabled for "this caller / device combo", every
     * subsequent showSession from the same GSA process gets rejected with
     *
     *   E/dwkq: dvrg: Invocation source ENTRYPOINT_SESSION is not enabled
     *
     * even when the showSession Bundle is byte-for-byte identical to what SystemUI would send.
     * Empirically the only reliable way to flush that decision is to kill the process; a
     * manual "Force stop" from Settings -> Apps works 100% of the time, and so does this call.
     *
     * This function is only invoked on the failure paths (synchronous VIMS rejection or
     * asynchronous verdict probe failure), so the side effects below are paid only when
     * the user would otherwise see a failed press:
     *  - Hey Google hotword listening will be re-armed after cold start (a few seconds).
     *  - Background sync / Discover refresh of the Google app may be interrupted.
     *  - End-to-end latency adds ~400-600ms for the cold-start path.
     *
     * Failures here are non-fatal: if the reflective call throws (different OEM / API level),
     * we just continue, preserving the prior behaviour.
     */
    private fun forceStopGoogleApp(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: run {
                XLog.error("forceStopGoogleApp: ActivityManager service unavailable")
                return
            }
            val method = ActivityManager::class.java.getDeclaredMethod(
                "forceStopPackage",
                String::class.java
            )
            method.isAccessible = true
            method.invoke(am, Config.PKG_GOOGLE)
            XLog.debug("forceStopGoogleApp: kill request issued for ${Config.PKG_GOOGLE}")
            // SIGKILL + AMS bookkeeping is asynchronous; sleep briefly so the subsequent
            // bindService doesn't race the cleanup and bind to a half-torn-down ProcessRecord.
            Thread.sleep(FORCE_STOP_SETTLE_MS)
        } catch (e: NoSuchMethodException) {
            XLog.error("forceStopGoogleApp: forceStopPackage(String) not found on this ROM: ${e.message}")
        } catch (e: SecurityException) {
            XLog.error("forceStopGoogleApp: denied (caller likely lacks FORCE_STOP_PACKAGES): ${e.message}")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Throwable) {
            XLog.error("forceStopGoogleApp failed: ${e.message}")
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
     * `SHOW_SOURCE_ASSIST_GESTURE` from
     * [VoiceInteractionSession](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#SHOW_SOURCE_ASSIST_GESTURE).
     * SystemUI's `AssistManager.startAssist` passes this value when dispatching the assistant
     * gesture and power-button-long-press paths.
     *
     * Earlier revisions of this file accidentally used the value `7`, which is the bitwise OR
     * of `SHOW_WITH_ASSIST(1) | SHOW_WITH_SCREENSHOT(2) | SHOW_SOURCE_ASSIST_GESTURE(4)`.
     * Recent GSA builds (17.26.x) tightened their invocation classifier and treat that
     * combination as ambiguous, falling back to the disabled `ENTRYPOINT_SESSION` bucket.
     */
    private const val SHOW_SOURCE_ASSIST_GESTURE = 4

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
            // Per the public VoiceInteractionSession.onShow(Bundle, int) docs, GSA reads
            // android.intent.extra.TIME as "timing in milliseconds of the KeyEvent that
            // triggered Assistant" and android.intent.extra.ASSIST_INPUT_DEVICE_ID as the
            // device id of the input device that delivered the trigger key. SystemUI fills
            // both for power long-press; new GSA classifier rejects any "system" invocation
            // that lacks them, falling back to ENTRYPOINT_SESSION.
            putLong(Intent.EXTRA_TIME, SystemClock.uptimeMillis())
            putInt(Intent.EXTRA_ASSIST_INPUT_DEVICE_ID, KEYBOARD_DEVICE_ID_SYSTEM)
            // Module-internal marker so VimsHooker can recognise its own requests when the
            // call happens to be routed through showSessionFromSession on certain ColorOS
            // builds. GSA ignores unknown keys, so this is free to keep.
            putBoolean("xiaobu_trigger", true)
        }
    }

    /**
     * Input device id reported for synthesised system events. The actual value isn't important
     * to GSA's classifier; what matters is that the key is present and a reasonable int.
     * `-1` is the well-known "virtual / no real input device" sentinel.
     */
    private const val KEYBOARD_DEVICE_ID_SYSTEM = -1

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
