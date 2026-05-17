package com.xdreamllc.oplus.hook

import android.content.Context
import com.xdreamllc.oplus.utils.TriggerHelper
import com.xdreamllc.oplus.utils.XLog
import io.github.libxposed.api.XposedInterface

/**
 * Hooks the SystemUI nav bar gesture indicator long-press handler.
 */
object GestureBarHooker {

    fun hook(classLoader: ClassLoader) {
        val owner = XposedApi.findClass(
            "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenBusiness",
            classLoader
        )

        if (owner == null) {
            XLog.error("GestureBarHooker: OplusOcrScreenBusiness class not found")
            return
        }

        logLongPressMethods(owner)
        val count = XposedApi.hookAllMethods(owner, "onLongPressed") { chain ->
            onLongPressed(chain)
        }
        XLog.debug("Hooked $count OplusOcrScreenBusiness.onLongPressed method(s)")
    }

    private fun logLongPressMethods(owner: Class<*>) {
        try {
            val methods = owner.declaredMethods.filter { it.name == "onLongPressed" }
            XLog.debug("GestureBarHooker: found ${methods.size} onLongPressed method(s)")
            for (method in methods) {
                val paramTypes = method.parameterTypes.joinToString(", ") { it.simpleName }
                XLog.debug("GestureBarHooker: -> onLongPressed($paramTypes)")
            }
        } catch (_: Throwable) {
        }
    }

    private fun onLongPressed(chain: XposedInterface.Chain): Any? {
        if (!HookGuard.shouldInterceptGestureBar()) {
            XLog.debug("GestureBarHooker: disabled by pref, letting original method run")
            return chain.proceed()
        }

        try {
            XLog.debug("GestureBarHooker: onLongPressed triggered (args=${chain.getArgs().size})")

            val context = getContextFromThisObject(chain.getThisObject())
            XLog.debug("GestureBarHooker: context obtained = ${context != null}")

            if (context != null) {
                TriggerHelper.performHapticFeedback(context)
            } else {
                XLog.error("GestureBarHooker: context is null, no haptic feedback")
            }

            TriggerHelper.triggerCircleToSearch()
            return null
        } catch (e: Throwable) {
            XLog.error("GestureBarHooker: error in hook", e)
            return null
        }
    }

    private fun getContextFromThisObject(thisObject: Any?): Context? {
        if (thisObject == null) return null

        try {
            val method = thisObject.javaClass.getMethod("getContext")
            return method.invoke(thisObject) as? Context
        } catch (_: Throwable) {
        }

        for (fieldName in arrayOf("mContext", "context", "mOcrContext")) {
            try {
                val field = findField(thisObject.javaClass, fieldName)
                val value = field?.get(thisObject) as? Context
                if (value != null) return value
            } catch (_: Throwable) {
            }
        }

        try {
            var type: Class<*>? = thisObject.javaClass
            while (type != null && type != Any::class.java) {
                for (field in type.declaredFields) {
                    if (Context::class.java.isAssignableFrom(field.type)) {
                        field.isAccessible = true
                        val value = field.get(thisObject) as? Context
                        if (value != null) {
                            XLog.debug("GestureBarHooker: found context in field '${field.name}'")
                            return value
                        }
                    }
                }
                type = type.superclass
            }
        } catch (e: Throwable) {
            XLog.error("GestureBarHooker: field scan for Context failed: ${e.message}")
        }

        return null
    }

    private fun findField(owner: Class<*>, name: String): java.lang.reflect.Field? {
        var type: Class<*>? = owner
        while (type != null && type != Any::class.java) {
            try {
                return type.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        return null
    }
}
