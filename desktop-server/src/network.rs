//! High-performance network transport:
//! 1. UDP Audio Streamer with DSCP 0xB8 (Expedited Forwarding / WMM Voice Priority)
//! 2. Dedicated TCP-NoDelay Audio Tunnel for ultra-low-latency ADB USB wired mode
//! 3. TCP Control Channel on port 59100 for JSON handshake and telemetry
//! 4. UDP Broadcast zero-config discovery fallback on port 59100

use std::collections::HashSet;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use byteorder::{ByteOrder, LittleEndian};
use local_ip_address::list_afinet_netifas;
use socket2::{Domain, Protocol, Socket, Type};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::broadcast;
use tokio::time::sleep;

use protocol_common::{
    AudioPacketHeader, ControlMessage, DiscoveryBeacon,
    DEFAULT_AUDIO_PORT, DEFAULT_CONTROL_PORT, HEADER_SIZE,
};

use crate::codecs::{CodecFramer, StreamCompressor};
use crate::state::{AppState, ClientTransportType, ConnectedClient};

/// Network manager orchestrating control and audio streaming
pub struct NetworkManager {
    state: Arc<AppState>,
    udp_socket: Arc<UdpSocket>,
    audio_broadcast_tx: broadcast::Sender<Vec<u8>>,
}

impl NetworkManager {
    pub async fn new(state: Arc<AppState>) -> Result<Self> {
        let (audio_broadcast_tx, _) = broadcast::channel::<Vec<u8>>(128);

        // Bind UDP socket with DSCP 0xB8 (Expedited Forwarding) for WMM Voice Priority
        let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))
            .context("Failed to create socket2 UDP socket")?;

        socket.set_reuse_address(true).ok();
        #[cfg(not(windows))]
        socket.set_reuse_port(true).ok();

        // Set DSCP / IP_TOS = 0xB8 (DSCP 46 / Expedited Forwarding) for WMM Voice Priority
        socket.set_tos(0xB8).ok();

        let bind_addr: SocketAddr = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, DEFAULT_AUDIO_PORT).into();
        socket.bind(&bind_addr.into()).context("Failed to bind UDP audio port 59101")?;
        socket.set_nonblocking(true)?;

        let std_udp: std::net::UdpSocket = socket.into();
        let udp_socket = Arc::new(UdpSocket::from_std(std_udp)?);

        Ok(Self {
            state,
            udp_socket,
            audio_broadcast_tx,
        })
    }

    /// Broadcast captured audio frame to all connected clients (UDP & TCP ADB tunnels)
    pub async fn broadcast_audio_frame(&self, raw_pcm: &[i16]) {
        let config = self.state.config.read().await;
        let active_codec = config.active_codec;
        let sample_rate = config.sample_rate;
        let channels = config.channels;
        drop(config);

        let seq = self.state.next_sequence();
        let timestamp_us = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_micros() as u64;

        let clients_guard = self.state.clients.read().await;
        if clients_guard.is_empty() {
            return;
        }

        // For each client, apply volume, delay offset, and stereo routing
        for (_client_id, client) in clients_guard.iter() {
            let mut processed_pcm = if client.sync_offset_ms > 0 {
                let mut out = vec![0i16; raw_pcm.len()];
                let mut dl = client.delay_line.clone();
                dl.process(
                    raw_pcm,
                    &mut out,
                    client.sync_offset_ms,
                    sample_rate.hz(),
                    channels.as_u8() as usize,
                );
                out
            } else {
                raw_pcm.to_vec()
            };

            // 2. Stereo routing
            CodecFramer::apply_stereo_routing(&mut processed_pcm, &client.stereo_routing);

            // 3. Client Volume & Mute (Mute applied on server; volume scaling applied on phone for instant zero-latency touch response)
            if client.muted {
                processed_pcm.fill(0);
            }

            // 4. Encode audio payload
            let mut payload = Vec::new();
            let mut compressor = StreamCompressor::new(active_codec, channels, sample_rate.hz());
            compressor.compress(&processed_pcm, &mut payload);

            // 5. Construct 16-byte binary packet header
            let header = AudioPacketHeader::new(
                active_codec,
                protocol_common::StreamDirection::ServerToClient,
                channels,
                sample_rate,
                seq,
                timestamp_us,
            );

            let mut packet = vec![0u8; HEADER_SIZE + payload.len()];
            if header.encode(&mut packet[0..HEADER_SIZE]).is_ok() {
                packet[HEADER_SIZE..].copy_from_slice(&payload);

                // Dispatch to client transport
                match client.transport_type {
                    ClientTransportType::UdpWifi | ClientTransportType::UdpTethering => {
                        if let Some(target_addr) = client.audio_addr {
                            let _ = self.udp_socket.send_to(&packet, target_addr).await;
                        }
                    }
                    ClientTransportType::TcpAdb => {
                        // Forwarded via TCP NoDelay broadcast channel
                        let _ = self.audio_broadcast_tx.send(packet);
                    }
                }
            }
        }
    }

    /// Run the TCP Control Server on port 59100
    pub async fn run_control_server(self: Arc<Self>) -> Result<()> {
        let listener = TcpListener::bind(format!("0.0.0.0:{}", DEFAULT_CONTROL_PORT))
            .await
            .context("Failed to bind TCP control listener on port 59100")?;

        log::info!("Zero Pulse Link Control Server listening on TCP port {}", DEFAULT_CONTROL_PORT);

        loop {
            match listener.accept().await {
                Ok((stream, remote_addr)) => {
                    let self_clone = Arc::clone(&self);
                    tokio::spawn(async move {
                        if let Err(e) = self_clone.handle_control_client(stream, remote_addr).await {
                            log::warn!("Control client error from {}: {}", remote_addr, e);
                        }
                    });
                }
                Err(e) => {
                    log::error!("TCP accept error: {}", e);
                    sleep(Duration::from_millis(500)).await;
                }
            }
        }
    }

    /// Handle an individual connected control client
    async fn handle_control_client(
        self: Arc<Self>,
        stream: TcpStream,
        remote_addr: SocketAddr,
    ) -> Result<()> {
        stream.set_nodelay(true)?;
        let (reader, mut writer) = stream.into_split();
        let mut buf_reader = BufReader::new(reader);
        let mut line = String::new();

        let mut client_id = String::new();
        let is_adb = remote_addr.ip().is_loopback();
        let transport_type = if is_adb {
            ClientTransportType::TcpAdb
        } else if remote_addr.ip().to_string().starts_with("192.168.42.") {
            ClientTransportType::UdpTethering
        } else {
            ClientTransportType::UdpWifi
        };

        log::info!("Zero Pulse Link client connected from {} via {:?}", remote_addr, transport_type);

        loop {
            line.clear();
            let bytes_read = buf_reader.read_line(&mut line).await?;
            if bytes_read == 0 {
                break; // Client disconnected
            }

            let msg = match ControlMessage::from_line(&line) {
                Ok(m) => m,
                Err(e) => {
                    log::warn!("Malformed JSON control message from {}: {}", remote_addr, e);
                    continue;
                }
            };

            match msg {
                ControlMessage::ClientHello {
                    client_id: cid,
                    device_name,
                    model,
                    os,
                    version,
                    ..
                } => {
                    client_id = cid.clone();
                    let sample_rate = {
                        let cfg = self.state.config.read().await;
                        cfg.sample_rate.hz()
                    };

                    let new_client = ConnectedClient::new(
                        cid.clone(),
                        device_name,
                        model,
                        os,
                        version,
                        remote_addr,
                        transport_type,
                        sample_rate,
                    );

                    self.state.clients.write().await.insert(cid.clone(), new_client);

                    // Send ServerHello
                    let cfg = self.state.config.read().await;
                    let server_hello = ControlMessage::ServerHello {
                        server_name: cfg.server_name.clone(),
                        server_id: "zeropulselink-pc".into(),
                        version: "1.0.0".into(),
                        os: std::env::consts::OS.into(),
                        control_port: DEFAULT_CONTROL_PORT,
                        audio_port: DEFAULT_AUDIO_PORT,
                        active_codec: cfg.active_codec.name().into(),
                        sample_rate: cfg.sample_rate.hz(),
                        channels: cfg.channels.as_u8(),
                        frame_size_ms: cfg.frame_size_ms,
                        ip_addresses: Self::get_all_local_ips(),
                    };

                    writer.write_all(server_hello.to_line()?.as_bytes()).await?;
                    writer.flush().await?;
                }

                ControlMessage::Ping { sequence, timestamp_us } => {
                    let pong = ControlMessage::Pong {
                        sequence,
                        ping_timestamp_us: timestamp_us,
                        pong_timestamp_us: SystemTime::now()
                            .duration_since(UNIX_EPOCH)
                            .unwrap_or_default()
                            .as_micros() as u64,
                    };
                    writer.write_all(pong.to_line()?.as_bytes()).await?;
                    writer.flush().await?;
                }

                ControlMessage::Telemetry {
                    rtt_ms,
                    packet_loss_pct,
                    buffer_fill_ms,
                    underrun_count,
                    bitrate_kbps,
                    ..
                } => {
                    if !client_id.is_empty() {
                        let mut clients = self.state.clients.write().await;
                        if let Some(c) = clients.get_mut(&client_id) {
                            c.rtt_ms = rtt_ms;
                            c.packet_loss_pct = packet_loss_pct;
                            c.buffer_fill_ms = buffer_fill_ms;
                            c.underrun_count = underrun_count;
                            c.bitrate_kbps = bitrate_kbps;
                            c.last_seen = Instant::now();
                        }
                    }
                }

                ControlMessage::ClientSettings {
                    client_id: cid,
                    volume,
                    muted,
                    stereo_routing,
                    sync_offset_ms,
                } => {
                    let mut clients = self.state.clients.write().await;
                    if let Some(c) = clients.get_mut(&cid) {
                        c.volume = volume;
                        c.muted = muted;
                        c.stereo_routing = stereo_routing;
                        c.sync_offset_ms = sync_offset_ms;
                    }
                }

                _ => {}
            }
        }

        // Cleanup on disconnect
        if !client_id.is_empty() {
            log::info!("Client disconnected: {}", client_id);
            self.state.clients.write().await.remove(&client_id);
        }

        Ok(())
    }

    /// Dedicated TCP-NoDelay Audio Tunnel for USB ADB wired mode
    pub async fn run_tcp_audio_tunnel(self: Arc<Self>) -> Result<()> {
        let listener = TcpListener::bind(format!("0.0.0.0:{}", DEFAULT_AUDIO_PORT))
            .await
            .context("Failed to bind TCP audio tunnel on port 59101")?;

        log::info!("Zero Pulse Link TCP Audio Tunnel listening on port {}", DEFAULT_AUDIO_PORT);

        loop {
            match listener.accept().await {
                Ok((mut stream, addr)) => {
                    let _ = stream.set_nodelay(true);
                    let mut rx = self.audio_broadcast_tx.subscribe();

                    tokio::spawn(async move {
                        log::info!("TCP Audio tunnel stream established for {}", addr);
                        let mut len_buf = [0u8; 4];

                        while let Ok(packet) = rx.recv().await {
                            LittleEndian::write_u32(&mut len_buf, packet.len() as u32);
                            if stream.write_all(&len_buf).await.is_err() {
                                break;
                            }
                            if stream.write_all(&packet).await.is_err() {
                                break;
                            }
                            // Immediate flush for <5ms USB latency
                            if stream.flush().await.is_err() {
                                break;
                            }
                        }
                        log::info!("TCP Audio tunnel stream closed for {}", addr);
                    });
                }
                Err(e) => {
                    log::error!("TCP audio tunnel accept error: {}", e);
                    sleep(Duration::from_millis(500)).await;
                }
            }
        }
    }

    /// Run UDP Broadcast zero-config discovery responder on port 59100
    pub async fn run_discovery_responder(self: Arc<Self>) -> Result<()> {
        let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
        socket.set_reuse_address(true).ok();
        #[cfg(not(windows))]
        socket.set_reuse_port(true).ok();
        socket.set_broadcast(true)?;

        let bind_addr: SocketAddr = SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, DEFAULT_CONTROL_PORT).into();
        socket.bind(&bind_addr.into())?;
        socket.set_nonblocking(true)?;

        let std_udp: std::net::UdpSocket = socket.into();
        let discovery_sock = UdpSocket::from_std(std_udp)?;

        let mut buf = [0u8; 1024];
        log::info!("Zero Pulse Link Discovery Responder listening on UDP 59100");

        loop {
            match discovery_sock.recv_from(&mut buf).await {
                Ok((len, peer_addr)) => {
                    let query = String::from_utf8_lossy(&buf[..len]);
                    if query.contains("ZEROPULSELINK_DISCOVER") || query.contains("PULSELINK_DISCOVER") {
                        let ips = Self::get_all_local_ips();
                        let adb_on = self.state.adb_device_connected.load(Ordering::Relaxed);

                        let beacon = DiscoveryBeacon {
                            server_name: "Zero Pulse Link Server".into(),
                            server_id: "zeropulselink-pc".into(),
                            os: std::env::consts::OS.into(),
                            version: "1.0.0".into(),
                            control_port: DEFAULT_CONTROL_PORT,
                            audio_port: DEFAULT_AUDIO_PORT,
                            addresses: ips,
                            adb_available: adb_on,
                        };

                        if let Ok(beacon_json) = serde_json::to_vec(&beacon) {
                            let _ = discovery_sock.send_to(&beacon_json, peer_addr).await;
                        }
                    }
                }
                Err(e) => {
                    log::debug!("UDP discovery recv error: {}", e);
                }
            }
        }
    }

    /// Listen for UDP registration/NAT-punch packets on port 59101 from Android clients
    /// so we always route audio to the client's exact reachable UDP socket address.
    pub async fn run_udp_punch_listener(self: Arc<Self>) {
        let mut buf = [0u8; 256];
        log::info!("Zero Pulse Link UDP Audio Punch Listener active on UDP port {}", DEFAULT_AUDIO_PORT);

        loop {
            match self.udp_socket.recv_from(&mut buf).await {
                Ok((len, peer_addr)) => {
                    let msg = String::from_utf8_lossy(&buf[..len]);
                    if let Some(cid) = msg.trim().strip_prefix("ZP_UDP_HELLO:") {
                        let mut clients = self.state.clients.write().await;
                        if let Some(client) = clients.get_mut(cid) {
                            if client.audio_addr != Some(peer_addr) {
                                log::info!("Updated UDP audio destination for {} -> {}", cid, peer_addr);
                                client.audio_addr = Some(peer_addr);
                            }
                        } else {
                            // Fallback: match by IP address if ClientHello hasn't arrived yet or ID differs
                            for c in clients.values_mut() {
                                if c.control_addr.ip() == peer_addr.ip() {
                                    c.audio_addr = Some(peer_addr);
                                }
                            }
                        }
                    }
                }
                Err(e) => {
                    log::debug!("UDP punch recv error: {}", e);
                    sleep(Duration::from_millis(50)).await;
                }
            }
        }
    }

    /// Retrieve all local IPv4 addresses, prioritizing the OS primary LAN/Wi-Fi IP first.
    pub fn get_all_local_ips() -> Vec<String> {
        let mut ordered = Vec::new();
        let mut seen = HashSet::new();

        // 1. Primary default-route LAN/Wi-Fi IP first
        if let Ok(IpAddr::V4(primary)) = local_ip_address::local_ip() {
            if !primary.is_loopback() && !primary.is_link_local() {
                let s = primary.to_string();
                seen.insert(s.clone());
                ordered.push(s);
            }
        }

        // 2. Remaining non-loopback, non-link-local IPv4 addresses (192.168.*, 10.*, 172.*)
        let mut others = Vec::new();
        if let Ok(interfaces) = list_afinet_netifas() {
            for (_name, ip) in interfaces {
                if let IpAddr::V4(ipv4) = ip {
                    if !ipv4.is_loopback() && !ipv4.is_link_local() {
                        let s = ipv4.to_string();
                        if seen.insert(s.clone()) {
                            others.push(s);
                        }
                    }
                }
            }
        }
        // Prefer 192.168.* over virtual 172.*/10.* adapters
        others.sort_by_key(|ip| {
            if ip.starts_with("192.168.42.") || ip.starts_with("192.168.43.") {
                (0, ip.clone())
            } else if ip.starts_with("192.168.") {
                (1, ip.clone())
            } else if ip.starts_with("10.") {
                (2, ip.clone())
            } else {
                (3, ip.clone())
            }
        });
        ordered.extend(others);

        // 3. Loopback for USB ADB mode last
        if seen.insert("127.0.0.1".to_string()) {
            ordered.push("127.0.0.1".to_string());
        }

        ordered
    }
}
