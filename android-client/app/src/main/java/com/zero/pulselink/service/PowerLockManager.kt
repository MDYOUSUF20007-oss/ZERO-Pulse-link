package com.zero.pulselink.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

class PowerLockManager(private val context: Context) {

    private val tag = "ZeroPulseLinkPowerLock"
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquireLocks() {
        // 1. Partial WakeLock so CPU never throttles when screen is off
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ZeroPulseLink:AudioStreamWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12 hours max
            }
            Log.i(tag, "Acquired PARTIAL_WAKE_LOCK")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire wake lock: ${e.message}")
        }

        // 2. High-performance Low-Latency Wi-Fi lock
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "ZeroPulseLink:LowLatencyWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(tag, "Acquired WIFI_MODE_FULL_LOW_LATENCY lock")
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire wifi lock: ${e.message}")
        }
    }

    fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            Log.i(tag, "Released PARTIAL_WAKE_LOCK")
        } catch (e: Exception) {}

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
            Log.i(tag, "Released WifiLock")
        } catch (e: Exception) {}
    }
}
