package com.xdreamllc.oplus.hook

import android.os.Binder
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.XLog

/**
 * Enables Contextual Search on unsupported ColorOS builds while keeping permission bypasses scoped
 * to the module's own replacement flows.
 */
object ContextualSearchHooker {

    private var contextualSearchConfigId = 0

    fun hook(classLoader: ClassLoader) {
        resolveContextualSearchConfigId(classLoader)
        hookDeviceHasConfigString(classLoader)
        hookContextualSearchPackageName(classLoader)
        hookPermissionCheck(classLoader)
        hookStartContextualSearch(classLoader)
    }

    private fun resolveContextualSearchConfigId(classLoader: ClassLoader) {
        try {
            val rString = XposedApi.requireClass("com.android.internal.R\$string", classLoader)
            contextualSearchConfigId = rString.getField("config_defaultContextualSearchPackageName")
                .getInt(null)
        } catch (e: Throwable) {
            XLog.error("Failed to resolve contextualSearchConfigId: ${e.message}")
        }
    }

    private fun hookDeviceHasConfigString(classLoader: ClassLoader) {
        try {
            val owner = XposedApi.requireClass("com.android.server.SystemServer", classLoader)
            val method = XposedApi.getMethod(
                owner,
                "deviceHasConfigString",
                android.content.Context::class.java,
                Integer.TYPE
            )
            XposedApi.hook(method) { chain ->
                val resId = chain.getArg(1) as Int
                if (resId == contextualSearchConfigId) {
                    XLog.debug("deviceHasConfigString: forced true for contextual search")
                    true
                } else {
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {
            XLog.error("Hook deviceHasConfigString failed: ${e.message}")
        }
    }

    private fun hookContextualSearchPackageName(classLoader: ClassLoader) {
        try {
            val owner = XposedApi.requireClass(
                "com.android.server.contextualsearch.ContextualSearchManagerService",
                classLoader
            )
            val method = XposedApi.getMethod(owner, "getContextualSearchPackageName")
            XposedApi.hook(method) { Config.PKG_GOOGLE }
        } catch (e: Throwable) {
            XLog.error("Hook getContextualSearchPackageName failed: ${e.message}")
        }
    }

    private fun hookPermissionCheck(classLoader: ClassLoader) {
        try {
            val owner = XposedApi.requireClass(
                "com.android.server.contextualsearch.ContextualSearchManagerService",
                classLoader
            )
            val method = XposedApi.getMethod(owner, "enforcePermission", String::class.java)
            XposedApi.hook(method) { chain ->
                val callingUid = Binder.getCallingUid()
                if (HookGuard.shouldAllowContextualSearchFrom(callingUid)) {
                    XLog.debug("ContextualSearch permission bypassed for UID=$callingUid")
                    null
                } else {
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {
            XLog.error("Hook enforcePermission failed: ${e.message}")
        }
    }

    private fun hookStartContextualSearch(classLoader: ClassLoader) {
        val serviceClassName = "com.android.server.contextualsearch.ContextualSearchManagerService"
        val serviceClass = XposedApi.findClass(serviceClassName, classLoader)

        if (serviceClass != null) {
            val count = XposedApi.hookAllMethods(
                serviceClass,
                "startContextualSearch",
                createIdentityClearingHook("ContextualSearchManagerService")
            )
            if (count > 0) {
                XLog.debug("Hooked $count startContextualSearch method(s) on service class")
                return
            }
        }

        val stubClassNames = arrayOf(
            "${serviceClassName}\$ContextualSearchManagerServiceStub",
            "${serviceClassName}\$Stub",
            "${serviceClassName}\$1"
        )
        for (stubName in stubClassNames) {
            val stubClass = XposedApi.findClass(stubName, classLoader) ?: continue
            val count = XposedApi.hookAllMethods(
                stubClass,
                "startContextualSearch",
                createIdentityClearingHook(stubName.substringAfterLast('.'))
            )
            if (count > 0) {
                XLog.debug("Hooked $count startContextualSearch on $stubName")
                return
            }
        }

        if (serviceClass != null) {
            try {
                for (innerClass in serviceClass.declaredClasses) {
                    val count = XposedApi.hookAllMethods(
                        innerClass,
                        "startContextualSearch",
                        createIdentityClearingHook(innerClass.simpleName)
                    )
                    if (count > 0) {
                        XLog.debug("Hooked $count startContextualSearch on ${innerClass.simpleName}")
                        return
                    }
                }
            } catch (_: Throwable) {
            }
        }

        XLog.error("Could not find startContextualSearch method to hook for identity clearing")
    }

    private fun createIdentityClearingHook(tag: String): (io.github.libxposed.api.XposedInterface.Chain) -> Any? {
        return { chain ->
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            XLog.debug("startContextualSearch called from UID=$callingUid PID=$callingPid ($tag)")

            var token: Long? = null
            try {
                if (HookGuard.shouldAllowContextualSearchFrom(callingUid)) {
                    token = Binder.clearCallingIdentity()
                }
                chain.proceed()
            } catch (e: Throwable) {
                XLog.error("startContextualSearch threw exception ($tag): ${e.message}")
                throw e
            } finally {
                if (token != null) {
                    Binder.restoreCallingIdentity(token)
                }
            }
        }
    }
}
