package com.zero.pulselink.net

import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class QrPairingInfo(
    val hosts: List<String>,
    val port: Int = 59100
) {
    companion object {
        /**
         * Parses QR payloads in formats:
         * - zeropulselink://192.168.1.50:59100?ips=192.168.1.50,192.168.42.10
         * - zeropulselink://192.168.1.50:59100
         * - 192.168.1.50:59100
         * - 192.168.1.50
         */
        fun parse(raw: String): QrPairingInfo? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            var body = trimmed
            if (body.startsWith("zeropulselink://", ignoreCase = true)) {
                body = body.substring("zeropulselink://".length)
            } else if (body.startsWith("pulselink://", ignoreCase = true)) {
                body = body.substring("pulselink://".length)
            }

            val querySplit = body.split("?", limit = 2)
            val hostPortPart = querySplit[0].trim().trimEnd('/')
            val queryPart = if (querySplit.size > 1) querySplit[1] else ""

            val parts = hostPortPart.split(":")
            val primaryHost = parts.getOrNull(0)?.trim().orEmpty()
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 59100

            val hosts = mutableListOf<String>()
            if (primaryHost.isNotBlank()) {
                hosts.add(primaryHost)
            }

            if (queryPart.isNotBlank()) {
                queryPart.split("&").forEach { param ->
                    val kv = param.split("=", limit = 2)
                    if (kv.size == 2 && kv[0].equals("ips", ignoreCase = true)) {
                        kv[1].split(",").forEach { ip ->
                            val clean = ip.trim()
                            if (clean.isNotBlank() && !hosts.contains(clean)) {
                                hosts.add(clean)
                            }
                        }
                    }
                }
            }

            return if (hosts.isNotEmpty()) QrPairingInfo(hosts = hosts, port = port) else null
        }
    }
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
