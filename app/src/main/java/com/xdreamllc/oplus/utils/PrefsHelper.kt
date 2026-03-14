package com.xdreamllc.oplus.utils

import com.xdreamllc.oplus.Config
import de.robv.android.xposed.XSharedPreferences

/**
 * Helper for reading module preferences from hook (non-app) processes.
 * Uses XSharedPreferences which can read another app's shared_prefs file
 * without needing that app's Context.
 */
object PrefsHelper {

    private var xPrefs: XSharedPreferences? = null

    /**
     * Get the module's XSharedPreferences, creating it if needed.
     * Always calls reload() to get the latest values.
     */
    fun getPrefs(): XSharedPreferences? {
        if (xPrefs == null) {
            try {
                xPrefs = XSharedPreferences("com.xdreamllc.oplus", Config.PREFS_NAME)
                xPrefs?.makeWorldReadable()
            } catch (e: Throwable) {
                XLog.error("Failed to create XSharedPreferences: ${e.message}")
                return null
            }
        }
        try {
            xPrefs?.reload()
        } catch (_: Throwable) {}
        return xPrefs
    }

    fun getPowerMode(): Int {
        return getPrefs()?.getInt(Config.KEY_POWER_MODE, Config.POWER_MODE_GEMINI)
            ?: Config.POWER_MODE_GEMINI
    }

    fun isGestureBarEnabled(): Boolean {
        return getPrefs()?.getBoolean(Config.KEY_GESTURE_BAR_ENABLED, true) ?: true
    }
}
