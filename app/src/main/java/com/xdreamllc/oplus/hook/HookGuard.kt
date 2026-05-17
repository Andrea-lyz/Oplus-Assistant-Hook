package com.xdreamllc.oplus.hook

import android.os.Process
import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.PrefsHelper

/**
 * Central policy gate for high-impact hooks.
 */
object HookGuard {
    private const val PKG_SYSTEMUI = "com.android.systemui"

    fun powerMode(): Int = PrefsHelper.getPowerMode()

    fun shouldInterceptPowerButton(): Boolean {
        return powerMode() != Config.POWER_MODE_NONE
    }

    fun shouldInterceptGestureBar(): Boolean {
        return PrefsHelper.isGestureBarEnabled()
    }

    fun shouldBlockOriginalAssistant(): Boolean {
        return shouldInterceptPowerButton()
    }

    fun shouldAllowContextualSearchFrom(callingUid: Int): Boolean {
        if (!isContextualSearchReplacementEnabled()) return false
        if (callingUid == Process.SYSTEM_UID) return true
        return packagesForUid(callingUid)?.contains(PKG_SYSTEMUI) == true
    }

    private fun isContextualSearchReplacementEnabled(): Boolean {
        return powerMode() == Config.POWER_MODE_CIRCLE || shouldInterceptGestureBar()
    }

    private fun packagesForUid(uid: Int): Array<String>? {
        return try {
            SystemContextHooker.getContext()?.packageManager?.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        }
    }
}
