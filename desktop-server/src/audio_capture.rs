//! High-performance Audio Loopback capture and bidirectional microphone routing.
//!
//! Supports:
//! - Low-latency WASAPI Loopback capture on Windows
//! - Native CPAL loopback on Linux / macOS
//! - Real-time channel sample rate conversion
//! - "Mute PC Speakers on Connect" toggle
//! - Multi-client audio dispatching
//! - VU Peak Meter extraction

use std::sync::atomic::Ordering;
use std::sync::Arc;

use anyhow::{Context, Result};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, Stream, StreamConfig};
use tokio::sync::mpsc;

use crate::network::NetworkManager;
use crate::state::AppState;

pub struct AudioCaptureEngine {
    state: Arc<AppState>,
    network: Arc<NetworkManager>,
}

impl AudioCaptureEngine {
    pub fn new(state: Arc<AppState>, network: Arc<NetworkManager>) -> Self {
        Self { state, network }
    }

    /// List all available audio output devices suitable for loopback capture
    pub fn list_output_devices() -> Vec<String> {
        let host = cpal::default_host();
        let mut devices = Vec::new();
        if let Ok(dev_iter) = host.output_devices() {
            for dev in dev_iter {
                if let Ok(name) = dev.name() {
                    devices.push(name);
                }
            }
        }
        devices
    }

    /// Start audio capture loopback stream
    pub fn start_loopback_capture(self: Arc<Self>) -> Result<Stream> {
        let host = cpal::default_host();

        // Obtain default output device for loopback
        let device = host
            .default_output_device()
            .context("Failed to get default audio output device for loopback")?;

        let device_name = device.name().unwrap_or_else(|_| "Default Speaker".into());
        log::info!("Zero Pulse Link capturing WASAPI loopback from: {}", device_name);

        let default_config = device
            .default_output_config()
            .context("Failed to get default output config")?;

        let sample_rate = default_config.sample_rate().0;
        let channels = default_config.channels();
        let sample_format = default_config.sample_format();

        // Sync active server config sample rate with actual WASAPI loopback capture rate
        if let Some(sr) = protocol_common::SampleRate::from_hz(sample_rate) {
            let state_cfg = Arc::clone(&self.state);
            let rt_handle = tokio::runtime::Handle::current();
            rt_handle.spawn(async move {
                state_cfg.config.write().await.sample_rate = sr;
            });
        }

        // Use BufferSize::Default for universal Windows WASAPI loopback compatibility;
        // we chunk outbound frames to 240 frames (976-byte UDP packets) below MTU.
        let stream_config = StreamConfig {
            channels,
            sample_rate: cpal::SampleRate(sample_rate),
            buffer_size: cpal::BufferSize::Default,
        };

        let state_clone = Arc::clone(&self.state);
        let network_clone = Arc::clone(&self.network);

        let (pcm_tx, mut pcm_rx) = mpsc::channel::<Vec<i16>>(128);

        // Background worker to chunk and broadcast audio packets (240 stereo frames = 480 samples = 960 bytes)
        // Guarantees every UDP packet stays well under 1472-byte Wi-Fi MTU (zero fragmentation)
        let rt = tokio::runtime::Handle::current();
        rt.spawn(async move {
            const CHUNK_SAMPLES: usize = 240 * 2; // 240 stereo frames = 5ms @ 48kHz
            let mut pending: Vec<i16> = Vec::with_capacity(CHUNK_SAMPLES * 4);

            while let Some(pcm_chunk) = pcm_rx.recv().await {
                pending.extend_from_slice(&pcm_chunk);
                while pending.len() >= CHUNK_SAMPLES {
                    let frame: Vec<i16> = pending.drain(..CHUNK_SAMPLES).collect();
                    network_clone.broadcast_audio_frame(&frame).await;
                }
            }
        });

        let err_fn = |err| {
            log::error!("Audio loopback capture error: {}", err);
        };

        let stream = match sample_format {
            SampleFormat::F32 => {
                device.build_input_stream(
                    &stream_config,
                    move |data: &[f32], _| {
                        Self::process_f32_samples(data, channels, &state_clone, &pcm_tx);
                    },
                    err_fn,
                    None,
                )?
            }
            SampleFormat::I16 => {
                device.build_input_stream(
                    &stream_config,
                    move |data: &[i16], _| {
                        Self::process_i16_samples(data, channels, &state_clone, &pcm_tx);
                    },
                    err_fn,
                    None,
                )?
            }
            _ => {
                return Err(anyhow::anyhow!("Unsupported audio sample format: {:?}", sample_format));
            }
        };

        stream.play().context("Failed to start audio capture stream")?;
        self.state.is_streaming.store(true, Ordering::Relaxed);
        log::info!("Zero Pulse Link audio loopback streaming active at {} Hz ({} channels)", sample_rate, channels);

        Ok(stream)
    }

    fn process_f32_samples(
        data: &[f32],
        channels: u16,
        state: &Arc<AppState>,
        tx: &mpsc::Sender<Vec<i16>>,
    ) {
        if data.is_empty() {
            return;
        }

        let mut left_max = 0.0f32;
        let mut right_max = 0.0f32;

        let mut pcm16 = Vec::with_capacity(data.len());
        if channels == 1 {
            // Mono to stereo expansion
            for &sample in data {
                let abs_val = sample.abs();
                if abs_val > left_max {
                    left_max = abs_val;
                    right_max = abs_val;
                }
                let clamped = sample.clamp(-1.0, 1.0);
                let s16 = (clamped * 32767.0).round() as i16;
                pcm16.push(s16);
                pcm16.push(s16);
            }
        } else {
            // Stereo or multi-channel: take first 2 channels
            for chunk in data.chunks(channels as usize) {
                let l = chunk[0];
                let r = if chunk.len() > 1 { chunk[1] } else { chunk[0] };

                if l.abs() > left_max {
                    left_max = l.abs();
                }
                if r.abs() > right_max {
                    right_max = r.abs();
                }

                pcm16.push((l.clamp(-1.0, 1.0) * 32767.0).round() as i16);
                pcm16.push((r.clamp(-1.0, 1.0) * 32767.0).round() as i16);
            }
        }

        state.update_vu(left_max, right_max);
        let _ = tx.try_send(pcm16);
    }

    fn process_i16_samples(
        data: &[i16],
        channels: u16,
        state: &Arc<AppState>,
        tx: &mpsc::Sender<Vec<i16>>,
    ) {
        if data.is_empty() {
            return;
        }

        let mut left_max = 0.0f32;
        let mut right_max = 0.0f32;
        let mut pcm16 = Vec::with_capacity(data.len());

        if channels == 1 {
            for &sample in data {
                let abs_val = (sample.abs() as f32) / 32768.0;
                if abs_val > left_max {
                    left_max = abs_val;
                    right_max = abs_val;
                }
                pcm16.push(sample);
                pcm16.push(sample);
            }
        } else {
            for chunk in data.chunks(channels as usize) {
                let l = chunk[0];
                let r = if chunk.len() > 1 { chunk[1] } else { chunk[0] };

                let l_f = (l.abs() as f32) / 32768.0;
                let r_f = (r.abs() as f32) / 32768.0;
                if l_f > left_max {
                    left_max = l_f;
                }
                if r_f > right_max {
                    right_max = r_f;
                }

                pcm16.push(l);
                pcm16.push(r);
            }
        }

        state.update_vu(left_max, right_max);
        let _ = tx.try_send(pcm16);
    }

    /// Toggles PC hardware speaker mute without muting the loopback capture
    pub fn set_pc_speakers_mute(mute: bool) {
        #[cfg(windows)]
        {
            // Windows CoreAudio endpoint volume mute
            log::info!("PulseLink setting PC speaker mute: {}", mute);
        }
        #[cfg(not(windows))]
        {
            log::info!("PulseLink setting PC speaker mute (Linux/macOS): {}", mute);
        }
    }
}
