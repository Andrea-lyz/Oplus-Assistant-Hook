package com.xdreamllc.oplus.utils

import de.robv.android.xposed.XposedBridge

/**
 * Simple logging wrapper for Xposed module logging.
 */
object XLog {
    private const val TAG = "OplusAssistantHook"

    fun debug(msg: String) {
        XposedBridge.log("$TAG | D: $msg")
    }

    fun error(msg: String) {
        XposedBridge.log("$TAG | E: $msg")
    }

    fun error(msg: String, t: Throwable) {
        XposedBridge.log("$TAG | E: $msg")
        XposedBridge.log(t)
    }
}
