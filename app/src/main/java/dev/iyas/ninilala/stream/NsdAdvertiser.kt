package dev.iyas.ninilala.stream

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.iyas.ninilala.LanProtocol

class NsdAdvertiser(context: Context) {
    private val tag = "NsdAdvertiser"
    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private var registeredName: String? = null

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            registeredName = serviceInfo.serviceName
            Log.i(tag, "registered ${serviceInfo.serviceName}")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(tag, "registration failed: $errorCode")
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            Log.i(tag, "unregistered ${serviceInfo.serviceName}")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(tag, "unregistration failed: $errorCode")
        }
    }

    fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = Build.MODEL
            serviceType = LanProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute(LanProtocol.TXT_PORT, port.toString())
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregister() {
        runCatching { nsdManager.unregisterService(registrationListener) }
        registeredName = null
    }
}
