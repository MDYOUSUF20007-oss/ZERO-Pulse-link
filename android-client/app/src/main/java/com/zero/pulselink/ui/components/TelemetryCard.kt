package com.zero.pulselink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zero.pulselink.ui.theme.AccentGreen
import com.zero.pulselink.ui.theme.PrimaryCyan
import com.zero.pulselink.ui.theme.SurfaceDark
import com.zero.pulselink.ui.theme.WarningAmber

@Composable
fun TelemetryCard(
    rttMs: Float,
    packetLossPct: Float,
    bufferFillMs: Float,
    underruns: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceDark)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        TelemetryItem(label = "LATENCY", value = "${String.format("%.1f", rttMs)} ms", color = PrimaryCyan)
        TelemetryItem(label = "BUFFER", value = "${String.format("%.1f", bufferFillMs)} ms", color = AccentGreen)
        TelemetryItem(
            label = "PACKET LOSS",
            value = "${String.format("%.1f", packetLossPct)}%",
            color = if (packetLossPct > 2f) WarningAmber else Color.Gray
        )
        TelemetryItem(
            label = "UNDERRUNS",
            value = "$underruns",
            color = if (underruns > 0) WarningAmber else Color.Gray
        )
    }
}

@Composable
private fun TelemetryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = color)
    }
}
