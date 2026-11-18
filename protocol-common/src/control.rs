//! Control protocol messages exchanged over TCP port 59100.
//!
//! Uses line-delimited JSON (NDJSON) framing for reliable, streaming serialization.

use serde::{Deserialize, Serialize};

fn default_max_channels() -> u8 {
    2
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum StereoRouting {
    Stereo,
    LeftOnly,
    RightOnly,
    MonoDownmix,
}

impl Default for StereoRouting {
    fn default() -> Self {
        StereoRouting::Stereo
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum BufferProfile {
    UltraLowLatency, // 5ms - 15ms target
    Balanced,        // 20ms - 40ms target
    HighStability,   // 50ms - 150ms target
}

impl Default for BufferProfile {
    fn default() -> Self {
        BufferProfile::Balanced
    }
}

impl BufferProfile {
    pub fn target_ms(&self) -> f32 {
        match self {
            BufferProfile::UltraLowLatency => 10.0,
            BufferProfile::Balanced => 25.0,
            BufferProfile::HighStability => 80.0,
        }
    }

    pub fn min_ms(&self) -> f32 {
        match self {
            BufferProfile::UltraLowLatency => 5.0,
            BufferProfile::Balanced => 15.0,
            BufferProfile::HighStability => 40.0,
        }
    }

    pub fn max_ms(&self) -> f32 {
        match self {
            BufferProfile::UltraLowLatency => 20.0,
            BufferProfile::Balanced => 45.0,
            BufferProfile::HighStability => 160.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "data", rename_all = "snake_case")]
pub enum ControlMessage {
    /// Client sends initial introduction to Server
    ClientHello {
        client_id: String,
        device_name: String,
        model: String,
        os: String,
        version: String,
        #[serde(default)]
        supported_codecs: Vec<String>,
        #[serde(default)]
        supported_rates: Vec<u32>,
        #[serde(default = "default_max_channels")]
        max_channels: u8,
    },

    /// Server replies with its configuration and active state
    ServerHello {
        server_name: String,
        server_id: String,
        version: String,
        os: String,
        control_port: u16,
        audio_port: u16,
        active_codec: String,
        sample_rate: u32,
        channels: u8,
        frame_size_ms: f32,
        ip_addresses: Vec<String>,
    },

    /// Negotiate / Reconfigure audio stream parameters
    ConfigureStream {
        codec: String,
        sample_rate: u32,
        channels: u8,
        frame_size_ms: f32,
        direction: String, // "pc_to_phone" or "phone_to_pc"
        buffer_profile: BufferProfile,
    },

    /// Command to start streaming
    StartStream {
        stream_id: String,
        transport: String, // "udp" or "tcp_nodelay"
    },

    /// Command to stop streaming
    StopStream {
        stream_id: String,
        reason: String,
    },

    /// Heartbeat ping from either side
    Ping {
        sequence: u32,
        timestamp_us: u64,
    },

    /// Heartbeat pong response
    Pong {
        sequence: u32,
        ping_timestamp_us: u64,
        pong_timestamp_us: u64,
    },

    /// Telemetry exchanged every 1000ms
    Telemetry {
        sender: String, // "client" or "server"
        timestamp_us: u64,
        rtt_ms: f32,
        packet_loss_pct: f32,
        buffer_fill_ms: f32,
        underrun_count: u32,
        resample_ratio: f32,
        bitrate_kbps: f32,
    },

    /// Client per-device configuration update
    ClientSettings {
        client_id: String,
        volume: f32, // 0.0 to 2.0 (boost)
        muted: bool,
        stereo_routing: StereoRouting,
        sync_offset_ms: i32, // -200ms to +200ms
    },

    /// Server control command
    ServerCommand {
        mute_pc_speakers: Option<bool>,
        source_device_name: Option<String>,
        target_bitrate_kbps: Option<u32>,
    },

    /// Error message
    Error {
        code: u32,
        message: String,
    },
}

impl ControlMessage {
    /// Serialize message to line-delimited JSON
    pub fn to_line(&self) -> Result<String, serde_json::Error> {
        let mut s = serde_json::to_string(self)?;
        s.push('\n');
        Ok(s)
    }

    /// Parse message from line
    pub fn from_line(line: &str) -> Result<Self, serde_json::Error> {
        serde_json::from_str(line.trim())
    }
}

/// Broadcast discovery response payload (UDP 59100)
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DiscoveryBeacon {
    pub server_name: String,
    pub server_id: String,
    pub os: String,
    pub version: String,
    pub control_port: u16,
    pub audio_port: u16,
    pub addresses: Vec<String>,
    pub adb_available: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_control_message_roundtrip() {
        let msg = ControlMessage::ClientHello {
            client_id: "phone-abc-123".into(),
            device_name: "Pixel 8 Pro".into(),
            model: "Google Pixel 8".into(),
            os: "Android 14".into(),
            version: "1.0.0".into(),
            supported_codecs: vec!["pcm16".into(), "opus".into()],
            supported_rates: vec![44100, 48000, 96000],
            max_channels: 2,
        };

        let line = msg.to_line().unwrap();
        assert!(line.ends_with('\n'));
        let parsed = ControlMessage::from_line(&line).unwrap();
        assert_eq!(msg, parsed);
    }
}
