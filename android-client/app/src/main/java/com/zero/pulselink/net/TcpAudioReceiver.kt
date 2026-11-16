package com.zero.pulselink.net

import android.util.Log
import com.zero.pulselink.engine.NativeAudioEngine
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

class TcpAudioReceiver(
    private val host: String = "127.0.0.1",
    private val port: Int = 59101
) {
    private val tag = "ZeroPulseLinkTcpAudio"
    private var socket: Socket? = null
    private var scope: CoroutineScope? = null

    fun start() {
        stop()
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s

        s.launch {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), 3000)
                socket = sock
                Log.i(tag, "Zero Pulse Link TCP-NoDelay Audio Tunnel connected to $host:$port")

                val input = DataInputStream(sock.getInputStream())
                val buffer = ByteArray(8192)

                while (isActive) {
                    // 4 bytes packet length (Little Endian)
                    val b0 = input.readUnsignedByte()
                    val b1 = input.readUnsignedByte()
                    val b2 = input.readUnsignedByte()
                    val b3 = input.readUnsignedByte()
                    val packetLen = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)

                    if (packetLen in 16..buffer.size) {
                        input.readFully(buffer, 0, packetLen)
                        NativeAudioEngine.pushAudioPacket(buffer, packetLen)
                    } else if (packetLen > buffer.size) {
                        // Overflow protection: skip invalid packet
                        input.skipBytes(packetLen)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(tag, "TCP Audio Receiver error: ${e.message}")
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
