package com.zero.pulselink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.zero.pulselink.net.BufferProfile
import com.zero.pulselink.net.StereoRouting
import com.zero.pulselink.service.AudioStreamForegroundService
import com.zero.pulselink.ui.components.TelemetryCard
import com.zero.pulselink.ui.components.VuMeter
import com.zero.pulselink.ui.theme.*

@Composable
fun DashboardScreen(
    service: AudioStreamForegroundService,
    onDisconnect: () -> Unit
) {
    val isStreaming by service.isStreaming.collectAsState()
    val serverName by service.serverName.collectAsState()
    val codec by service.activeCodec.collectAsState()
    val sampleRate by service.sampleRateHz.collectAsState()
    val rttMs by service.latencyMs.collectAsState()
    val packetLoss by service.packetLossPct.collectAsState()
    val bufferFill by service.bufferFillMs.collectAsState()
    val underruns by service.underruns.collectAsState()
    val vuLeft by service.vuLeft.collectAsState()
    val vuRight by service.vuRight.collectAsState()
    val volume by service.volumeLevel.collectAsState()
    val isMuted by service.isMuted.collectAsState()
    val routing by service.stereoRouting.collectAsState()
    val profile by service.bufferProfile.collectAsState()

    val bitrateMbps = (sampleRate.toFloat() * 2f * 16f) / 1_000_000f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "ZERO PULSE LINK",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = PrimaryCyan
                )
                Text(serverName, fontSize = 13.sp, color = TextSecondary)
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isStreaming) AccentGreen.copy(alpha = 0.12f) else Color(0xFF141416)
            ) {
                Text(
                    text = if (isStreaming) "● CONNECTED" else "STANDBY",
                    color = if (isStreaming) AccentGreen else Color.LightGray,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Codec & Bitrate Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("CODEC", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text("$codec (${sampleRate / 1000}kHz)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PrimaryCyan)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("THROUGHPUT", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text("${String.format("%.3f", bitrateMbps)} Mbps", fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = AccentGreen)
            }
        }

        // Live Audio Visualizer (driven by real C++ audio peak telemetry)
        Text("AUDIO VISUALIZER", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Gray)
        VuMeter(
            leftLevel = if (isStreaming && !isMuted) vuLeft else 0.0f,
            rightLevel = if (isStreaming && !isMuted) vuRight else 0.0f
        )

        // Telemetry Card
        TelemetryCard(
            rttMs = rttMs,
            packetLossPct = packetLoss,
            bufferFillMs = bufferFill,
            underruns = underruns
        )

        // Volume Boost Slider (+15 dB Soft Limiter)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("OUTPUT GAIN", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Gray)
                val boostText = if (volume > 1.0f) {
                    val db = (volume - 1.0f) * 15f
                    "+${String.format("%.1f", db)} dB BOOST"
                } else {
                    "${(volume * 100).toInt()}%"
                }
                Text(
                    text = boostText,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (volume > 1.0f) WarningAmber else PrimaryCyan
                )
            }
            Slider(
                value = volume,
                onValueChange = { service.setVolume(it) },
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(
                    thumbColor = if (volume > 1.0f) WarningAmber else PrimaryCyan,
                    activeTrackColor = if (volume > 1.0f) WarningAmber else PrimaryCyan
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0%", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                Text("100% (Unity)", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                Text("200% (+15dB)", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = WarningAmber)
            }
        }

        // Buffer Profile Selector
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Text("LATENCY / BUFFER PROFILE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BufferProfile.values().forEach { prof ->
                    val isSelected = profile == prof
                    Button(
                        onClick = { service.setBufferProfile(prof) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) AccentGreen else SurfaceVariantDark,
                            contentColor = if (isSelected) AmoledBackground else Color.White
                        ),
                        contentPadding = PaddingValues(4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when (prof) {
                                BufferProfile.ULTRA_LOW -> "Ultra-Low"
                                BufferProfile.BALANCED -> "Balanced"
                                BufferProfile.HIGH_STABILITY -> "Stable"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Stereo Routing Selector (Stereo, Left, Right, Mono)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceDark)
                .padding(14.dp)
        ) {
            Text("CHANNEL ROUTING", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Gray)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StereoRouting.values().forEach { routeOption ->
                    val isSelected = routing == routeOption
                    Button(
                        onClick = { service.setStereoRouting(routeOption) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) PrimaryCyan else SurfaceVariantDark,
                            contentColor = if (isSelected) AmoledBackground else Color.White
                        ),
                        contentPadding = PaddingValues(4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when (routeOption) {
                                StereoRouting.STEREO -> "Stereo"
                                StereoRouting.LEFT_ONLY -> "Left"
                                StereoRouting.RIGHT_ONLY -> "Right"
                                StereoRouting.MONO_DOWNMIX -> "Mono"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Actions: Mute & Disconnect
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { service.toggleMute() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (isMuted) "UNMUTE" else "MUTE", color = if (isMuted) DangerRed else Color.White)
            }

            Button(
                onClick = {
                    service.stopStreamingSession()
                    onDisconnect()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("DISCONNECT", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
