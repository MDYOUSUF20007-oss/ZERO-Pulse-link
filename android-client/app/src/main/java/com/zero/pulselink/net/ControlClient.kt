package com.zero.pulselink.net

import android.os.Build
import android.util.Log
import com.zero.pulselink.engine.NativeAudioEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class ControlClient(
    private val host: String,
    private val port: Int = 59100,
    private val onStreamConfigReceived: (sampleRate: Int, channels: Int, codec: String) -> Unit
) {
    private val tag = "ZeroPulseLinkControl"
    private var socket: Socket? = null
    private var scope: CoroutineScope? = null
    private val clientId = "zero-android-" + UUID.randomUUID().toString().take(8)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _rttMs = MutableStateFlow(0f)
    val rttMs: StateFlow<Float> = _rttMs

    fun connect() {
        disconnect()
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s

        s.launch {
            try {
                val sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(host, port), 4000)
                socket = sock
                _isConnected.value = true
                Log.i(tag, "Connected to Control Server at $host:$port")

                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val writer = OutputStreamWriter(sock.getOutputStream())

                // Send ClientHello
                sendClientHello(writer)

                // Launch Heartbeat & Telemetry reporting job
                launchTelemetryLoop(writer)

                // Read server messages loop
                while (isActive) {
                    val line = reader.readLine() ?: break
                    handleServerMessage(line)
                }
            } catch (e: Exception) {
                Log.e(tag, "Control connection failed: ${e.message}")
            } finally {
                _isConnected.value = false
            }
        }
    }

    fun disconnect() {
        scope?.cancel()
        scope = null
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        _isConnected.value = false
    }

    private fun sendClientHello(writer: OutputStreamWriter) {
        val helloObj = JSONObject().apply {
            put("type", "client_hello")
            val data = JSONObject().apply {
                put("client_id", clientId)
                put("device_name", Build.MODEL)
                put("model", "${Build.MANUFACTURER} ${Build.PRODUCT}")
                put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("version", "1.0.0")
                put("max_channels", 2)
            }
            put("data", data)
        }
        writer.write(helloObj.toString() + "\n")
        writer.flush()
    }

    private fun CoroutineScope.launchTelemetryLoop(writer: OutputStreamWriter) {
        launch {
            var seq = 0
            while (isActive) {
                try {
                    // Send Ping
                    val pingTime = System.currentTimeMillis() * 1000
                    val pingObj = JSONObject().apply {
                        put("type", "ping")
                        val data = JSONObject().apply {
                            put("sequence", seq++)
                            put("timestamp_us", pingTime)
                        }
                        put("data", data)
                    }
                    writer.write(pingObj.toString() + "\n")

                    // Send live audio telemetry
                    val tele = NativeAudioEngine.getTelemetry()
                    val teleObj = JSONObject().apply {
                        put("type", "telemetry")
                        val data = JSONObject().apply {
                            put("sender", "client")
                            put("timestamp_us", pingTime)
                            put("rtt_ms", _rttMs.value)
                            put("packet_loss_pct", tele[1])
                            put("buffer_fill_ms", tele[2])
                            put("underrun_count", tele[3].toInt())
                            put("resample_ratio", tele[4])
                            put("bitrate_kbps", 1536f)
                        }
                        put("data", data)
                    }
                    writer.write(teleObj.toString() + "\n")
                    writer.flush()
                } catch (e: Exception) {
                    break
                }
                delay(1000)
            }
        }
    }

    private fun handleServerMessage(line: String) {
        try {
            val json = JSONObject(line)
            val type = json.optString("type")
            val data = json.optJSONObject("data") ?: return

            when (type) {
                "server_hello" -> {
                    val rate = data.optInt("sample_rate", 48000)
                    val channels = data.optInt("channels", 2)
                    val codec = data.optString("active_codec", "PCM 16-bit Lossless")
                    onStreamConfigReceived(rate, channels, codec)
                }
                "pong" -> {
                    val pingTime = data.optLong("ping_timestamp_us")
                    val now = System.currentTimeMillis() * 1000
                    val rtt = ((now - pingTime) / 1000f).coerceAtLeast(0f)
                    _rttMs.value = rtt
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse message: $line")
        }
    }

    fun updateClientSettings(volume: Float, muted: Boolean, routing: StereoRouting, syncOffsetMs: Int) {
        scope?.launch(Dispatchers.IO) {
            try {
                val sock = socket ?: return@launch
                val writer = OutputStreamWriter(sock.getOutputStream())
                val obj = JSONObject().apply {
                    put("type", "client_settings")
                    val data = JSONObject().apply {
                        put("client_id", clientId)
                        put("volume", volume)
                        put("muted", muted)
                        put("stereo_routing", routing.name.lowercase())
                        put("sync_offset_ms", syncOffsetMs)
                    }
                    put("data", data)
                }
                writer.write(obj.toString() + "\n")
                writer.flush()
            } catch (e: Exception) {
                Log.w(tag, "Failed to send settings update: ${e.message}")
            }
        }
    }
}
