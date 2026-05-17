package com.xdreamllc.oplus.utils

import android.util.Log
import io.github.libxposed.api.XposedInterface

/**
 * Logging wrapper that writes to the modern Xposed log when available.
 */
object XLog {
    private const val TAG = "OplusAssistantHook"

    private var xposed: XposedInterface? = null

    fun attach(api: XposedInterface) {
        xposed = api
    }

    fun debug(msg: String) {
        xposed?.log(Log.DEBUG, TAG, msg) ?: Log.d(TAG, msg)
    }

    fun error(msg: String) {
        xposed?.log(Log.ERROR, TAG, msg) ?: Log.e(TAG, msg)
    }

    fun error(msg: String, t: Throwable) {
        xposed?.log(Log.ERROR, TAG, msg, t) ?: Log.e(TAG, msg, t)
    }
}
