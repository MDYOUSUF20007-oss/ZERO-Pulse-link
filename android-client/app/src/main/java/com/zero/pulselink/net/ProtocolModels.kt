package com.zero.pulselink.net

import org.json.JSONArray
import org.json.JSONObject

enum class BufferProfile(val code: Int, val targetMs: Float) {
    ULTRA_LOW(0, 10f),
    BALANCED(1, 25f),
    HIGH_STABILITY(2, 80f)
}

enum class StereoRouting(val code: Int) {
    STEREO(0),
    LEFT_ONLY(1),
    RIGHT_ONLY(2),
    MONO_DOWNMIX(3)
}

data class DiscoveryBeacon(
    val serverName: String = "",
    val serverId: String = "",
    val os: String = "",
    val version: String = "",
    val controlPort: Int = 59100,
    val audioPort: Int = 59101,
    val addresses: List<String> = emptyList(),
    val adbAvailable: Boolean = false
) {
    companion object {
        fun fromJson(jsonStr: String): DiscoveryBeacon {
            val obj = JSONObject(jsonStr)
            val addrs = mutableListOf<String>()
            val arr = obj.optJSONArray("addresses")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    addrs.add(arr.getString(i))
                }
            }
            return DiscoveryBeacon(
                serverName = obj.optString("server_name", ""),
                serverId = obj.optString("server_id", ""),
                os = obj.optString("os", ""),
                version = obj.optString("version", ""),
                controlPort = obj.optInt("control_port", 59100),
                audioPort = obj.optInt("audio_port", 59101),
                addresses = addrs,
                adbAvailable = obj.optBoolean("adb_available", false)
            )
        }
    }
}
