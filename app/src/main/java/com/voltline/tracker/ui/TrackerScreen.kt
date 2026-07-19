package com.voltline.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voltline.tracker.tracking.TrackState
import com.voltline.tracker.tracking.TrackingStatus
import com.voltline.tracker.ui.theme.VoltAmber
import com.voltline.tracker.ui.theme.VoltCyan
import com.voltline.tracker.ui.theme.VoltLime
import com.voltline.tracker.ui.theme.VoltRed
import com.voltline.tracker.ui.theme.VoltSurface
import com.voltline.tracker.ui.theme.VoltTextDim
import com.voltline.tracker.ui.theme.VoltTextHi
import java.util.Locale

@Composable
fun TrackerScreen(
    state: TrackState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    val tracking = state.status != TrackingStatus.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader(state)
        Spacer(Modifier.height(12.dp))
        SpeedReadout(state)
        Spacer(Modifier.height(20.dp))
        StatGrid(state)
        Spacer(Modifier.height(16.dp))
        LaunchTraceCard(state)
        Spacer(Modifier.weight(1f))
        Controls(
            tracking = tracking,
            canExport = state.status == TrackingStatus.IDLE || tracking,
            onStart = onStart,
            onStop = onStop,
            onExport = onExport,
        )
    }
}

@Composable
private fun BrandHeader(state: TrackState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Bolt, contentDescription = null, tint = VoltCyan, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "VOLTLINE",
            color = VoltTextHi,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.weight(1f))
        GpsChip(state)
    }
    Text(
        text = "ELECTRIC INLINE TELEMETRY",
        color = VoltTextDim,
        fontSize = 10.sp,
        letterSpacing = 3.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun GpsChip(state: TrackState) {
    val (label, color) = when {
        state.status == TrackingStatus.IDLE -> "OFF" to VoltTextDim
        !state.hasGpsFix -> "GPS…" to VoltAmber
        state.gpsAccuracyM > 0f -> "±${state.gpsAccuracyM.toInt()}m" to VoltLime
        else -> "GPS" to VoltLime
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpeedReadout(state: TrackState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = String.format(Locale.US, "%.1f", state.speedKmh),
            color = VoltCyan,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
        )
        Text("KM / H", color = VoltTextDim, fontSize = 13.sp, letterSpacing = 4.sp)
    }
}

@Composable
private fun StatGrid(state: TrackState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("DISTANCE", String.format(Locale.US, "%.2f", state.distanceKm), "km", VoltLime, Modifier.weight(1f))
        StatTile("TOP", String.format(Locale.US, "%.1f", state.maxSpeedKmh), "km/h", VoltCyan, Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("AVG", String.format(Locale.US, "%.1f", state.avgSpeedKmh), "km/h", VoltTextHi, Modifier.weight(1f))
        StatTile("TIME", formatDuration(state.elapsedMs), "", VoltTextHi, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = VoltSurface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = VoltTextDim, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = VoltTextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun LaunchTraceCard(state: TrackState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VoltSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("LAUNCH TRACE", color = VoltTextDim, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = String.format(Locale.US, "peak %.2f g", state.peakAccelG),
                    color = VoltAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            AccelTrace(samples = state.accelTrace)
        }
    }
}

@Composable
private fun Controls(
    tracking: Boolean,
    canExport: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Button(
        onClick = if (tracking) onStop else onStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (tracking) VoltRed else VoltCyan,
            contentColor = Color.Black,
        ),
    ) {
        Icon(if (tracking) Icons.Filled.Stop else Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            if (tracking) "STOP SESSION" else "START SESSION",
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = onExport,
        enabled = canExport,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text("EXPORT CSV", color = VoltTextHi, letterSpacing = 2.sp, textAlign = TextAlign.Center)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}
