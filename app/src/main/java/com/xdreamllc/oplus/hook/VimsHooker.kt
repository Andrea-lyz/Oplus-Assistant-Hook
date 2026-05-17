package com.xdreamllc.oplus.hook

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.xdreamllc.oplus.utils.ResourceHookState
import com.xdreamllc.oplus.utils.XLog

/**
 * Manages temporary resource spoofing during VoiceInteractionManagerService calls.
 */
object VimsHooker {

    fun hook(classLoader: ClassLoader) {
        try {
            val owner = XposedApi.requireClass(
                "com.android.server.voiceinteraction.VoiceInteractionManagerService\$VoiceInteractionManagerServiceStub",
                classLoader
            )
            val method = XposedApi.getMethod(
                owner,
                "showSessionFromSession",
                IBinder::class.java,
                Bundle::class.java,
                Integer.TYPE,
                String::class.java
            )
            XposedApi.hook(method) { chain ->
                val bundle = chain.getArg(1) as? Bundle
                if (bundle?.getBoolean("xiaobu_trigger", false) != true) {
                    return@hook chain.proceed()
                }

                val token = Binder.clearCallingIdentity()
                ResourceHookState.isTempHookEnabled = true
                try {
                    chain.proceed()
                } finally {
                    ResourceHookState.isTempHookEnabled = false
                    Binder.restoreCallingIdentity(token)
                    XLog.debug("VIMS session finished, identity restored")
                }
            }
            XLog.debug("Hooked VIMS showSessionFromSession")
        } catch (e: Throwable) {
            XLog.error("VimsHooker failed: ${e.message}")
        }
    }
}
