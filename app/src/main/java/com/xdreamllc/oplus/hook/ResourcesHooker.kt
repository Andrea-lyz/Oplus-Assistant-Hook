package com.xdreamllc.oplus.hook

import android.content.res.Resources
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.ResourceHookState
import com.xdreamllc.oplus.utils.XLog

/**
 * Temporarily spoofs framework resource strings during Gemini trigger sessions.
 */
object ResourcesHooker {

    private var contextualSearchKeyId = 0
    private var contextualSearchPackageNameId = 0

    fun hook(classLoader: ClassLoader) {
        resolveResourceIds(classLoader)

        try {
            val method = XposedApi.getMethod(Resources::class.java, "getString", Integer.TYPE)
            XposedApi.hook(method) { chain ->
                if (!ResourceHookState.isTempHookEnabled) {
                    return@hook chain.proceed()
                }

                when (chain.getArg(0) as Int) {
                    contextualSearchKeyId -> "omni.entry_point"
                    contextualSearchPackageNameId -> Config.PKG_GOOGLE
                    else -> chain.proceed()
                }
            }
            XLog.debug("Hooked Resources.getString(int)")
        } catch (e: Throwable) {
            XLog.error("ResourcesHooker: failed to hook Resources.getString: ${e.message}")
        }
    }

    private fun resolveResourceIds(classLoader: ClassLoader) {
        if (contextualSearchKeyId != 0) return

        try {
            val rString = XposedApi.requireClass("com.android.internal.R\$string", classLoader)
            contextualSearchKeyId = rString.getField("config_defaultContextualSearchKey")
                .getInt(null)
            contextualSearchPackageNameId = rString
                .getField("config_defaultContextualSearchPackageName")
                .getInt(null)
            XLog.debug("ResourcesHooker: keyId=$contextualSearchKeyId, pkgId=$contextualSearchPackageNameId")
        } catch (e: Throwable) {
            XLog.error("ResourcesHooker: failed to resolve resource IDs: ${e.message}")
        }
    }
}
