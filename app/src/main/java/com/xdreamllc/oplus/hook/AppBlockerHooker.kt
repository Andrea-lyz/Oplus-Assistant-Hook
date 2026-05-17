package com.xdreamllc.oplus.hook

import com.xdreamllc.oplus.utils.XLog

/**
 * Blocks original Xiaobu assistant services only while replacement is enabled.
 */
object AppBlockerHooker {

    private val targetClasses = arrayOf(
        "com.oplus.voiceassistant.service.BrenoServiceProxy",
        "com.oplus.voiceassistant.BrenoService",
        "com.heytap.voiceassistant.service.VoiceAssistantService"
    )

    fun hook(classLoader: ClassLoader) {
        for (className in targetClasses) {
            try {
                val owner = XposedApi.findClass(className, classLoader) ?: continue
                for (method in owner.declaredMethods) {
                    if (!method.name.startsWith("start")) continue

                    XposedApi.hook(method) { chain ->
                        if (!HookGuard.shouldBlockOriginalAssistant()) {
                            return@hook chain.proceed()
                        }
                        XLog.debug("Blocked: ${className}.${method.name}")
                        null
                    }
                }
            } catch (_: Throwable) {
                // Class may not exist on this ROM version.
            }
        }
    }
}
