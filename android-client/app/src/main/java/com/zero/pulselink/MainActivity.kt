package com.zero.pulselink

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.zero.pulselink.net.DiscoveryBeacon
import com.zero.pulselink.net.DiscoveryClient
import com.zero.pulselink.service.AudioStreamForegroundService
import com.zero.pulselink.ui.screens.DashboardScreen
import com.zero.pulselink.ui.screens.DeviceDiscoveryScreen
import com.zero.pulselink.ui.theme.ZeroPulseLinkTheme

class MainActivity : ComponentActivity() {

    private var audioService: AudioStreamForegroundService? = null
    private var isBound by mutableStateOf(false)
    private lateinit var discoveryClient: DiscoveryClient

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AudioStreamForegroundService.LocalBinder
            audioService = localBinder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discoveryClient = DiscoveryClient(this)

        requestPermissions()
        bindAudioService()

        setContent {
            ZeroPulseLinkTheme {
                val service = audioService
                val isStreaming = service?.isStreaming?.collectAsState()?.value ?: false
                val discoveredServers by discoveryClient.discoveredServers.collectAsState()

                if (isStreaming && service != null) {
                    DashboardScreen(
                        service = service,
                        onDisconnect = {
                            discoveryClient.startDiscovery()
                        }
                    )
                } else {
                    DeviceDiscoveryScreen(
                        discoveredServers = discoveredServers,
                        onConnect = { host, isAdb ->
                            service?.startStreamingSession(host, isAdb)
                        }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun bindAudioService() {
        val intent = Intent(this, AudioStreamForegroundService::class.java)
        startService(intent) // Ensure service lifecycle stays alive
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (audioService?.isStreaming?.value != true) {
            discoveryClient.startDiscovery()
        }
    }

    override fun onPause() {
        super.onPause()
        discoveryClient.stopDiscovery()
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        super.onDestroy()
    }
}
