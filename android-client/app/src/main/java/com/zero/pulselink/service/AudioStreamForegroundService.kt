package com.zero.pulselink.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.zero.pulselink.engine.NativeAudioEngine
import com.zero.pulselink.net.BufferProfile
import com.zero.pulselink.net.ControlClient
import com.zero.pulselink.net.StereoRouting
import com.zero.pulselink.net.TcpAudioReceiver
import com.zero.pulselink.net.UdpAudioReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AudioStreamForegroundService : Service() {

    private val tag = "ZeroPulseLinkService"
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var powerLockManager: PowerLockManager
    private lateinit var notificationManager: MediaNotificationManager

    private var controlClient: ControlClient? = null
    private var udpAudioReceiver: UdpAudioReceiver? = null
    private var tcpAudioReceiver: TcpAudioReceiver? = null

    // Session State
    val isStreaming = MutableStateFlow(false)
    val serverName = MutableStateFlow("Zero Pulse Link Server")
    val activeCodec = MutableStateFlow("PCM 16-bit Lossless")
    val latencyMs = MutableStateFlow(0f)
    val packetLossPct = MutableStateFlow(0f)
    val bufferFillMs = MutableStateFlow(25f)
    val underruns = MutableStateFlow(0)
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
        return START_NOT_STICKY
    }

    fun startStreamingSession(host: String, isUsbAdb: Boolean) {
        stopStreamingSession()
        powerLockManager.acquireLocks()

        // Initialize Native Audio Engine (48kHz, 2 channels, default Balanced profile)
        NativeAudioEngine.initEngine(48000, 2, bufferProfile.value.code)
        NativeAudioEngine.startStream()

        // Start Foreground Service with Notification
        val notification = notificationManager.buildNotification(
            serverName.value,
            activeCodec.value,
            latencyMs.value,
            isMuted.value
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                       ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(MediaNotificationManager.NOTIFICATION_ID, notification, type)
        } else {
            startForeground(MediaNotificationManager.NOTIFICATION_ID, notification)
        }

        // Initialize TCP Control Channel
        controlClient = ControlClient(host) { rate, channels, codec ->
            activeCodec.value = codec
            updateNotification()
        }
        controlClient?.connect()

        // Initialize Audio Transport
        if (isUsbAdb || host == "127.0.0.1") {
            // TCP NoDelay tunnel for USB wired mode
            tcpAudioReceiver = TcpAudioReceiver(host = host)
            tcpAudioReceiver?.start()
        } else {
            // UDP with DSCP 0xB8 for Wi-Fi and USB Tethering
            udpAudioReceiver = UdpAudioReceiver()
            udpAudioReceiver?.start()
        }

        isStreaming.value = true
        launchTelemetryTracker()
    }

    fun stopStreamingSession() {
        isStreaming.value = false
        udpAudioReceiver?.stop()
        udpAudioReceiver = null
        tcpAudioReceiver?.stop()
        tcpAudioReceiver = null

        controlClient?.disconnect()
        controlClient = null

        NativeAudioEngine.stopStream()
        NativeAudioEngine.destroyEngine()
        powerLockManager.releaseLocks()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun launchTelemetryTracker() {
        serviceScope.launch {
            while (isActive && isStreaming.value) {
                val tele = NativeAudioEngine.getTelemetry()
                packetLossPct.value = tele[1]
                bufferFillMs.value = tele[2]
                underruns.value = tele[3].toInt()

                controlClient?.let {
                    latencyMs.value = it.rttMs.value
                }

                updateNotification()
                delay(1000)
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
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(MediaNotificationManager.NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        stopStreamingSession()
        serviceScope.cancel()
        super.onDestroy()
    }
}
