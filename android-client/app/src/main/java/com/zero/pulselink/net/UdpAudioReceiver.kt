package com.zero.pulselink.net

import android.util.Log
import com.zero.pulselink.engine.NativeAudioEngine
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class UdpAudioReceiver(
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
                // DSCP 0xB8 (Expedited Forwarding) for Wi-Fi Multimedia (WMM) Voice Priority
                try {
                    sock.trafficClass = 0xB8
                } catch (e: Exception) {
                    Log.w(tag, "Could not set DSCP 0xB8 traffic class: ${e.message}")
                }

                sock.bind(InetSocketAddress(port))
                socket = sock
                Log.i(tag, "Zero Pulse Link UDP Audio Receiver bound to port $port with DSCP 0xB8")

                val buffer = ByteArray(4096)
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
        } catch (e: Exception) {}
        socket = null
    }
}
