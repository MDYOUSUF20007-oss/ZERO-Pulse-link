package com.zero.pulselink.net

import android.util.Log
import com.zero.pulselink.engine.NativeAudioEngine
import kotlinx.coroutines.*
import java.io.BufferedInputStream
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
                sock.receiveBufferSize = 65536
                sock.connect(InetSocketAddress(host, port), 3000)
                socket = sock
                Log.i(tag, "Zero Pulse Link TCP-NoDelay Audio Tunnel connected to $host:$port")

                val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 16384))
                val buffer = ByteArray(8192)
                val lenBuf = ByteArray(4)

                while (isActive) {
                    input.readFully(lenBuf, 0, 4)
                    val packetLen = (lenBuf[0].toInt() and 0xFF) or
                            ((lenBuf[1].toInt() and 0xFF) shl 8) or
                            ((lenBuf[2].toInt() and 0xFF) shl 16) or
                            ((lenBuf[3].toInt() and 0xFF) shl 24)

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
