package com.zero.pulselink.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.zero.pulselink.engine.NativeAudioEngine
import com.zero.pulselink.net.BufferProfile
import com.zero.pulselink.net.ConnectionState
import com.zero.pulselink.net.ControlClient
import com.zero.pulselink.net.StereoRouting
import com.zero.pulselink.net.TcpAudioReceiver
import com.zero.pulselink.net.UdpAudioReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class AudioStreamForegroundService : Service() {

    private val tag = "ZeroPulseLinkService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var powerLockManager: PowerLockManager
    private lateinit var notificationManager: MediaNotificationManager

    private var controlClient: ControlClient? = null
    private var udpAudioReceiver: UdpAudioReceiver? = null
    private var tcpAudioReceiver: TcpAudioReceiver? = null
    private var telemetryJob: Job? = null

    // Session State
    val connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionError = MutableStateFlow<String?>(null)
    val isStreaming = MutableStateFlow(false)
    val serverName = MutableStateFlow("Zero Pulse Link Server")
    val activeCodec = MutableStateFlow("PCM 16-bit Lossless")
    val sampleRateHz = MutableStateFlow(48000)
    val latencyMs = MutableStateFlow(0f)
    val packetLossPct = MutableStateFlow(0f)
    val bufferFillMs = MutableStateFlow(25f)
    val underruns = MutableStateFlow(0)
    val vuLeft = MutableStateFlow(0f)
    val vuRight = MutableStateFlow(0f)
    val volumeLevel = MutableStateFlow(1.0f) // 0.0 to 2.0 (+15dB boost)
    val isMuted = MutableStateFlow(false)
    val stereoRouting = MutableStateFlow(StereoRouting.STEREO)
    val bufferProfile = MutableStateFlow(BufferProfile.BALANCED)
    val syncOffsetMs = MutableStateFlow(0)

    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamForegroundService = this@AudioStreamForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        powerLockManager = PowerLockManager(this)
        notificationManager = MediaNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MediaNotificationManager.ACTION_DISCONNECT -> {
                stopStreamingSession()
            }
            MediaNotificationManager.ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
        }
        return Service.START_NOT_STICKY
    }

    fun startStreamingSession(host: String, isUsbAdb: Boolean, controlPort: Int = 59100) {
        startStreamingSession(listOf(host), isUsbAdb, controlPort)
    }

    fun startStreamingSession(hosts: List<String>, isUsbAdb: Boolean, controlPort: Int = 59100) {
        teardownSession(clearError = true)
        connectionState.value = ConnectionState.CONNECTING

        val client = ControlClient(
            hosts = hosts,
            port = controlPort,
            onHandshakeConfirmed = { connectedHost, remoteName, rate, channels, codec ->
                serviceScope.launch(Dispatchers.Main) {
                    if (isStreaming.value) {
                        // Already running; just update metadata
                        serverName.value = remoteName
                        activeCodec.value = codec
                        updateNotification()
                        return@launch
                    }

                    serverName.value = remoteName
                    activeCodec.value = codec
                    sampleRateHz.value = rate

                    powerLockManager.acquireLocks()

                    // Initialize Native AAudio Engine with exact negotiated sample rate & channels
                    NativeAudioEngine.initEngine(rate, channels, bufferProfile.value.code)
                    NativeAudioEngine.setVolume(if (isMuted.value) 0f else volumeLevel.value)
                    NativeAudioEngine.setStereoRouting(stereoRouting.value.code)
                    NativeAudioEngine.startStream()

                    // Start Foreground Service safely across Android versions (API 26–34)
                    val notification = notificationManager.buildNotification(
                        serverName.value,
                        activeCodec.value,
                        latencyMs.value,
                        isMuted.value
                    )
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val hasMicPerm = ContextCompat.checkSelfPermission(
                                this@AudioStreamForegroundService,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            val type = if (hasMicPerm) {
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            } else {
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                            }
                            startForeground(MediaNotificationManager.NOTIFICATION_ID, notification, type)
                        } else {
                            startForeground(MediaNotificationManager.NOTIFICATION_ID, notification)
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "startForeground warning: ${e.message}")
                    }

                    // Start Audio Transport AFTER PC confirmed handshake
                    if (isUsbAdb || connectedHost == "127.0.0.1") {
                        tcpAudioReceiver = TcpAudioReceiver(host = connectedHost)
                        tcpAudioReceiver?.start()
                    } else {
                        val cid = controlClient?.clientId ?: "zero-android"
                        udpAudioReceiver = UdpAudioReceiver(serverHost = connectedHost, clientId = cid)
                        udpAudioReceiver?.start()
                    }

                    connectionError.value = null
                    connectionState.value = ConnectionState.CONNECTED
                    isStreaming.value = true
                    launchTelemetryTracker()
                }
            },
            onDisconnected = { errorMsg ->
                serviceScope.launch(Dispatchers.Main) {
                    teardownSession(clearError = false)
                    if (errorMsg != null) {
                        connectionError.value = errorMsg
                        connectionState.value = ConnectionState.ERROR
                    } else {
                        connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        )

        controlClient = client
        client.connect()
    }

    fun clearError() {
        connectionError.value = null
        if (connectionState.value == ConnectionState.ERROR) {
            connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun stopStreamingSession() {
        teardownSession(clearError = true)
        connectionState.value = ConnectionState.DISCONNECTED
    }

    private fun teardownSession(clearError: Boolean) {
        isStreaming.value = false
        vuLeft.value = 0f
        vuRight.value = 0f

        telemetryJob?.cancel()
        telemetryJob = null

        udpAudioReceiver?.stop()
        udpAudioReceiver = null
        tcpAudioReceiver?.stop()
        tcpAudioReceiver = null

        controlClient?.disconnect()
        controlClient = null

        NativeAudioEngine.stopStream()
        NativeAudioEngine.destroyEngine()
        powerLockManager.releaseLocks()

        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}

        if (clearError) {
            connectionError.value = null
        }
    }

    private fun launchTelemetryTracker() {
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch {
            var tick = 0
            while (isActive && isStreaming.value) {
                val tele = NativeAudioEngine.getTelemetry()
                if (tele.size >= 7) {
                    vuLeft.value = tele[5]
                    vuRight.value = tele[6]
                }

                // Update telemetry cards & notification every ~500ms (10 ticks x 50ms)
                if (tick % 10 == 0) {
                    if (tele.size >= 4) {
                        packetLossPct.value = tele[1]
                        bufferFillMs.value = tele[2]
                        underruns.value = tele[3].toInt()
                    }
                    controlClient?.let {
                        latencyMs.value = it.rttMs.value
                    }
                    if (tick % 20 == 0) {
                        updateNotification()
                    }
                }
                tick++
                delay(50)
            }
        }
    }

    fun setVolume(vol: Float) {
        volumeLevel.value = vol
        NativeAudioEngine.setVolume(if (isMuted.value) 0f else vol)
        controlClient?.updateClientSettings(vol, isMuted.value, stereoRouting.value, syncOffsetMs.value)
    }

    fun toggleMute() {
        val nextMute = !isMuted.value
        isMuted.value = nextMute
        NativeAudioEngine.setVolume(if (nextMute) 0f else volumeLevel.value)
        controlClient?.updateClientSettings(volumeLevel.value, nextMute, stereoRouting.value, syncOffsetMs.value)
        updateNotification()
    }

    fun setStereoRouting(routing: StereoRouting) {
        stereoRouting.value = routing
        NativeAudioEngine.setStereoRouting(routing.code)
        controlClient?.updateClientSettings(volumeLevel.value, isMuted.value, routing, syncOffsetMs.value)
    }

    fun setBufferProfile(profile: BufferProfile) {
        bufferProfile.value = profile
        NativeAudioEngine.setBufferProfile(profile.code)
    }

    private fun updateNotification() {
        if (isStreaming.value) {
            val notification = notificationManager.buildNotification(
                serverName.value,
                activeCodec.value,
                latencyMs.value,
                isMuted.value
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(MediaNotificationManager.NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        teardownSession(clearError = true)
        serviceScope.cancel()
        super.onDestroy()
    }
}
