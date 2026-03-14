package com.xdreamllc.oplus.hook

import android.content.res.Resources
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.ResourceHookState
import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Hooks Resources.getString(int) to dynamically replace values for:
 * - config_defaultContextualSearchKey → "omni.entry_point"
 * - config_defaultContextualSearchPackageName → GSA package name
 *
 * Only active when isTempHookEnabled is true (during VIMS trigger sessions).
 */
object ResourcesHooker {

    private var contextualSearchKeyId = 0
    private var contextualSearchPackageNameId = 0

    fun hook(lpparam: LoadPackageParam) {
        // Resolve resource IDs
        if (contextualSearchKeyId == 0) {
            try {
                val rStringCls = Class.forName("com.android.internal.R\$string")
                contextualSearchKeyId = rStringCls.getField("config_defaultContextualSearchKey")
                    .getInt(null)
                contextualSearchPackageNameId = rStringCls
                    .getField("config_defaultContextualSearchPackageName")
                    .getInt(null)
                XLog.debug("ResourcesHooker: keyId=$contextualSearchKeyId, pkgId=$contextualSearchPackageNameId")
            } catch (e: Throwable) {
                XLog.error("ResourcesHooker: failed to resolve resource IDs: ${e.message}")
            }
        }

        // Hook Resources.getString(int)
        XposedHelpers.findAndHookMethod(
            Resources::class.java,
            "getString",
            Integer.TYPE,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!ResourceHookState.isTempHookEnabled) return

                    val resId = param.args[0] as Int
                    when (resId) {
                        contextualSearchKeyId -> {
                            param.result = "omni.entry_point"
                        }
                        contextualSearchPackageNameId -> {
                            param.result = Config.PKG_GOOGLE
                        }
                    }
                }
            }
        )
    }
}
