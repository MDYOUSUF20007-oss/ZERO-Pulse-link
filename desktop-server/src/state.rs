//! Global server state, multi-client registry, stereo routing, and telemetry tracking.

use protocol_common::{
    AudioCodec, BufferProfile, ChannelCount, SampleRate, StereoRouting,
};
use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::Arc;
use std::time::Instant;
use tokio::sync::RwLock;

/// Transport medium used by a connected client
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClientTransportType {
    UdpWifi,
    UdpTethering,
    TcpAdb,
}

impl ClientTransportType {
    pub fn label(&self) -> &'static str {
        match self {
            ClientTransportType::UdpWifi => "Wi-Fi (UDP)",
            ClientTransportType::UdpTethering => "USB Tether (UDP)",
            ClientTransportType::TcpAdb => "USB ADB (TCP NoDelay)",
        }
    }
}

/// Dynamic Delay Line for multi-device sync offset (-200ms to +200ms)
#[derive(Debug, Clone)]
pub struct DelayLine {
    buffer: Vec<i16>,
    write_pos: usize,
    capacity: usize,
}

impl DelayLine {
    pub fn new(max_delay_ms: usize, sample_rate: u32, channels: usize) -> Self {
        let max_samples = (max_delay_ms * sample_rate as usize * channels) / 1000;
        let capacity = max_samples.next_power_of_two().max(1024);
        Self {
            buffer: vec![0; capacity],
            write_pos: 0,
            capacity,
        }
    }

    /// Process interleaved audio frame through delay line.
    /// `offset_ms`: positive values delay the output, negative values (or 0) pass through immediately.
    pub fn process(
        &mut self,
        input: &[i16],
        output: &mut [i16],
        offset_ms: i32,
        sample_rate: u32,
        channels: usize,
    ) {
        if offset_ms <= 0 {
            output.copy_from_slice(input);
            return;
        }

        let delay_samples = ((offset_ms as usize * sample_rate as usize * channels) / 1000)
            .min(self.capacity - input.len() - 1);

        for &sample in input {
            self.buffer[self.write_pos] = sample;
            self.write_pos = (self.write_pos + 1) % self.capacity;
        }

        let mut read_idx = (self.write_pos + self.capacity - delay_samples) % self.capacity;
        for out in output.iter_mut() {
            *out = self.buffer[read_idx];
            read_idx = (read_idx + 1) % self.capacity;
        }
    }
}

/// Represents an individual connected client session
#[derive(Debug, Clone)]
pub struct ConnectedClient {
    pub id: String,
    pub device_name: String,
    pub model: String,
    pub os: String,
    pub version: String,
    pub control_addr: SocketAddr,
    pub audio_addr: Option<SocketAddr>,
    pub transport_type: ClientTransportType,
    pub connected_at: Instant,
    pub last_seen: Instant,
    pub volume: f32, // 0.0 to 2.0 (boost)
    pub muted: bool,
    pub stereo_routing: StereoRouting,
    pub sync_offset_ms: i32, // -200ms to +200ms
    pub rtt_ms: f32,
    pub packet_loss_pct: f32,
    pub buffer_fill_ms: f32,
    pub underrun_count: u32,
    pub bitrate_kbps: f32,
    pub delay_line: DelayLine,
}

impl ConnectedClient {
    pub fn new(
        id: String,
        device_name: String,
        model: String,
        os: String,
        version: String,
        control_addr: SocketAddr,
        transport_type: ClientTransportType,
        sample_rate: u32,
    ) -> Self {
        Self {
            id,
            device_name,
            model,
            os,
            version,
            control_addr,
            audio_addr: None,
            transport_type,
            connected_at: Instant::now(),
            last_seen: Instant::now(),
            volume: 1.0,
            muted: false,
            stereo_routing: StereoRouting::Stereo,
            sync_offset_ms: 0,
            rtt_ms: 0.0,
            packet_loss_pct: 0.0,
            buffer_fill_ms: 25.0,
            underrun_count: 0,
            bitrate_kbps: 0.0,
            delay_line: DelayLine::new(300, sample_rate, 2),
        }
    }
}

/// Global configuration and statistics for PulseLink server
#[derive(Debug)]
pub struct ServerConfig {
    pub server_name: String,
    pub active_codec: AudioCodec,
    pub sample_rate: SampleRate,
    pub channels: ChannelCount,
    pub frame_size_ms: f32,
    pub buffer_profile: BufferProfile,
    pub mute_pc_speakers_on_connect: bool,
    pub selected_audio_device: Option<String>,
    pub bidirectional_mic_enabled: bool,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            server_name: "Zero Pulse Link PC Server".into(),
            active_codec: AudioCodec::Pcm16,
            sample_rate: SampleRate::Rate48000,
            channels: ChannelCount::Stereo,
            frame_size_ms: 5.0, // 5ms low-latency default frame
            buffer_profile: BufferProfile::Balanced,
            mute_pc_speakers_on_connect: false,
            selected_audio_device: None,
            bidirectional_mic_enabled: false,
        }
    }
}

/// Shared application state
pub struct AppState {
    pub config: RwLock<ServerConfig>,
    pub clients: RwLock<HashMap<String, ConnectedClient>>,
    pub is_streaming: AtomicBool,
    pub sequence_counter: AtomicU32,
    pub vu_left_peak: AtomicU32,  // Stored as normalized float bits
    pub vu_right_peak: AtomicU32, // Stored as normalized float bits
    pub adb_device_connected: AtomicBool,
    pub adb_forwarded: AtomicBool,
}

impl AppState {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            config: RwLock::new(ServerConfig::default()),
            clients: RwLock::new(HashMap::new()),
            is_streaming: AtomicBool::new(false),
            sequence_counter: AtomicU32::new(0),
            vu_left_peak: AtomicU32::new(0),
            vu_right_peak: AtomicU32::new(0),
            adb_device_connected: AtomicBool::new(false),
            adb_forwarded: AtomicBool::new(false),
        })
    }

    pub fn next_sequence(&self) -> u32 {
        self.sequence_counter.fetch_add(1, Ordering::Relaxed)
    }

    pub fn update_vu(&self, left_peak: f32, right_peak: f32) {
        self.vu_left_peak
            .store(left_peak.to_bits(), Ordering::Relaxed);
        self.vu_right_peak
            .store(right_peak.to_bits(), Ordering::Relaxed);
    }

    pub fn get_vu(&self) -> (f32, f32) {
        let left = f32::from_bits(self.vu_left_peak.load(Ordering::Relaxed));
        let right = f32::from_bits(self.vu_right_peak.load(Ordering::Relaxed));
        (left, right)
    }

    pub async fn client_count(&self) -> usize {
        self.clients.read().await.len()
    }

    /// Calculate active transmission bitrate in Mbps
    pub async fn calculate_bitrate_mbps(&self) -> f32 {
        let config = self.config.read().await;
        let bytes_per_sample = match config.active_codec {
            AudioCodec::Pcm16 => 2,
            AudioCodec::Pcm24 => 3,
            AudioCodec::Opus => 1, // Avg ~128kbps = 0.128 Mbps
            AudioCodec::Flac => 2, // Avg ~0.6-0.8 Mbps
        };

        if config.active_codec == AudioCodec::Opus {
            return 0.160; // 160 kbps Opus
        } else if config.active_codec == AudioCodec::Flac {
            let uncompressed = (config.sample_rate.hz() * config.channels.as_u8() as u32 * 2 * 8) as f32 / 1_000_000.0;
            return uncompressed * 0.55; // FLAC achieves ~45% compression
        }

        let bps = config.sample_rate.hz() * config.channels.as_u8() as u32 * bytes_per_sample * 8;
        bps as f32 / 1_000_000.0
    }
}
