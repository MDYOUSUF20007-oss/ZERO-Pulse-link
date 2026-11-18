//! Automatic USB ADB bridge controller and USB tethering interface detector.
//!
//! Enables plug-and-play USB wired mode without requiring carrier USB tethering plans:
//! 1. Discovers connected USB Android devices via `adb devices -l`
//! 2. Automatically executes `adb forward tcp:59100 tcp:59100` and `adb forward tcp:59101 tcp:59101`
//! 3. Detects USB Tethering (RNDIS) IP subnets (192.168.42.x)
//! 4. Monitors USB connect/disconnect events every 2 seconds

use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use std::time::Duration;
use tokio::process::Command;
use tokio::time::sleep;

use crate::state::AppState;

#[derive(Debug, Clone)]
pub struct AdbDeviceInfo {
    pub serial: String,
    pub state: String,
    pub model: String,
    pub product: String,
}

pub struct AdbController {
    adb_path: Option<PathBuf>,
}

impl AdbController {
    pub fn new() -> Self {
        let adb_path = Self::find_adb_binary();
        Self { adb_path }
    }

    /// Search for `adb.exe` / `adb` in standard platform locations and system PATH
    pub fn find_adb_binary() -> Option<PathBuf> {
        let candidates = [
            PathBuf::from(r"C:\Users\Admin\platform-tools\adb.exe"),
            PathBuf::from(r"C:\platform-tools\adb.exe"),
            PathBuf::from(r"C:\Android\platform-tools\adb.exe"),
            dirs_fallback().join(r"AppData\Local\Android\Sdk\platform-tools\adb.exe"),
            PathBuf::from("adb.exe"),
            PathBuf::from("adb"),
        ];

        for path in &candidates {
            if path.exists() {
                log::info!("Zero Pulse Link found ADB at: {:?}", path);
                return Some(path.clone());
            }
        }

        // Check if adb is in PATH by running `adb version`
        if let Ok(output) = std::process::Command::new("adb")
            .arg("version")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
        {
            if output.success() {
                return Some(PathBuf::from("adb"));
            }
        }

        None
    }

    pub fn is_adb_installed(&self) -> bool {
        self.adb_path.is_some()
    }

    /// Execute `adb devices -l` to parse all currently attached Android devices
    pub async fn list_devices(&self) -> Vec<AdbDeviceInfo> {
        let Some(adb) = &self.adb_path else {
            return Vec::new();
        };

        let output = match Command::new(adb)
            .arg("devices")
            .arg("-l")
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .await
        {
            Ok(out) if out.status.success() => String::from_utf8_lossy(&out.stdout).to_string(),
            _ => return Vec::new(),
        };

        let mut devices = Vec::new();
        for line in output.lines().skip(1) {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }

            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                let serial = parts[0].to_string();
                let state = parts[1].to_string();

                let mut model = "Android Device".to_string();
                let mut product = "Unknown".to_string();

                for item in &parts[2..] {
                    if let Some(m) = item.strip_prefix("model:") {
                        model = m.replace('_', " ");
                    } else if let Some(p) = item.strip_prefix("product:") {
                        product = p.to_string();
                    }
                }

                devices.push(AdbDeviceInfo {
                    serial,
                    state,
                    model,
                    product,
                });
            }
        }

        devices
    }

    /// Reverse-forward TCP port 59100 and 59101 over USB ADB (Android 127.0.0.1 -> PC Server)
    pub async fn forward_ports(&self) -> Result<(), String> {
        let Some(adb) = &self.adb_path else {
            return Err("ADB binary not found".into());
        };

        // Reverse-forward Control Channel (59100) from Android localhost -> PC localhost
        let res1 = Command::new(adb)
            .args(["reverse", "tcp:59100", "tcp:59100"])
            .output()
            .await
            .map_err(|e| format!("Failed to reverse port 59100: {}", e))?;

        if !res1.status.success() {
            let err = String::from_utf8_lossy(&res1.stderr);
            return Err(format!("adb reverse 59100 failed: {}", err));
        }

        // Reverse-forward Audio Channel (59101) from Android localhost -> PC localhost
        let res2 = Command::new(adb)
            .args(["reverse", "tcp:59101", "tcp:59101"])
            .output()
            .await
            .map_err(|e| format!("Failed to reverse port 59101: {}", e))?;

        if !res2.status.success() {
            let err = String::from_utf8_lossy(&res2.stderr);
            return Err(format!("adb reverse 59101 failed: {}", err));
        }

        log::info!("Zero Pulse Link successfully reversed ADB ports: tcp:59100 and tcp:59101");
        Ok(())
    }

    /// Detect USB Tethering (RNDIS) subnet (e.g. 192.168.42.x)
    pub fn find_usb_tethering_ip() -> Option<String> {
        if let Ok(interfaces) = local_ip_address::list_afinet_netifas() {
            for (_name, ip) in interfaces {
                let ip_str = ip.to_string();
                // Standard Android RNDIS USB Tethering subnets
                if ip_str.starts_with("192.168.42.") || ip_str.starts_with("192.168.43.") {
                    return Some(ip_str);
                }
            }
        }
        None
    }

    /// Background monitor loop that continuously checks USB status and forwards ports
    pub fn start_monitor(self: Arc<Self>, state: Arc<AppState>) {
        tokio::spawn(async move {
            loop {
                let devices = self.list_devices().await;
                let has_device = !devices.is_empty();
                state.adb_device_connected.store(has_device, Ordering::Relaxed);

                if has_device {
                    if !state.adb_forwarded.load(Ordering::Relaxed) {
                        if let Ok(()) = self.forward_ports().await {
                            state.adb_forwarded.store(true, Ordering::Relaxed);
                        }
                    }
                } else {
                    state.adb_forwarded.store(false, Ordering::Relaxed);
                }

                sleep(Duration::from_secs(2)).await;
            }
        });
    }
}

fn dirs_fallback() -> PathBuf {
    if let Ok(user_profile) = std::env::var("USERPROFILE") {
        PathBuf::from(user_profile)
    } else {
        PathBuf::from(r"C:\Users\Admin")
    }
}
