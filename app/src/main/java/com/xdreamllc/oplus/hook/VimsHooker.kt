package com.xdreamllc.oplus.hook

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.xdreamllc.oplus.utils.ResourceHookState
import com.xdreamllc.oplus.utils.XLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Hooks VoiceInteractionManagerService$VoiceInteractionManagerServiceStub.showSessionFromSession
 * to detect when our Gemini trigger is invoked and manage the resource spoofing state.
 *
 * Before: if the bundle has xiaobu_trigger=true, enable temp resource hook and clear calling identity
 * After: restore calling identity and disable temp hook
 */
object VimsHooker {

    fun hook(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.server.voiceinteraction.VoiceInteractionManagerService\$VoiceInteractionManagerServiceStub",
                lpparam.classLoader,
                "showSessionFromSession",
                IBinder::class.java,
                Bundle::class.java,
                Integer.TYPE,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val bundle = param.args[1] as? Bundle ?: return
                        if (bundle.getBoolean("xiaobu_trigger", false)) {
                            ResourceHookState.isTempHookEnabled = true
                            val token = Binder.clearCallingIdentity()
                            ResourceHookState.identityThreadLocal.set(token)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bundle = param.args[1] as? Bundle ?: return
                        if (bundle.getBoolean("xiaobu_trigger", false)) {
                            ResourceHookState.isTempHookEnabled = false
                            val token = ResourceHookState.identityThreadLocal.get()
                            if (token != null) {
                                Binder.restoreCallingIdentity(token)
                                ResourceHookState.identityThreadLocal.remove()
                            }
                            XLog.debug("✅ VIMS session finished, identity restored")
                        }
                    }
                }
            )
            XLog.debug("Hooked VIMS showSessionFromSession")
        } catch (e: Throwable) {
            XLog.error("VimsHooker failed: ${e.message}")
        }
    }
}
