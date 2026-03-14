package com.xdreamllc.oplus.hook

import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.xdreamllc.oplus.utils.XLog

/**
 * Spoofs device properties (Build.MANUFACTURER, BRAND, MODEL, PRODUCT, DEVICE)
 * to Samsung S24 Ultra within the Google Search app process.
 * This is required because Circle to Search availability is gated by device model.
 */
object DeviceSpoofHooker {

    fun hook(lpparam: LoadPackageParam) {
        try {
            val buildCls = XposedHelpers.findClass("android.os.Build", lpparam.classLoader)

            XposedHelpers.setStaticObjectField(buildCls, "MANUFACTURER", "samsung")
            XposedHelpers.setStaticObjectField(buildCls, "BRAND", "samsung")
            XposedHelpers.setStaticObjectField(buildCls, "MODEL", "SM-S928B")
            XposedHelpers.setStaticObjectField(buildCls, "PRODUCT", "e3s")
            XposedHelpers.setStaticObjectField(buildCls, "DEVICE", "e3s")

            XLog.debug("✅ GSA device properties spoofed to Samsung S24 Ultra")
        } catch (e: Throwable) {
            XLog.error("DeviceSpoofHooker failed: ${e.message}")
        }
    }
}
