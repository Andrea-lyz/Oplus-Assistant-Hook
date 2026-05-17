package com.xdreamllc.oplus.hook

import com.xdreamllc.oplus.utils.PrefsHelper
import com.xdreamllc.oplus.utils.XLog
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable
import java.lang.reflect.Method

/**
 * Small project-local facade over the libxposed interceptor API.
 */
object XposedApi {
    private var api: XposedInterface? = null

    fun attach(api: XposedInterface) {
        this.api = api
        XLog.attach(api)
        PrefsHelper.attach(api)
    }

    fun frameworkOrNull(): XposedInterface? = api

    fun findClass(name: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    fun requireClass(name: String, classLoader: ClassLoader): Class<*> {
        return Class.forName(name, false, classLoader)
    }

    fun getMethod(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        return owner.getDeclaredMethod(name, *parameterTypes).apply {
            isAccessible = true
        }
    }

    fun hook(method: Method, intercept: (XposedInterface.Chain) -> Any?) {
        val framework = api ?: error("Xposed API is not attached")
        method.isAccessible = true
        framework.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain -> intercept(chain) }
    }

    fun hookAllMethods(owner: Class<*>, name: String, intercept: (XposedInterface.Chain) -> Any?): Int {
        var count = 0
        for (method in owner.declaredMethods) {
            if (method.name == name) {
                hook(method, intercept)
                count++
            }
        }
        return count
    }

    fun deoptimize(executable: Executable) {
        try {
            api?.deoptimize(executable)
        } catch (e: Throwable) {
            XLog.error("Failed to deoptimize ${executable.name}: ${e.message}")
        }
    }
}
