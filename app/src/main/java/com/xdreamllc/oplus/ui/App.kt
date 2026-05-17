package com.xdreamllc.oplus.ui

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Application class that registers an Xposed service listener as soon as the process starts.
 *
 * On libxposed API 101 the framework no longer hooks the module's own app, and module ↔ hook
 * data exchange goes through the [XposedService] bridge: the framework pushes a binder to the
 * app via [io.github.libxposed.service.XposedProvider], and the helper exposes it as an
 * [XposedService] instance. The presence of this binder is also the only first-class way for the
 * settings UI to confirm that the framework actually loaded the module.
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    companion object {
        @Volatile
        var service: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                listener.onServiceStateChanged(service)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(state: XposedService?) {
            for (listener in listeners) {
                listener.onServiceStateChanged(state)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Companion.service = service
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (Companion.service === service) {
            Companion.service = null
        }
        dispatch(Companion.service)
    }
}
