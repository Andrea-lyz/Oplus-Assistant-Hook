package com.xdreamllc.oplus.hook

import android.os.Binder
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Hooks ContextualSearchManagerService to:
 * 1. Force SystemServer.deviceHasConfigString to return true for the contextual search resource ID
 * 2. Replace getContextualSearchPackageName to return GSA package
 * 3. No-op enforcePermission to bypass permission checks
 * 4. Clear calling identity around startContextualSearch to allow calls from SystemUI
 *
 * This is needed for Circle to Search to work on non-Pixel/Samsung devices.
 */
object ContextualSearchHooker {

    private var contextualSearchConfigId = 0

    /**
     * ThreadLocal to store the Binder identity token during startContextualSearch calls.
     * This allows before/after hooks to share the token across the same call.
     */
    private val identityTokenThreadLocal = ThreadLocal<Long>()

    fun hook(lpparam: LoadPackageParam) {
        // First: resolve the resource ID for config_defaultContextualSearchPackageName
        try {
            val rStringCls = XposedHelpers.findClass(
                "com.android.internal.R\$string",
                lpparam.classLoader
            )
            contextualSearchConfigId = XposedHelpers.getStaticIntField(
                rStringCls,
                "config_defaultContextualSearchPackageName"
            )
        } catch (e: Throwable) {
            XLog.error("Failed to resolve contextualSearchConfigId: ${e.message}")
        }

        // Hook 1: deviceHasConfigString → return true when checking contextual search config
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.SystemServer",
                lpparam.classLoader,
                "deviceHasConfigString",
                android.content.Context::class.java,
                Integer.TYPE,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val resId = param.args[1] as Int
                        if (resId == contextualSearchConfigId) {
                            param.result = true
                            XLog.debug("deviceHasConfigString: forced true for contextual search")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XLog.error("Hook deviceHasConfigString failed: ${e.message}")
        }

        // Hook 2: getContextualSearchPackageName → return GSA
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.contextualsearch.ContextualSearchManagerService",
                lpparam.classLoader,
                "getContextualSearchPackageName",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return Config.PKG_GOOGLE
                    }
                }
            )
        } catch (e: Throwable) {
            XLog.error("Hook getContextualSearchPackageName failed: ${e.message}")
        }

        // Hook 3: enforcePermission → no-op
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.contextualsearch.ContextualSearchManagerService",
                lpparam.classLoader,
                "enforcePermission",
                String::class.java,
                XC_MethodReplacement.DO_NOTHING
            )
        } catch (e: Throwable) {
            XLog.error("Hook enforcePermission failed: ${e.message}")
        }

        // Hook 4: Clear calling identity around startContextualSearch execution
        // This is the KEY fix for the gesture bar issue.
        // When SystemUI (UID ~10238) calls startContextualSearch via binder,
        // the system_server side sees the calling UID as SystemUI's UID.
        // Any internal permission checks (beyond our enforcePermission hook)
        // will fail because SystemUI doesn't have system-level permissions.
        // By clearing the calling identity, all downstream permission checks
        // will see UID 1000 (system) instead of SystemUI's UID.
        hookStartContextualSearch(lpparam)
    }

    private fun hookStartContextualSearch(lpparam: LoadPackageParam) {
        // Try the main service class
        val serviceClassName = "com.android.server.contextualsearch.ContextualSearchManagerService"
        val serviceCls = XposedHelpers.findClassIfExists(serviceClassName, lpparam.classLoader)

        if (serviceCls != null) {
            val hooked = XposedBridge.hookAllMethods(
                serviceCls,
                "startContextualSearch",
                createIdentityClearingHook("ContextualSearchManagerService")
            )
            if (hooked.isNotEmpty()) {
                XLog.debug("Hooked ${hooked.size} startContextualSearch method(s) on service class for identity clearing")
                return
            }
        }

        // Fallback: try inner Stub class (some AOSP versions structure it this way)
        val stubClassNames = arrayOf(
            "${serviceClassName}\$ContextualSearchManagerServiceStub",
            "${serviceClassName}\$Stub",
            "${serviceClassName}\$1"  // anonymous inner class
        )
        for (stubName in stubClassNames) {
            try {
                val stubCls = XposedHelpers.findClassIfExists(stubName, lpparam.classLoader)
                if (stubCls != null) {
                    val hooked = XposedBridge.hookAllMethods(
                        stubCls,
                        "startContextualSearch",
                        createIdentityClearingHook(stubName.substringAfterLast('.'))
                    )
                    if (hooked.isNotEmpty()) {
                        XLog.debug("Hooked ${hooked.size} startContextualSearch on $stubName for identity clearing")
                        return
                    }
                }
            } catch (_: Throwable) {}
        }

        // Last resort: scan all inner classes
        if (serviceCls != null) {
            try {
                for (innerCls in serviceCls.declaredClasses) {
                    val hooked = XposedBridge.hookAllMethods(
                        innerCls,
                        "startContextualSearch",
                        createIdentityClearingHook(innerCls.simpleName)
                    )
                    if (hooked.isNotEmpty()) {
                        XLog.debug("Hooked ${hooked.size} startContextualSearch on inner class ${innerCls.simpleName} for identity clearing")
                        return
                    }
                }
            } catch (_: Throwable) {}
        }

        XLog.error("Could not find startContextualSearch method to hook for identity clearing")
    }

    private fun createIdentityClearingHook(tag: String): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val callingUid = Binder.getCallingUid()
                    val callingPid = Binder.getCallingPid()
                    XLog.debug("startContextualSearch called from UID=$callingUid PID=$callingPid ($tag)")

                    // Clear calling identity so downstream checks see system UID (1000)
                    val token = Binder.clearCallingIdentity()
                    identityTokenThreadLocal.set(token)
                } catch (e: Throwable) {
                    XLog.error("startContextualSearch before hook error ($tag): ${e.message}")
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val token = identityTokenThreadLocal.get()
                    if (token != null) {
                        Binder.restoreCallingIdentity(token)
                        identityTokenThreadLocal.remove()
                    }
                    if (param.throwable != null) {
                        XLog.error("startContextualSearch threw exception ($tag): ${param.throwable.message}")
                    }
                } catch (e: Throwable) {
                    XLog.error("startContextualSearch after hook error ($tag): ${e.message}")
                }
            }
        }
    }
}
