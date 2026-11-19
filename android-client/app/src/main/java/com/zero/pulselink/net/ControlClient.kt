package com.zero.pulselink.net

import android.os.Build
import android.util.Log
import com.zero.pulselink.engine.NativeAudioEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

class ControlClient(
    private val hosts: List<String>,
    private val port: Int = 59100,
    private val onHandshakeConfirmed: (
        connectedHost: String,
        serverName: String,
        sampleRate: Int,
        channels: Int,
        codec: String
    ) -> Unit,
    private val onDisconnected: (errorMessage: String?) -> Unit
) {
    private val tag = "ZeroPulseLinkControl"
    private var socket: Socket? = null
    private var writerRef: OutputStreamWriter? = null
    private val writeLock = Any()
    private var scope: CoroutineScope? = null

    val clientId: String = "zero-android-" + UUID.randomUUID().toString().take(8)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _rttMs = MutableStateFlow(0f)
    val rttMs: StateFlow<Float> = _rttMs

    @Volatile
    private var intentionalDisconnect = false

    @Volatile
    private var handshakeDone = false

    fun connect() {
        disconnect()
        intentionalDisconnect = false
        handshakeDone = false

        val s = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = s

        s.launch {
            var connectedHost: String? = null
            var sock: Socket? = null
            var lastError = "Unable to reach PC server"

            // Try each candidate host IP until one establishes a TCP connection
            for (candidate in hosts) {
                if (!isActive) break
                try {
                    Log.i(tag, "Attempting TCP Control connection to $candidate:$port ...")
                    val attempt = Socket()
                    attempt.tcpNoDelay = true
                    attempt.keepAlive = true
                    attempt.connect(InetSocketAddress(candidate, port), 5000)
                    sock = attempt
                    connectedHost = candidate
                    break
                } catch (e: Exception) {
                    lastError = "${candidate}:$port (${e.message ?: "connection refused"})"
                    Log.w(tag, "Failed to reach $lastError")
                }
            }

            if (sock == null || connectedHost == null) {
                _isConnected.value = false
                if (!intentionalDisconnect) {
                    onDisconnected(
                        "Could not connect to PC ($lastError). Check that Zero Pulse Link is running on PC, both devices are on the same Wi-Fi, and Windows Firewall allows TCP 59100."
                    )
                }
                return@launch
            }

            try {
                socket = sock
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val writer = OutputStreamWriter(sock.getOutputStream())
                writerRef = writer

                Log.i(tag, "TCP Control connected to $connectedHost:$port, sending ClientHello...")

                // Send complete ClientHello matching protocol-common ControlMessage::ClientHello
                sendClientHello(writer)

                // Guard: if server doesn't reply with ServerHello within 5s, abort
                val handshakeWatchdog = launch {
                    delay(5000)
                    if (!handshakeDone && isActive) {
                        Log.e(tag, "Handshake timed out waiting for ServerHello from $connectedHost")
                        try {
                            sock.close()
                        } catch (_: Exception) {}
                    }
                }

                // Read server messages loop
                while (isActive) {
                    val line = reader.readLine() ?: break
                    handleServerMessage(line, connectedHost, writer, handshakeWatchdog)
                }

                handshakeWatchdog.cancel()

                if (!intentionalDisconnect) {
                    val msg = if (!handshakeDone) {
                        "PC closed connection before handshake completed. Ensure you are running the latest Zero Pulse Link PC server."
                    } else {
                        "Disconnected from PC server."
                    }
                    onDisconnected(msg)
                }
            } catch (e: Exception) {
                Log.e(tag, "Control connection error: ${e.message}")
                if (!intentionalDisconnect) {
                    val msg = if (!handshakeDone) {
                        "Handshake failed with $connectedHost:$port (${e.message})."
                    } else {
                        "Connection lost (${e.message})."
                    }
                    onDisconnected(msg)
                }
            } finally {
                _isConnected.value = false
                writerRef = null
                try {
                    sock.close()
                } catch (_: Exception) {}
            }
        }
    }

    fun disconnect() {
        intentionalDisconnect = true
        scope?.cancel()
        scope = null
        writerRef = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        _isConnected.value = false
    }

    private fun writeJsonLine(writer: OutputStreamWriter, json: JSONObject) {
        synchronized(writeLock) {
            writer.write(json.toString() + "\n")
            writer.flush()
        }
    }

    private fun sendClientHello(writer: OutputStreamWriter) {
        val helloObj = JSONObject().apply {
            put("type", "client_hello")
            val data = JSONObject().apply {
                put("client_id", clientId)
                put("device_name", Build.MODEL ?: "Android Phone")
                put("model", "${Build.MANUFACTURER} ${Build.PRODUCT}")
                put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                put("version", "1.0.0")
                put("supported_codecs", JSONArray(listOf("pcm16", "pcm24", "opus", "flac")))
                put("supported_rates", JSONArray(listOf(44100, 48000, 96000)))
                put("max_channels", 2)
            }
            put("data", data)
        }
        writeJsonLine(writer, helloObj)
    }

    private fun CoroutineScope.launchTelemetryLoop(writer: OutputStreamWriter) {
        launch {
            var seq = 0
            while (isActive && _isConnected.value) {
                try {
                    // Use monotonic high-precision nanoseconds converted to microseconds
                    val pingTime = System.nanoTime() / 1000L
                    val pingObj = JSONObject().apply {
                        put("type", "ping")
                        val data = JSONObject().apply {
                            put("sequence", seq++)
                            put("timestamp_us", pingTime)
                        }
                        put("data", data)
                    }
                    writeJsonLine(writer, pingObj)

                    val tele = NativeAudioEngine.getTelemetry()
                    val teleObj = JSONObject().apply {
                        put("type", "telemetry")
                        val data = JSONObject().apply {
                            put("sender", "client")
                            put("timestamp_us", pingTime)
                            put("rtt_ms", _rttMs.value)
                            put("packet_loss_pct", if (tele.size > 1) tele[1] else 0f)
                            put("buffer_fill_ms", if (tele.size > 2) tele[2] else 0f)
                            put("underrun_count", if (tele.size > 3) tele[3].toInt() else 0)
                            put("resample_ratio", if (tele.size > 4) tele[4] else 1f)
                            put("bitrate_kbps", 1536f)
                        }
                        put("data", data)
                    }
                    writeJsonLine(writer, teleObj)
                } catch (e: Exception) {
                    Log.w(tag, "Telemetry loop ended: ${e.message}")
                    break
                }
                delay(500)
            }
        }
    }

    private fun handleServerMessage(
        line: String,
        connectedHost: String,
        writer: OutputStreamWriter,
        handshakeWatchdog: Job
    ) {
        try {
            val json = JSONObject(line)
            val type = json.optString("type")
            val data = json.optJSONObject("data") ?: return

            when (type) {
                "server_hello" -> {
                    val serverName = data.optString("server_name", "Zero Pulse Link PC")
                    val rate = data.optInt("sample_rate", 48000)
                    val channels = data.optInt("channels", 2)
                    val codec = data.optString("active_codec", "PCM 16-bit Lossless")

                    if (!handshakeDone) {
                        handshakeDone = true
                        handshakeWatchdog.cancel()
                        _isConnected.value = true
                        Log.i(tag, "Handshake confirmed with $serverName ($rate Hz, $channels ch, $codec)")
                        scope?.launchTelemetryLoop(writer)
                    }

                    onHandshakeConfirmed(connectedHost, serverName, rate, channels, codec)
                }
                "pong" -> {
                    val pingTime = data.optLong("ping_timestamp_us")
                    val now = System.nanoTime() / 1000L
                    val rawRtt = ((now - pingTime) / 1000f).coerceIn(0.1f, 300f)
                    val prev = _rttMs.value
                    // Exponential Moving Average filter (alpha=0.3) to reject OS thread context-switch spikes
                    val smoothed = if (prev <= 0.1f) rawRtt else (prev * 0.7f + rawRtt * 0.3f)
                    _rttMs.value = smoothed
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to parse server message: $line")
        }
    }

    fun updateClientSettings(volume: Float, muted: Boolean, routing: StereoRouting, syncOffsetMs: Int) {
        val writer = writerRef ?: return
        scope?.launch(Dispatchers.IO) {
            try {
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
                writeJsonLine(writer, obj)
            } catch (e: Exception) {
                Log.w(tag, "Failed to send settings update: ${e.message}")
            }
        }
    }
}
