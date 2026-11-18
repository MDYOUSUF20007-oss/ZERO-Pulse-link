package com.zero.pulselink.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.zero.pulselink.net.ConnectionState
import com.zero.pulselink.net.DiscoveryBeacon
import com.zero.pulselink.net.QrPairingInfo
import com.zero.pulselink.ui.theme.*

@Composable
fun DeviceDiscoveryScreen(
    discoveredServers: List<DiscoveryBeacon>,
    connectionState: ConnectionState,
    connectionError: String?,
    onClearError: () -> Unit,
    onConnect: (hosts: List<String>, isAdb: Boolean, port: Int) -> Unit
) {
    var manualIp by remember { mutableStateOf("") }
    var qrError by remember { mutableStateOf<String?>(null) }

    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            val parsed = QrPairingInfo.parse(contents)
            if (parsed != null) {
                qrError = null
                onClearError()
                val isAdb = parsed.hosts.size == 1 && parsed.hosts[0] == "127.0.0.1"
                onConnect(parsed.hosts, isAdb, parsed.port)
            } else {
                qrError = "Invalid Zero Pulse Link QR code: $contents"
            }
        }
    }

    val isConnecting = connectionState == ConnectionState.CONNECTING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "ZERO PULSE LINK",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            color = PrimaryCyan
        )
        Text(
            "Lossless low-latency PC audio receiver",
            fontSize = 13.sp,
            color = TextSecondary
        )

        // Connecting Progress Banner
        if (isConnecting) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrimaryCyan.copy(alpha = 0.12f))
                    .border(1.dp, PrimaryCyan.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    color = PrimaryCyan,
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Column {
                    Text(
                        "CONNECTING TO PC...",
                        color = PrimaryCyan,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Verifying handshake with Zero Pulse Link Server",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Connection / QR Error Banner
        val activeError = qrError ?: connectionError
        if (activeError != null && !isConnecting) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DangerRed.copy(alpha = 0.14f))
                    .border(1.dp, DangerRed.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "CONNECTION FAILED",
                        color = DangerRed,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "DISMISS",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clickable {
                            qrError = null
                            onClearError()
                        }
                    )
                }
                Text(activeError, color = TextPrimary, fontSize = 12.sp)
            }
        }

        // Primary Quick-Connect Row: Scan PC QR Code + USB Wired Mode
        Button(
            onClick = {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Point camera at the QR Code in Zero Pulse Link PC Server")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
                qrScannerLauncher.launch(options)
            },
            enabled = !isConnecting,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = "Scan QR Code",
                tint = AmoledBackground,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    "SCAN PC QR CODE",
                    color = AmoledBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "Instant Wi-Fi pairing • Zero IP typing required",
                    color = AmoledBackground.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }

        // One-Click USB Wired Mode Button
        OutlinedButton(
            onClick = {
                qrError = null
                onClearError()
                onConnect(listOf("127.0.0.1"), true, 59100)
            },
            enabled = !isConnecting,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentGreen)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "⚡ ONE-CLICK USB WIRED MODE",
                    color = AccentGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    "Direct ADB Reverse Tunnel • <5ms Latency • No Tethering Plan",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Text(
            "DISCOVERED PC SERVERS",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color.Gray
        )

        if (discoveredServers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = PrimaryCyan,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Searching for Zero Pulse Link servers on Wi-Fi...",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Or tap SCAN PC QR CODE above to pair instantly",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(discoveredServers) { server ->
                    val hosts = server.addresses.ifEmpty { listOf("127.0.0.1") }
                    val primaryHost = hosts.first()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .clickable(enabled = !isConnecting) {
                                qrError = null
                                onClearError()
                                val isAdb = primaryHost == "127.0.0.1"
                                onConnect(hosts, isAdb, server.controlPort)
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                server.serverName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            Text(
                                "$primaryHost • ${server.os}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = PrimaryCyan.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "CONNECT",
                                color = PrimaryCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Manual Direct IP Connect
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Text(
                "DIRECT IP CONNECTION",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    placeholder = { Text("e.g. 192.168.1.50", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryCyan,
                        unfocusedBorderColor = Color(0xFF222226)
                    )
                )
                Button(
                    onClick = {
                        val parsed = QrPairingInfo.parse(manualIp)
                        if (parsed != null) {
                            qrError = null
                            onClearError()
                            val isAdb = parsed.hosts.size == 1 && parsed.hosts[0] == "127.0.0.1"
                            onConnect(parsed.hosts, isAdb, parsed.port)
                        }
                    },
                    enabled = !isConnecting && manualIp.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text("CONNECT", color = AmoledBackground, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
