package com.zero.pulselink.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zero.pulselink.ui.theme.AccentGreen
import com.zero.pulselink.ui.theme.DangerRed
import com.zero.pulselink.ui.theme.PrimaryCyan
import com.zero.pulselink.ui.theme.WarningAmber

@Composable
fun VuMeter(
    leftLevel: Float,
    rightLevel: Float,
    modifier: Modifier = Modifier
) {
    val animLeft by animateFloatAsState(targetValue = leftLevel.coerceIn(0f, 1f), animationSpec = tween(50), label = "L")
    val animRight by animateFloatAsState(targetValue = rightLevel.coerceIn(0f, 1f), animationSpec = tween(50), label = "R")

    val gradient = Brush.horizontalGradient(
        colors = listOf(PrimaryCyan, AccentGreen, WarningAmber, DangerRed)
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("L", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF141416))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animLeft)
                        .background(gradient)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("R", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF141416))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animRight)
                        .background(gradient)
                )
            }
        }
    }
}
