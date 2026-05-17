package com.xdreamllc.oplus.hook

import com.xdreamllc.oplus.Config
import com.xdreamllc.oplus.utils.XLog
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Modern libxposed entry point.
 */
class HookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedApi.attach(this)
        XLog.debug("Module loaded in process=${param.processName}, system=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        XLog.debug("Hooking system_server")
        val classLoader = param.classLoader
        hookSafely("SystemContextHooker") { SystemContextHooker.hook(classLoader) }
        hookSafely("ContextualSearchHooker") { ContextualSearchHooker.hook(classLoader) }
        hookSafely("ButtonInterceptorHooker") { ButtonInterceptorHooker.hook(classLoader) }
        hookSafely("AppBlockerHooker") { AppBlockerHooker.hook(classLoader) }
        hookSafely("VimsHooker") { VimsHooker.hook(classLoader) }
        hookSafely("ResourcesHooker") { ResourcesHooker.hook(classLoader) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            "com.android.systemui" -> {
                XLog.debug("Hooking SystemUI")
                hookSafely("GestureBarHooker") { GestureBarHooker.hook(param.classLoader) }
            }

            Config.PKG_GOOGLE -> {
                XLog.debug("Hooking Google Search App")
                hookSafely("DeviceSpoofHooker") { DeviceSpoofHooker.hook(param.classLoader) }
            }
        }
    }

    private fun hookSafely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            XLog.error("$name failed", e)
        }
    }
}
