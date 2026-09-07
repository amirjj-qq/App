package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.NeonEmerald
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RadarView(
    modifier: Modifier = Modifier,
    isScanning: Boolean = true,
    discoveredCount: Int = 0
) {
    val transition = rememberInfiniteTransition(label = "RadarTransition")

    // Rotating scan sweep
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarRotation"
    )

    // Pulsing outer ripple
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseAlpha"
    )

    val pulseScale by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseScale"
    )

    Box(
        modifier = modifier.size(240.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f - 12.dp.toPx()

            // Concentric radar circles
            for (i in 1..4) {
                val r = maxRadius * (i / 4f)
                drawCircle(
                    color = ElectricCyan.copy(alpha = 0.2f),
                    radius = r,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Crosshairs
            drawLine(
                color = ElectricCyan.copy(alpha = 0.25f),
                start = Offset(center.x - maxRadius, center.y),
                end = Offset(center.x + maxRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = ElectricCyan.copy(alpha = 0.25f),
                start = Offset(center.x, center.y - maxRadius),
                end = Offset(center.x, center.y + maxRadius),
                strokeWidth = 1.dp.toPx()
            )

            // Pulsing scan wave
            if (isScanning) {
                drawCircle(
                    color = ElectricCyan.copy(alpha = pulseAlpha * 0.4f),
                    radius = maxRadius * pulseScale,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Rotating sweep beam
                rotate(angle, pivot = center) {
                    val sweepBrush = Brush.sweepGradient(
                        colors = listOf(
                            Color.Transparent,
                            ElectricCyan.copy(alpha = 0.05f),
                            ElectricCyan.copy(alpha = 0.4f)
                        ),
                        center = center
                    )
                    drawCircle(
                        brush = sweepBrush,
                        radius = maxRadius,
                        center = center
                    )
                }
            }

            // Center Node
            drawCircle(
                color = ElectricCyan,
                radius = 7.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = center
            )

            // Draw blips for discovered peers around radar
            if (discoveredCount > 0) {
                val blipAngles = listOf(45.0, 140.0, 220.0, 310.0, 90.0, 180.0)
                val blipDistances = listOf(0.4f, 0.7f, 0.55f, 0.85f, 0.65f, 0.35f)

                for (idx in 0 until discoveredCount.coerceAtMost(blipAngles.size)) {
                    val rad = Math.toRadians(blipAngles[idx])
                    val dist = maxRadius * blipDistances[idx]
                    val blipX = center.x + (dist * cos(rad)).toFloat()
                    val blipY = center.y + (dist * sin(rad)).toFloat()

                    // Glow aura
                    drawCircle(
                        color = NeonEmerald.copy(alpha = 0.35f),
                        radius = 10.dp.toPx(),
                        center = Offset(blipX, blipY)
                    )
                    // Core point
                    drawCircle(
                        color = NeonEmerald,
                        radius = 4.dp.toPx(),
                        center = Offset(blipX, blipY)
                    )
                }
            }
        }
    }
}
