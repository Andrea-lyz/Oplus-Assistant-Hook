package com.xdreamllc.oplus.hook

import android.content.Context
import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Captures a usable Context in the system_server process.
 *
 * Strategy:
 * 1. Try hooking SystemServer.run() → mSystemContext (may not fire since run() never returns)
 * 2. Hook ActivityManagerService.systemReady() → get context after system is ready
 * 3. Fallback: use ActivityThread.currentApplication() via reflection (same as original module)
 */
object SystemContextHooker {

    @Volatile
    var systemContext: Context? = null
        private set

    fun hook(lpparam: LoadPackageParam) {
        // Strategy 1: Hook SystemServer.createSystemContext (called early in run())
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.SystemServer",
                lpparam.classLoader,
                "createSystemContext",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = XposedHelpers.getObjectField(
                                param.thisObject, "mSystemContext"
                            ) as? Context
                            if (ctx != null) {
                                systemContext = ctx
                                XLog.debug("Captured SystemContext via createSystemContext")
                            }
                        } catch (e: Throwable) {
                            XLog.error("createSystemContext hook error: ${e.message}")
                        }
                    }
                }
            )
            XLog.debug("Hooked SystemServer.createSystemContext")
        } catch (e: Throwable) {
            XLog.error("Hook createSystemContext failed: ${e.message}")
        }

        // Strategy 2: Hook ActivityManagerService.systemReady as backup
        try {
            val amsCls = XposedHelpers.findClassIfExists(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader
            )
            if (amsCls != null) {
                XposedHelpers.findAndHookMethod(
                    amsCls,
                    "systemReady",
                    Runnable::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (systemContext == null) {
                                systemContext = getContextViaActivityThread()
                                if (systemContext != null) {
                                    XLog.debug("Captured Context via AMS.systemReady + ActivityThread")
                                }
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {
            // Best effort
        }
    }

    /**
     * Get a usable Context via ActivityThread.currentApplication().
     * This is the same approach as the original module's ContextHelper.getGlobalContext().
     */
    fun getContextViaActivityThread(): Context? {
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getMethod("currentApplication")
            method.invoke(null) as? Context
        } catch (e: Throwable) {
            XLog.error("ActivityThread.currentApplication failed: ${e.message}")
            null
        }
    }

    /**
     * Get a usable Context, trying cached first, then ActivityThread fallback.
     * This mirrors the original module's ContextHelper.getSystemContext().
     */
    fun getContext(): Context? {
        systemContext?.let { return it }

        // Fallback: try ActivityThread.currentApplication()
        val ctx = getContextViaActivityThread()
        if (ctx != null) {
            systemContext = ctx
        }
        return ctx
    }
}
