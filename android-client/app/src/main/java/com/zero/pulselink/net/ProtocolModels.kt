package com.zero.pulselink.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

val pulseLinkJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

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

@Serializable
data class DiscoveryBeacon(
    @SerialName("server_name") val serverName: String = "",
    @SerialName("server_id") val serverId: String = "",
    @SerialName("os") val os: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("control_port") val controlPort: Int = 59100,
    @SerialName("audio_port") val audioPort: Int = 59101,
    @SerialName("addresses") val addresses: List<String> = emptyList(),
    @SerialName("adb_available") val adbAvailable: Boolean = false
)

@Serializable
data class ControlPacket(
    val type: String,
    val data: kotlinx.serialization.json.JsonObject? = null
)
