package com.roboff.lan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun RobotProgressArt(
    progress: Float?,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false,
    isCleaning: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val animatedProgress by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 550),
        label = "cleaning progress",
    )
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(204.dp)
                .semantics {
                    contentDescription = when {
                        isConnecting -> "Connecting to vacuum"
                        isCleaning && progress != null -> "Cleaning progress ${(progress * 100).roundToInt()} percent"
                        isCleaning -> "Cleaning in progress; completion unavailable"
                        progress != null -> "Battery ${(progress * 100).roundToInt()} percent"
                        else -> "Vacuum ready"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(188.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeWidth = 7.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else if (progress != null) {
                CircularProgressIndicator(
                    progress = animatedProgress,
                    modifier = Modifier.size(188.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeWidth = 7.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else if (isCleaning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(188.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeWidth = 7.dp,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                CircularProgressIndicator(
                    progress = 0f,
                    modifier = Modifier.size(188.dp),
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeWidth = 7.dp,
                    strokeCap = StrokeCap.Round,
                )
            }
            VacuumIllustration(Modifier.size(204.dp))
        }
    }
}

@Composable
private fun VacuumIllustration(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .semantics { contentDescription = "Roborock vacuum" },
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.minDimension * .37f
        val center = androidx.compose.ui.geometry.Offset(centerX, centerY)
        val shadow = Color.Black.copy(alpha = .38f).compositeOver(colors.surface)
        val rim = Color(0xFFDDE2DD)
        val body = Color.White
        val seam = Color(0xFFC4CCC6)
        val turret = Color(0xFFF3F5F2)
        val button = Color(0xFF46534B)

        // Restore the detailed disc-shaped Roborock from the original Android client.
        drawCircle(shadow, radius = radius * 1.025f, center = center.copy(y = centerY + radius * .05f))
        drawCircle(rim, radius = radius, center = center)
        drawCircle(body, radius = radius * .95f, center = center.copy(y = centerY - radius * .025f))
        drawArc(
            color = seam,
            startAngle = 8f,
            sweepAngle = 164f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(centerX - radius * .84f, centerY - radius * .84f),
            size = androidx.compose.ui.geometry.Size(radius * 1.68f, radius * 1.68f),
            style = Stroke(width = radius * .012f),
        )
        drawLine(
            color = seam,
            start = androidx.compose.ui.geometry.Offset(centerX - radius * .9f, centerY + radius * .19f),
            end = androidx.compose.ui.geometry.Offset(centerX + radius * .9f, centerY + radius * .19f),
            strokeWidth = radius * .012f,
        )

        val turretCenter = androidx.compose.ui.geometry.Offset(centerX, centerY - radius * .38f)
        drawCircle(rim, radius = radius * .28f, center = turretCenter.copy(y = centerY - radius * .35f))
        drawCircle(turret, radius = radius * .255f, center = turretCenter)
        drawCircle(colors.primaryContainer, radius = radius * .13f, center = turretCenter)
        drawCircle(colors.primary, radius = radius * .085f, center = turretCenter)
        drawRoundRect(
            color = button,
            topLeft = androidx.compose.ui.geometry.Offset(centerX - radius * .15f, centerY + radius * .36f),
            size = androidx.compose.ui.geometry.Size(radius * .3f, radius * .1f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * .04f),
        )

        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.onSurfaceVariant.toArgb()
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = radius * .105f
        }
        drawContext.canvas.nativeCanvas.drawText("roborock", centerX, centerY + radius * .7f, labelPaint)
    }
}

@Composable
fun ModePreferenceSlider(
    title: String,
    labels: List<String>,
    wireValues: List<Int>,
    reportedMode: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    require(labels.size == wireValues.size && labels.size >= 2)
    val currentPosition = wireValues.indexOf(reportedMode).takeIf { it >= 0 }?.toFloat()
    var preview by remember(title) { mutableFloatStateOf(currentPosition ?: 0f) }
    var dragging by remember(title) { mutableStateOf(false) }
    LaunchedEffect(reportedMode) {
        if (!dragging && currentPosition != null) preview = currentPosition
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    dragging -> labels[preview.roundToInt().coerceIn(labels.indices)]
                    currentPosition != null -> labels[currentPosition.roundToInt()]
                    reportedMode < 0 -> "Unavailable"
                    else -> "Custom"
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = preview,
            onValueChange = {
                dragging = true
                preview = it
            },
            onValueChangeFinished = {
                dragging = false
                val selected = wireValues[preview.roundToInt().coerceIn(wireValues.indices)]
                if (enabled && selected != reportedMode) onCommit(selected)
            },
            valueRange = 0f..(labels.lastIndex.toFloat()),
            steps = labels.size - 2,
            enabled = enabled,
            colors = SliderDefaults.colors(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(labels.first(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(labels.last(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
