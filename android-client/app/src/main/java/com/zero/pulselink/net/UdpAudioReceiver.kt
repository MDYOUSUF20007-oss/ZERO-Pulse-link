package com.zero.pulselink.net

import android.util.Log
import com.zero.pulselink.engine.NativeAudioEngine
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class UdpAudioReceiver(
    private val serverHost: String,
    private val clientId: String,
    private val port: Int = 59101
) {
    private val tag = "ZeroPulseLinkUdpReceiver"
    private var socket: DatagramSocket? = null
    private var scope: CoroutineScope? = null

    fun start() {
        stop()
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s

        s.launch {
            try {
                val sock = DatagramSocket(null)
                sock.reuseAddress = true
                try {
                    sock.receiveBufferSize = 262144
                } catch (_: Exception) {}

                // DSCP 0xB8 (Expedited Forwarding) for Wi-Fi Multimedia (WMM) Voice Priority
                try {
                    sock.trafficClass = 0xB8
                } catch (e: Exception) {
                    Log.w(tag, "Could not set DSCP 0xB8 traffic class: ${e.message}")
                }

                try {
                    sock.bind(InetSocketAddress(port))
                } catch (e: Exception) {
                    Log.w(tag, "Port $port busy, binding to ephemeral port for UDP punch: ${e.message}")
                    sock.bind(InetSocketAddress(0))
                }

                socket = sock
                Log.i(tag, "Zero Pulse Link UDP Audio Receiver bound to local port ${sock.localPort}")

                // Periodically send UDP NAT-punch / registration packet to PC server:59101
                val serverAddr = InetAddress.getByName(serverHost)
                val punchBytes = "ZP_UDP_HELLO:$clientId".toByteArray()
                launch {
                    while (isActive) {
                        try {
                            val punchPacket = DatagramPacket(punchBytes, punchBytes.size, serverAddr, port)
                            sock.send(punchPacket)
                        } catch (e: Exception) {
                            Log.w(tag, "UDP punch send error: ${e.message}")
                        }
                        delay(3000)
                    }
                }

                val buffer = ByteArray(8192)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    sock.receive(packet)
                    val len = packet.length
                    if (len >= 16) {
                        NativeAudioEngine.pushAudioPacket(buffer, len)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(tag, "UDP Audio Receiver loop error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}
