package com.xdreamllc.oplus.hook

import android.content.Context
import com.xdreamllc.oplus.utils.XLog

/**
 * Captures a usable Context in the system_server process.
 */
object SystemContextHooker {

    @Volatile
    var systemContext: Context? = null
        private set

    fun hook(classLoader: ClassLoader) {
        hookCreateSystemContext(classLoader)
        hookActivityManagerSystemReady(classLoader)
    }

    private fun hookCreateSystemContext(classLoader: ClassLoader) {
        try {
            val systemServer = XposedApi.requireClass("com.android.server.SystemServer", classLoader)
            val method = XposedApi.getMethod(systemServer, "createSystemContext")
            XposedApi.hook(method) { chain ->
                val result = chain.proceed()
                captureFromSystemServer(chain.getThisObject(), "createSystemContext")
                result
            }
            XLog.debug("Hooked SystemServer.createSystemContext")
        } catch (e: Throwable) {
            XLog.error("Hook createSystemContext failed: ${e.message}")
        }
    }

    private fun hookActivityManagerSystemReady(classLoader: ClassLoader) {
        try {
            val amsClass = XposedApi.findClass(
                "com.android.server.am.ActivityManagerService",
                classLoader
            ) ?: return
            val method = XposedApi.getMethod(amsClass, "systemReady", Runnable::class.java)
            XposedApi.hook(method) { chain ->
                val result = chain.proceed()
                if (systemContext == null) {
                    systemContext = getContextViaActivityThread()
                    if (systemContext != null) {
                        XLog.debug("Captured Context via AMS.systemReady + ActivityThread")
                    }
                }
                result
            }
        } catch (_: Throwable) {
            // Best effort fallback.
        }
    }

    private fun captureFromSystemServer(systemServer: Any?, source: String) {
        if (systemServer == null) return
        try {
            val field = systemServer.javaClass.getDeclaredField("mSystemContext").apply {
                isAccessible = true
            }
            val context = field.get(systemServer) as? Context
            if (context != null) {
                systemContext = context
                XLog.debug("Captured SystemContext via $source")
            }
        } catch (e: Throwable) {
            XLog.error("$source hook error: ${e.message}")
        }
    }

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

    fun getContext(): Context? {
        systemContext?.let { return it }
        val context = getContextViaActivityThread()
        if (context != null) {
            systemContext = context
        }
        return context
    }
}
