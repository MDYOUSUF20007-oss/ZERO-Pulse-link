package com.zero.pulselink.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket

class DiscoveryClient(private val context: Context) {

    private val tag = "ZeroPulseLinkDiscovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _discoveredServers = MutableStateFlow<List<DiscoveryBeacon>>(emptyList())
    val discoveredServers: StateFlow<List<DiscoveryBeacon>> = _discoveredServers

    private var discoveryScope: CoroutineScope? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null

    fun startDiscovery() {
        stopDiscovery()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        discoveryScope = scope

        startNsdDiscovery()
        startUdpBroadcastDiscovery(scope)
        probeAdbUsbLoopback(scope)
    }

    fun stopDiscovery() {
        discoveryScope?.cancel()
        discoveryScope = null
        stopNsdDiscovery()
    }

    private fun startNsdDiscovery() {
        try {
            nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(tag, "NSD Discovery start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Log.e(tag, "NSD Discovery stop failed: $errorCode")
                }

                override fun onDiscoveryStarted(serviceType: String?) {
                    Log.i(tag, "NSD Discovery started for _zeropulselink._tcp")
                }

                override fun onDiscoveryStopped(serviceType: String?) {
                    Log.i(tag, "NSD Discovery stopped")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    serviceInfo?.let { resolveService(it) }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            }
            nsdManager?.discoverServices("_zeropulselink._tcp", NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
        } catch (e: Exception) {
            Log.w(tag, "NSD discoverServices exception: ${e.message}")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                Log.w(tag, "Resolve failed: $errorCode")
            }

            override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                resolvedInfo?.let {
                    val host = it.host?.hostAddress ?: return
                    val beacon = DiscoveryBeacon(
                        serverName = it.serviceName ?: "Zero Pulse Link Server",
                        controlPort = it.port,
                        audioPort = 59101,
                        addresses = listOf(host)
                    )
                    addDiscoveredBeacon(beacon)
                }
            }
        })
    }

    private fun stopNsdDiscovery() {
        try {
            nsdDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            // Ignored
        }
        nsdDiscoveryListener = null
    }

    private fun startUdpBroadcastDiscovery(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    val socket = DatagramSocket()
                    socket.broadcast = true
                    socket.soTimeout = 2000

                    val query = "ZEROPULSELINK_DISCOVER".toByteArray()
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    val packet = DatagramPacket(query, query.size, broadcastAddr, 59100)
                    socket.send(packet)

                    val buffer = ByteArray(2048)
                    val recvPacket = DatagramPacket(buffer, buffer.size)

                    val endTime = System.currentTimeMillis() + 1800
                    while (System.currentTimeMillis() < endTime && isActive) {
                        try {
                            socket.receive(recvPacket)
                            val jsonString = String(recvPacket.data, 0, recvPacket.length)
                            val beacon = DiscoveryBeacon.fromJson(jsonString)
                            val originHost = recvPacket.address.hostAddress ?: ""
                            val mergedAddrs = (beacon.addresses + originHost).distinct().filter { it.isNotBlank() }
                            addDiscoveredBeacon(beacon.copy(addresses = mergedAddrs))
                        } catch (e: Exception) {
                            break
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    Log.d(tag, "UDP discovery error: ${e.message}")
                }
                delay(3000)
            }
        }
    }

    private fun probeAdbUsbLoopback(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                try {
                    // Check if 127.0.0.1:59100 is forwarded via ADB
                    Socket().use { sock ->
                        sock.connect(java.net.InetSocketAddress("127.0.0.1", 59100), 500)
                        val adbBeacon = DiscoveryBeacon(
                            serverName = "USB Wired PC (Direct ADB)",
                            controlPort = 59100,
                            audioPort = 59101,
                            addresses = listOf("127.0.0.1"),
                            adbAvailable = true
                        )
                        addDiscoveredBeacon(adbBeacon)
                    }
                } catch (e: Exception) {
                    // ADB not currently forwarded or cable disconnected
                }
                delay(3000)
            }
        }
    }

    private fun addDiscoveredBeacon(beacon: DiscoveryBeacon) {
        val current = _discoveredServers.value.toMutableList()
        val existingIndex = current.indexOfFirst {
            it.serverName == beacon.serverName || (it.addresses.intersect(beacon.addresses.toSet()).isNotEmpty())
        }
        if (existingIndex >= 0) {
            current[existingIndex] = beacon
        } else {
            current.add(beacon)
        }
        _discoveredServers.value = current
    }
}
