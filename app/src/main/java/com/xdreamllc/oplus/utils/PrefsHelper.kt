package com.xdreamllc.oplus.utils

import android.content.SharedPreferences
import com.xdreamllc.oplus.Config
import io.github.libxposed.api.XposedInterface

/**
 * Reads the module SharedPreferences from hooked processes via the libxposed API 101 official
 * cross-process bridge.
 *
 * The settings UI writes through [io.github.libxposed.service.XposedService.getRemotePreferences],
 * the hook side reads through [XposedInterface.getRemotePreferences] -- both endpoints are backed
 * by the same LSPosed database, so values stay in sync without any file system or SELinux
 * gymnastics.
 */
object PrefsHelper {

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    fun attach(api: XposedInterface) {
        remotePrefs = try {
            api.getRemotePreferences(Config.PREFS_NAME).also {
                XLog.debug("PrefsHelper: remote preferences attached (${Config.PREFS_NAME})")
            }
        } catch (e: Throwable) {
            XLog.error("PrefsHelper: getRemotePreferences failed: ${e.message}")
            null
        }
    }

    fun getPowerMode(): Int {
        return remotePrefs?.getInt(Config.KEY_POWER_MODE, Config.DEFAULT_POWER_MODE)
            ?: Config.DEFAULT_POWER_MODE
    }

    fun isGestureBarEnabled(): Boolean {
        return remotePrefs?.getBoolean(
            Config.KEY_GESTURE_BAR_ENABLED,
            Config.DEFAULT_GESTURE_BAR_ENABLED
        ) ?: Config.DEFAULT_GESTURE_BAR_ENABLED
    }
}
