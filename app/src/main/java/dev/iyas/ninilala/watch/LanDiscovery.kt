package dev.iyas.ninilala.watch

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.iyas.ninilala.LanProtocol
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

data class DiscoveredStream(
    val name: String,
    val host: String,
    val port: Int,
)

class LanDiscovery(context: Context) {
    private val tag = "LanDiscovery"
    private val nsdManager = context.getSystemService(NsdManager::class.java)

    fun discover(): Flow<List<DiscoveredStream>> = callbackFlow {
        val found = ConcurrentHashMap<String, DiscoveredStream>()
        val executor = Executor { runnable -> runnable.run() }

        fun emitCurrent() {
            trySend(found.values.sortedBy { it.name.lowercase() })
        }

        fun ingest(serviceInfo: NsdServiceInfo) {
            val host = if (Build.VERSION.SDK_INT >= 34) {
                serviceInfo.hostAddresses.firstOrNull()?.hostAddress
            } else {
                @Suppress("DEPRECATION")
                serviceInfo.host?.hostAddress
            } ?: return
            val txtPort = serviceInfo.attributes[LanProtocol.TXT_PORT]
                ?.toString(Charsets.UTF_8)
                ?.toIntOrNull()
            val port = txtPort ?: serviceInfo.port
            if (port <= 0) return
            found[serviceInfo.serviceName] = DiscoveredStream(
                name = serviceInfo.serviceName,
                host = host,
                port = port,
            )
            emitCurrent()
        }

        fun resolve(serviceInfo: NsdServiceInfo) {
            if (Build.VERSION.SDK_INT >= 34) {
                nsdManager.registerServiceInfoCallback(
                    serviceInfo,
                    executor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.w(tag, "service info callback failed: $errorCode")
                        }

                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            ingest(serviceInfo)
                            runCatching { nsdManager.unregisterServiceInfoCallback(this) }
                        }

                        override fun onServiceLost() = Unit

                        override fun onServiceInfoCallbackUnregistered() = Unit
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.w(tag, "resolve failed for ${serviceInfo.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            ingest(serviceInfo)
                        }
                    },
                )
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(tag, "discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != LanProtocol.SERVICE_TYPE &&
                    !serviceInfo.serviceType.contains("ninilala")
                ) {
                    return
                }
                resolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
                emitCurrent()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(tag, "discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "start discovery failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "stop discovery failed: $errorCode")
            }
        }

        nsdManager.discoverServices(
            LanProtocol.SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener,
        )
        emitCurrent()

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }
}
