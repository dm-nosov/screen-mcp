package com.voltline.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.voltline.tracker.ui.theme.VoltAmber
import com.voltline.tracker.ui.theme.VoltCyan
import com.voltline.tracker.ui.theme.VoltSurfaceHi
import kotlin.math.abs
import kotlin.math.max

/**
 * The launch trace: recent longitudinal acceleration drawn as a signed waveform
 * around a zero baseline. The high sensor rate is what makes the breakaway spike
 * legible — this is the shape you tune the ramp against.
 */
@Composable
fun AccelTrace(
    samples: List<Float>,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        drawBaseline()
        if (samples.size < 2) return@Canvas

        // Symmetric scale, floored so idle noise doesn't fill the panel.
        val peak = max(2.5f, samples.maxOf { abs(it) })
        val midY = size.height / 2f
        val stepX = size.width / (samples.size - 1)

        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = i * stepX
            val y = midY - (v / peak) * (midY * 0.9f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = VoltCyan, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))

        // Mark the peak sample.
        val peakIdx = samples.indices.maxByOrNull { abs(samples[it]) } ?: return@Canvas
        val px = peakIdx * stepX
        val py = midY - (samples[peakIdx] / peak) * (midY * 0.9f)
        drawCircle(color = VoltAmber, radius = 5f, center = Offset(px, py))
    }
}

private fun DrawScope.drawBaseline() {
    val midY = size.height / 2f
    drawLine(
        color = VoltSurfaceHi,
        start = Offset(0f, midY),
        end = Offset(size.width, midY),
        strokeWidth = 2f,
    )
}
