//! Zero Pulse Link Desktop Audio Server
//!
//! Production-grade, ultra-low-latency audio streaming suite.
//! Turns an Android/iOS smartphone into a lossless wireless and wired speaker for PC.

mod adb_bridge;
mod audio_capture;
mod codecs;
mod network;
mod state;

use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;

use adb_bridge::AdbController;
use audio_capture::AudioCaptureEngine;
use eframe::egui;
use network::NetworkManager;
use protocol_common::{AudioCodec, SampleRate, StereoRouting};
use state::AppState;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    log::info!("Starting Zero Pulse Link Audio Server v1.0.0...");

    let state = AppState::new();

    // 1. Initialize ADB Controller & start background monitor
    let adb = Arc::new(AdbController::new());
    adb.clone().start_monitor(Arc::clone(&state));

    // 2. Initialize Network Manager
    let network = Arc::new(NetworkManager::new(Arc::clone(&state)).await?);

    // 3. Spawn TCP Control Server (port 59100)
    let net_control = Arc::clone(&network);
    tokio::spawn(async move {
        if let Err(e) = net_control.run_control_server().await {
            log::error!("Control server failed: {}", e);
        }
    });

    // 4. Spawn TCP Audio Tunnel for ADB Wired Mode (port 59101)
    let net_tunnel = Arc::clone(&network);
    tokio::spawn(async move {
        if let Err(e) = net_tunnel.run_tcp_audio_tunnel().await {
            log::error!("TCP audio tunnel failed: {}", e);
        }
    });

    // 5. Spawn UDP Discovery Responder (port 59100)
    let net_disc = Arc::clone(&network);
    tokio::spawn(async move {
        if let Err(e) = net_disc.run_discovery_responder().await {
            log::error!("Discovery responder failed: {}", e);
        }
    });

    // 6. Start WASAPI Audio Loopback Capture
    let audio_engine = Arc::new(AudioCaptureEngine::new(Arc::clone(&state), Arc::clone(&network)));
    let _stream = match Arc::clone(&audio_engine).start_loopback_capture() {
        Ok(s) => Some(s),
        Err(e) => {
            log::warn!("Could not start audio loopback capture immediately: {}", e);
            None
        }
    };

    // 7. Launch Desktop GUI (eframe / egui)
    let native_options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([880.0, 700.0])
            .with_min_inner_size([640.0, 500.0])
            .with_title("Zero Pulse Link Server"),
        ..Default::default()
    };

    let gui_app = PulseLinkGuiApp::new(state, adb);

    if let Err(e) = eframe::run_native(
        "Zero Pulse Link",
        native_options,
        Box::new(|cc| {
            // Apply Pure AMOLED black theme (#000000) with clean minimalist styling
            let mut visuals = egui::Visuals::dark();
            visuals.panel_fill = egui::Color32::from_rgb(0, 0, 0); // Pure AMOLED black #000000
            visuals.window_fill = egui::Color32::from_rgb(0, 0, 0);
            visuals.selection.bg_fill = egui::Color32::from_rgb(0, 229, 255);
            visuals.widgets.noninteractive.bg_fill = egui::Color32::from_rgb(10, 10, 10);
            visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(18, 18, 18);
            visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(28, 28, 28);
            visuals.widgets.active.bg_fill = egui::Color32::from_rgb(38, 38, 38);
            cc.egui_ctx.set_visuals(visuals);

            Ok(Box::new(gui_app))
        }),
    ) {
        log::error!("GUI run failed: {}", e);
    }

    Ok(())
}

/// egui Desktop GUI Application
pub struct PulseLinkGuiApp {
    state: Arc<AppState>,
    adb: Arc<AdbController>,
    selected_device_name: String,
    local_ips: Vec<String>,
    qr_ascii: String,
}

impl PulseLinkGuiApp {
    pub fn new(state: Arc<AppState>, adb: Arc<AdbController>) -> Self {
        let local_ips = NetworkManager::get_all_local_ips();
        let qr_ascii = generate_qr_ascii(&local_ips);

        Self {
            state,
            adb,
            selected_device_name: "Default PC Audio Output".into(),
            local_ips,
            qr_ascii,
        }
    }
}

impl eframe::App for PulseLinkGuiApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        ctx.request_repaint_after(Duration::from_millis(33)); // 30 FPS UI refresh

        egui::CentralPanel::default().show(ctx, |ui| {
            egui::ScrollArea::vertical().show(ui, |ui| {
                // Top Header
                ui.horizontal(|ui| {
                    ui.heading("⚡ ZERO PULSE LINK");
                    ui.label(egui::RichText::new("LOSSLESS PC AUDIO").monospace().weak());
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        let is_streaming = self.state.is_streaming.load(Ordering::Relaxed);
                        if is_streaming {
                            ui.colored_label(egui::Color32::from_rgb(0, 230, 118), "● ACTIVE");
                        } else {
                            ui.colored_label(egui::Color32::from_rgb(255, 171, 0), "● STANDBY");
                        }
                    });
                });

                ui.separator();

                // Live Audio Level VU Meter
                let (vu_l, vu_r) = self.state.get_vu();
                ui.group(|ui| {
                    ui.label(egui::RichText::new("Audio Visualizer (Peak Loopback Level)").strong());
                    ui.horizontal(|ui| {
                        ui.label("L:");
                        let bar_l = egui::ProgressBar::new(vu_l)
                            .desired_width(ui.available_width() * 0.45)
                            .show_percentage();
                        ui.add(bar_l);

                        ui.label("R:");
                        let bar_r = egui::ProgressBar::new(vu_r)
                            .desired_width(ui.available_width())
                            .show_percentage();
                        ui.add(bar_r);
                    });
                });

                ui.add_space(8.0);

                // Connection & Zero-Config Discovery Card
                ui.group(|ui| {
                    ui.label(egui::RichText::new("📡 Network & Zero-Config Pairing").strong());
                    ui.horizontal(|ui| {
                        ui.vertical(|ui| {
                            ui.label("Connect your phone via Wi-Fi or USB to any of these IPs:");
                            for ip in &self.local_ips {
                                let badge = if ip == "127.0.0.1" {
                                    "(ADB USB Mode)"
                                } else if ip.starts_with("192.168.42.") {
                                    "(USB Tethering)"
                                } else {
                                    "(Wi-Fi LAN)"
                                };
                                ui.monospace(format!("• {}:59100  {}", ip, badge));
                            }
                        });

                        ui.separator();

                        ui.vertical(|ui| {
                            ui.label(egui::RichText::new("Quick QR Code:").small());
                            ui.monospace(&self.qr_ascii);
                        });
                    });
                });

                ui.add_space(8.0);

                // USB Wired Mode & ADB Status Card
                ui.group(|ui| {
                    ui.horizontal(|ui| {
                        ui.label(egui::RichText::new("🔌 One-Click USB Wired Mode (Direct ADB)").strong());
                        let adb_connected = self.state.adb_device_connected.load(Ordering::Relaxed);
                        let adb_forwarded = self.state.adb_forwarded.load(Ordering::Relaxed);

                        if adb_connected && adb_forwarded {
                            ui.colored_label(egui::Color32::from_rgb(0, 230, 118), "✔ ADB USB TUNNEL ACTIVE");
                        } else if adb_connected {
                            ui.colored_label(egui::Color32::from_rgb(255, 171, 0), "⏳ ADB Device Detected, Forwarding...");
                        } else {
                            ui.colored_label(egui::Color32::from_rgb(180, 180, 180), "No USB device detected");
                        }
                    });

                    ui.horizontal(|ui| {
                        if ui.button("⚡ Forward ADB Ports Now").clicked() {
                            let adb_c = Arc::clone(&self.adb);
                            let state_c = Arc::clone(&self.state);
                            tokio::spawn(async move {
                                if let Ok(()) = adb_c.forward_ports().await {
                                    state_c.adb_forwarded.store(true, Ordering::Relaxed);
                                }
                            });
                        }
                        ui.label("Plug in USB cable with USB Debugging enabled for <5ms wired lossless audio.");
                    });
                });

                ui.add_space(8.0);

                // Codec & Quality Pipeline Settings
                ui.group(|ui| {
                    ui.label(egui::RichText::new("🎛 Audio Pipeline & Lossless Codec").strong());

                    let mut cfg = tokio::task::block_in_place(|| {
                        tokio::runtime::Handle::current().block_on(async {
                            self.state.config.read().await.active_codec
                        })
                    });

                    let mut rate = tokio::task::block_in_place(|| {
                        tokio::runtime::Handle::current().block_on(async {
                            self.state.config.read().await.sample_rate
                        })
                    });

                    let mut mute_speakers = tokio::task::block_in_place(|| {
                        tokio::runtime::Handle::current().block_on(async {
                            self.state.config.read().await.mute_pc_speakers_on_connect
                        })
                    });

                    ui.horizontal(|ui| {
                        ui.label("Codec Mode:");
                        egui::ComboBox::from_id_source("codec_select")
                            .selected_text(cfg.name())
                            .show_ui(ui, |ui| {
                                ui.selectable_value(&mut cfg, AudioCodec::Pcm16, "PCM 16-bit Lossless");
                                ui.selectable_value(&mut cfg, AudioCodec::Pcm24, "PCM 24-bit Lossless (Hi-Res)");
                                ui.selectable_value(&mut cfg, AudioCodec::Flac, "FLAC Stream Lossless");
                                ui.selectable_value(&mut cfg, AudioCodec::Opus, "Opus Ultra-Low-Delay");
                            });

                        ui.separator();

                        ui.label("Sample Rate:");
                        egui::ComboBox::from_id_source("rate_select")
                            .selected_text(format!("{} Hz", rate.hz()))
                            .show_ui(ui, |ui| {
                                ui.selectable_value(&mut rate, SampleRate::Rate44100, "44,100 Hz (CD)");
                                ui.selectable_value(&mut rate, SampleRate::Rate48000, "48,000 Hz (Studio)");
                                ui.selectable_value(&mut rate, SampleRate::Rate96000, "96,000 Hz (Hi-Res)");
                            });

                        ui.separator();

                        let calc_bitrate = match cfg {
                            AudioCodec::Pcm16 => rate.hz() as f32 * 2.0 * 2.0 * 8.0 / 1_000_000.0,
                            AudioCodec::Pcm24 => rate.hz() as f32 * 2.0 * 3.0 * 8.0 / 1_000_000.0,
                            AudioCodec::Flac => (rate.hz() as f32 * 2.0 * 2.0 * 8.0 / 1_000_000.0) * 0.55,
                            AudioCodec::Opus => 0.160,
                        };
                        ui.label(format!("📊 Throughput: {:.3} Mbps", calc_bitrate));
                    });

                    ui.checkbox(&mut mute_speakers, "🔇 Mute PC Speakers on Connect (Capture Loopback Only)");

                    // Commit changes to state
                    tokio::task::block_in_place(|| {
                        tokio::runtime::Handle::current().block_on(async {
                            let mut c = self.state.config.write().await;
                            c.active_codec = cfg;
                            c.sample_rate = rate;
                            c.mute_pc_speakers_on_connect = mute_speakers;
                        })
                    });
                });

                ui.add_space(8.0);

                // Multi-Device Streaming Registry
                ui.group(|ui| {
                    ui.label(egui::RichText::new("📱 Connected Mobile Devices (Multi-Device Streaming)").strong());

                    let clients_snapshot = tokio::task::block_in_place(|| {
                        tokio::runtime::Handle::current().block_on(async {
                            self.state.clients.read().await.clone()
                        })
                    });

                    if clients_snapshot.is_empty() {
                        ui.label("No phones currently connected. Open the PulseLink Android app to stream audio.");
                    } else {
                        for (cid, client) in clients_snapshot {
                            ui.separator();
                            ui.horizontal(|ui| {
                                ui.strong(format!("📱 {}", client.device_name));
                                ui.monospace(format!("({})", client.model));
                                ui.colored_label(
                                    egui::Color32::from_rgb(0, 180, 216),
                                    format!("[{}]", client.transport_type.label()),
                                );
                                ui.monospace(format!("IP: {}", client.control_addr));
                            });

                            ui.horizontal(|ui| {
                                ui.label(format!(
                                    "RTT: {:.1}ms | Loss: {:.1}% | Buffer: {:.1}ms | Bitrate: {:.0} kbps",
                                    client.rtt_ms, client.packet_loss_pct, client.buffer_fill_ms, client.bitrate_kbps
                                ));
                            });

                            let mut vol = client.volume;
                            let mut routing = client.stereo_routing.clone();
                            let mut offset = client.sync_offset_ms;

                            ui.horizontal(|ui| {
                                ui.label("Gain:");
                                ui.add(egui::Slider::new(&mut vol, 0.0..=2.0).text("x Vol (up to +15dB)"));

                                ui.separator();

                                ui.label("Channel Routing:");
                                egui::ComboBox::from_id_source(format!("route_{}", cid))
                                    .selected_text(format!("{:?}", routing))
                                    .show_ui(ui, |ui| {
                                        ui.selectable_value(&mut routing, StereoRouting::Stereo, "Stereo");
                                        ui.selectable_value(&mut routing, StereoRouting::LeftOnly, "Left Channel Only");
                                        ui.selectable_value(&mut routing, StereoRouting::RightOnly, "Right Channel Only");
                                        ui.selectable_value(&mut routing, StereoRouting::MonoDownmix, "Mono Downmix");
                                    });

                                ui.separator();

                                ui.label("Sync Offset:");
                                ui.add(egui::Slider::new(&mut offset, -200..=200).text("ms"));
                            });

                            // Update client settings if changed
                            if (vol - client.volume).abs() > 0.01
                                || routing != client.stereo_routing
                                || offset != client.sync_offset_ms
                            {
                                let state_c = Arc::clone(&self.state);
                                let cid_c = cid.clone();
                                tokio::task::block_in_place(|| {
                                    tokio::runtime::Handle::current().block_on(async move {
                                        let mut cls = state_c.clients.write().await;
                                        if let Some(c) = cls.get_mut(&cid_c) {
                                            c.volume = vol;
                                            c.stereo_routing = routing;
                                            c.sync_offset_ms = offset;
                                        }
                                    });
                                });
                            }
                        }
                    }
                });
            });
        });
    }
}

/// Helper to generate ASCII QR code representing primary network IP
fn generate_qr_ascii(ips: &[String]) -> String {
    let target = ips.iter().find(|ip| !ip.starts_with("127.")).unwrap_or(&ips[0]);
    let payload = format!("zeropulselink://{}:59100", target);
    match qrcode::QrCode::new(payload.as_bytes()) {
        Ok(code) => code.render::<char>().quiet_zone(false).module_dimensions(1, 1).build(),
        Err(_) => "[QR Unavailable]".into(),
    }
}
