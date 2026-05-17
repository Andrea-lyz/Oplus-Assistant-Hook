package com.xdreamllc.oplus.hook

import com.xdreamllc.oplus.utils.XLog

/**
 * Spoofs device properties inside the Google Search app process.
 */
object DeviceSpoofHooker {

    fun hook(classLoader: ClassLoader) {
        try {
            val buildClass = XposedApi.requireClass("android.os.Build", classLoader)

            setStaticField(buildClass, "MANUFACTURER", "samsung")
            setStaticField(buildClass, "BRAND", "samsung")
            setStaticField(buildClass, "MODEL", "SM-S928B")
            setStaticField(buildClass, "PRODUCT", "e3s")
            setStaticField(buildClass, "DEVICE", "e3s")

            XLog.debug("GSA device properties spoofed to Samsung S24 Ultra")
        } catch (e: Throwable) {
            XLog.error("DeviceSpoofHooker failed: ${e.message}")
        }
    }

    private fun setStaticField(owner: Class<*>, name: String, value: String) {
        owner.getDeclaredField(name).apply {
            isAccessible = true
            set(null, value)
        }
    }
}
