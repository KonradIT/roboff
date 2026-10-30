package com.roboff.lan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

enum class RemoteMode(val label: String) {
    JOYSTICK("Joystick"),
    BLE_REMOTE("BLE remote"),
}

enum class RemoteSessionState(val label: String) {
    IDLE("Ready"),
    STARTING("Connecting"),
    ACTIVE("Manual control active"),
    STOPPING("Stopping safely"),
}

data class RemoteDriveVector(val turn: Float = 0f, val forward: Float = 0f) {
    companion object {
        val ZERO = RemoteDriveVector()
    }
}

@Composable
fun RemoteControlScreen(
    mode: RemoteMode,
    onMode: (RemoteMode) -> Unit,
    state: RemoteSessionState,
    connected: Boolean,
    drive: RemoteDriveVector,
    invertTurn: Boolean,
    invertForward: Boolean,
    onDrive: (Float, Float) -> Unit,
    onInvertTurn: (Boolean) -> Unit,
    onInvertForward: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    availableControllers: List<String>,
    activeController: String,
    onOpenBluetoothSettings: () -> Unit,
) {
    val modes = RemoteMode.entries
    var confirmStart by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Remote control", style = MaterialTheme.typography.headlineMedium)
        }

        PrimaryTabRow(selectedTabIndex = modes.indexOf(mode).coerceAtLeast(0)) {
            modes.forEach { item ->
                Tab(
                    selected = mode == item,
                    onClick = { onMode(item) },
                    text = { Text(item.label) },
                )
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(
                                    when (state) {
                                        RemoteSessionState.ACTIVE -> MaterialTheme.colorScheme.primary
                                        RemoteSessionState.IDLE -> MaterialTheme.colorScheme.outline
                                        else -> MaterialTheme.colorScheme.tertiary
                                    },
                                    CircleShape,
                                ),
                        )
                        Text(state.label, style = MaterialTheme.typography.titleMedium)
                    }
                    when (state) {
                        RemoteSessionState.IDLE -> Button(
                            onClick = { confirmStart = true },
                            enabled = connected,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) { Text("Start manual drive") }

                        RemoteSessionState.STARTING -> Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) { Text("Connecting to vacuum…") }

                        RemoteSessionState.ACTIVE -> FilledTonalButton(
                            onClick = onStop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) { Text("End manual drive") }

                        RemoteSessionState.STOPPING -> Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                        ) { Text("Stopping safely…") }
                    }
                    if (!connected && state == RemoteSessionState.IDLE) {
                        Text(
                            "Connect to the vacuum over Wi-Fi before starting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            when (mode) {
                RemoteMode.JOYSTICK -> {
                    Text("Touch and drag to steer", style = MaterialTheme.typography.titleLarge)
                    JoystickPad(drive = drive, enabled = state == RemoteSessionState.ACTIVE, onDrive = onDrive)
                    Text(
                        "Push up to move forward. Release to return to neutral.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                RemoteMode.BLE_REMOTE -> ControllerControls(
                    drive = drive,
                    enabled = state == RemoteSessionState.ACTIVE,
                    canConfigure = state == RemoteSessionState.IDLE,
                    invertTurn = invertTurn,
                    invertForward = invertForward,
                    onDrive = onDrive,
                    onInvertTurn = onInvertTurn,
                    onInvertForward = onInvertForward,
                    availableControllers = availableControllers,
                    activeController = activeController,
                    onOpenBluetoothSettings = onOpenBluetoothSettings,
                )
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Text(
                    "Keep the area clear while driving. Ending manual drive stops the wheels and closes the local control session.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Start manual drive?") },
            text = { Text("The vacuum can move as soon as the joystick, phone tilt, or connected controller sends input. Make sure its path is clear.") },
            confirmButton = {
                Button(onClick = {
                    confirmStart = false
                    onStart()
                }) { Text("Start remote control") }
            },
            dismissButton = { TextButton(onClick = { confirmStart = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun JoystickPad(drive: RemoteDriveVector, enabled: Boolean, onDrive: (Float, Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter = maxWidth.coerceAtMost(320.dp).coerceAtLeast(240.dp)
        val density = androidx.compose.ui.platform.LocalDensity.current
        val diameterPx = with(density) { diameter.toPx() }
        val trackRadius = diameterPx * .34f
        Box(
            Modifier
                .size(diameter)
                .semantics { contentDescription = "Remote joystick. Drag to steer the vacuum." }
                .pointerInput(diameterPx, enabled) {
                    if (!enabled) return@pointerInput
                    fun sendAt(position: Offset) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = position.x - center.x
                        val dy = position.y - center.y
                        val magnitude = sqrt(dx * dx + dy * dy)
                        val scale = if (magnitude > trackRadius) trackRadius / magnitude else 1f
                        onDrive(dx * scale / trackRadius, -dy * scale / trackRadius)
                    }
                    detectDragGestures(
                        onDragStart = ::sendAt,
                        onDrag = { change, _ ->
                            change.consume()
                            sendAt(change.position)
                        },
                        onDragEnd = { onDrive(0f, 0f) },
                        onDragCancel = { onDrive(0f, 0f) },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension * .44f
                val knobRange = size.minDimension * .34f
                drawCircle(colors.surfaceContainerHigh, radius = radius, center = center)
                drawCircle(
                    colors.outlineVariant,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
                drawCircle(
                    colors.outlineVariant.copy(alpha = .6f),
                    radius = radius * .52f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
                drawLine(
                    colors.outlineVariant.copy(alpha = .6f),
                    Offset(center.x - radius, center.y), Offset(center.x + radius, center.y),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    colors.outlineVariant.copy(alpha = .6f),
                    Offset(center.x, center.y - radius), Offset(center.x, center.y + radius),
                    strokeWidth = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val knobCenter = Offset(
                    center.x + drive.turn.coerceIn(-1f, 1f) * knobRange,
                    center.y - drive.forward.coerceIn(-1f, 1f) * knobRange,
                )
                drawCircle(
                    colors.primary,
                    radius = size.minDimension * .105f,
                    center = knobCenter,
                )
                drawCircle(
                    colors.onPrimary,
                    radius = size.minDimension * .105f,
                    center = knobCenter,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun ControllerControls(
    drive: RemoteDriveVector,
    enabled: Boolean,
    canConfigure: Boolean,
    invertTurn: Boolean,
    invertForward: Boolean,
    onDrive: (Float, Float) -> Unit,
    onInvertTurn: (Boolean) -> Unit,
    onInvertForward: (Boolean) -> Unit,
    availableControllers: List<String>,
    activeController: String,
    onOpenBluetoothSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("BLE joystick remote", style = MaterialTheme.typography.titleLarge)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Channel direction", style = MaterialTheme.typography.titleMedium)
                InversionRow("Invert forward / reverse", invertForward, canConfigure, onInvertForward)
                InversionRow("Invert left / right", invertTurn, canConfigure, onInvertTurn)
            }
        }
        JoystickPad(drive = drive, enabled = false, onDrive = { _, _ -> })
        Text(
            if (activeController.isNotBlank()) "Input from $activeController"
            else if (availableControllers.isEmpty()) "No Android game controller is connected yet."
            else "Connected: ${availableControllers.joinToString()}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (activeController.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenBluetoothSettings) { Text("Open Bluetooth settings") }
        Text(
            "Pair a BLE HID gamepad, PlayStation or Xbox controller in Android settings. Use a controller mode that Android recognizes as a gamepad; some transmitter BLE joystick modes are simulator-only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            if (enabled) "Left stick: forward and turn · D-pad: forward and turn"
            else "Move the sticks to preview direction. The vacuum stays idle until you start manual drive and confirm.",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun InversionRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

