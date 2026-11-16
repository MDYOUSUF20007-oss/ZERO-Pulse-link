package com.zero.pulselink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.zero.pulselink.net.DiscoveryBeacon
import com.zero.pulselink.ui.theme.*

@Composable
fun DeviceDiscoveryScreen(
    discoveredServers: List<DiscoveryBeacon>,
    onConnect: (host: String, isAdb: Boolean) -> Unit
) {
    var manualIp by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ZERO PULSE LINK", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = PrimaryCyan)
        Text("Lossless low-latency PC audio receiver", fontSize = 13.sp, color = TextSecondary)

        // One-Click USB Wired Mode Button
        Button(
            onClick = { onConnect("127.0.0.1", true) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryCyan),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(14.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚡ ONE-CLICK USB WIRED MODE", color = AmoledBackground, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("Direct ADB Tunnel • <5ms Latency • Zero Tethering Plan Required", color = AmoledBackground.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(2.dp))
        Text("DISCOVERED PC SERVERS", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Gray)

        if (discoveredServers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceDark),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PrimaryCyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Searching for Zero Pulse Link servers on Wi-Fi...", color = TextSecondary, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(discoveredServers) { server ->
                    val host = server.addresses.firstOrNull() ?: "127.0.0.1"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .clickable { onConnect(host, server.adbAvailable) }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(server.serverName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                            Text("$host • ${server.os}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
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
            Text("DIRECT IP CONNECTION", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    placeholder = { Text("e.g. 192.168.1.50 or 10.0.0.2", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryCyan,
                        unfocusedBorderColor = Color(0xFF222226)
                    )
                )
                Button(
                    onClick = {
                        if (manualIp.isNotBlank()) {
                            onConnect(manualIp.trim(), false)
                        }
                    },
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
