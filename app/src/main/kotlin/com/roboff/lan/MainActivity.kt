package com.roboff.lan

import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.roboff.lan.auth.RobotProfile
import com.roboff.lan.auth.RobotStore
import com.roboff.lan.discovery.DiscoveredDevice
import com.roboff.lan.discovery.LanDiscovery
import com.roboff.lan.protocol.LocalClient
import com.roboff.lan.ui.ModePreferenceSlider
import com.roboff.lan.ui.MetricTile
import com.roboff.lan.ui.RemoteControlScreen
import com.roboff.lan.ui.RemoteDriveVector
import com.roboff.lan.ui.RemoteMode
import com.roboff.lan.ui.RemoteSessionState
import com.roboff.lan.ui.RobotProgressArt
import com.roboff.lan.ui.theme.RoboffTheme
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private val DPAD_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
)

private enum class QuickCleaningMode(
    val label: String,
    val fanPower: Int,
    val waterBoxMode: Int,
) {
    VAC_AND_MOP("Vac & Mop", fanPower = 102, waterBoxMode = 202),
    VACUUM("Vacuum", fanPower = 102, waterBoxMode = 200),
    MOP("Mop", fanPower = 105, waterBoxMode = 202),
}

/** All vacuum reads and commands use the direct LAN protocol. */
class MainActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val remoteWorker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val requestInFlight = AtomicBoolean(false)
    private val remoteStopRequested = AtomicBoolean(false)
    private val remoteDrive = AtomicReference(RemoteDriveVector.ZERO)
    private val remoteSequence = AtomicInteger(0)
    private lateinit var store: RobotStore
    private var discovery: LanDiscovery? = null
    private var activeClient: LocalClient? = null
    private var activeClientProfileId: String? = null
    private val profiles = mutableStateListOf<RobotProfile>()
    private val found = mutableStateListOf<DiscoveredDevice>()
    private val selectedRooms = mutableStateListOf<Int>()
    private var activeId by mutableStateOf("")
    private var status by mutableStateOf<RobotState?>(null)
    private var connection by mutableStateOf("Searching your local network")
    private var problem by mutableStateOf("")
    private var tab by mutableStateOf("Control")
    private var busy by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var rooms by mutableStateOf<RoomMapping?>(null)
    private var roomUnsupported by mutableStateOf(false)
    private var roomSelectionInitialized = false
    private var roomError by mutableStateOf("")
    private var detailBusy by mutableStateOf(false)
    private var careValues by mutableStateOf<Map<String, String>>(emptyMap())
    private var robotProbe by mutableStateOf<RobotProbe?>(null)
    private var probeLoading by mutableStateOf(false)
    private var repeat by mutableStateOf(1)
    private var selectedQuickAction by mutableStateOf<QuickCleaningMode?>(null)
    private var advancedExpanded by mutableStateOf(false)
    private var showDevices by mutableStateOf(false)
    private var pairDevice by mutableStateOf<DiscoveredDevice?>(null)
    private var showPairDialog by mutableStateOf(false)
    private var confirmPlan by mutableStateOf<RoomCleanPlan?>(null)
    private var roomRenameId by mutableStateOf<Int?>(null)
    private var roomRenameValue by mutableStateOf("")
    private var remoteMode by mutableStateOf(RemoteMode.JOYSTICK)
    private var remoteState by mutableStateOf(RemoteSessionState.IDLE)
    private var driveInput by mutableStateOf(RemoteDriveVector.ZERO)
    private var remoteInvertTurn by mutableStateOf(false)
    private var remoteInvertForward by mutableStateOf(false)
    private var rawBluetoothDrive = RemoteDriveVector.ZERO
    private var availableControllers by mutableStateOf<List<String>>(emptyList())
    private var activeController by mutableStateOf("")
    private var activeControllerId by mutableStateOf(-1)
    @Volatile private var remoteClient: LocalClient? = null
    @Volatile private var remoteWireActive = false
    @Volatile private var remoteTicker: ScheduledFuture<*>? = null
    private val afterRemoteStop = mutableListOf<() -> Unit>()
    private var destroyed = false

    private val poll = object : Runnable {
        override fun run() {
            if (!isStarted) return
            if (activeProfile() != null && !busy && remoteState == RemoteSessionState.IDLE) refreshStatus(silent = true)
            main.postDelayed(this, 8_000)
        }
    }
    private var isStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RobotStore(applicationContext)
        try {
            profiles.addAll(store.load())
        } catch (failure: RobotStore.StoreException) {
            problem = failure.message ?: "Could not load saved local credentials."
        }
        activeId = savedInstanceState?.getString("activeId") ?: profiles.firstOrNull()?.id.orEmpty()
        tab = savedInstanceState?.getString("tab")?.takeIf { it in setOf("Control", "Remote", "Care") } ?: "Control"
        repeat = (savedInstanceState?.getInt("repeat") ?: 1).coerceIn(1, 3)
        getSharedPreferences("remote_control", MODE_PRIVATE).also { preferences ->
            remoteInvertTurn = preferences.getBoolean("invert_turn", false)
            remoteInvertForward = preferences.getBoolean("invert_forward", false)
        }
        if (profiles.isEmpty() && !getSharedPreferences("app_settings", MODE_PRIVATE).getBoolean("local_setup", false)) {
            startActivity(android.content.Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        refreshControllerDevices()

        discovery = LanDiscovery(this, object : LanDiscovery.Listener {
            override fun onDevice(device: DiscoveredDevice?) {
                val item = device ?: return
                val host = item.host.orEmpty()
                val id = item.id.orEmpty()
                val index = found.indexOfFirst { it.host == host }
                if (index < 0) found.add(item) else if (id.isNotBlank() || item.protocol.orEmpty().isNotBlank()) found[index] = item
                val profile = activeProfile()
                if (profile != null && id == profile.id && host != profile.host) {
                    updateAddress(profile, host)
                } else if (profile != null && profile.host.isBlank() && id == profile.id) {
                    updateAddress(profile, host)
                } else if (profile == null) {
                    connection = "Vacuum found on your network"
                }
            }

            override fun onMessage(message: String?) {
                if (activeProfile() == null && found.isEmpty() && !message.isNullOrBlank()) connection = message
            }
        })

        setContent {
            RoboffTheme {
                MainScreen(
                    active = activeProfile(), profiles = profiles, discovered = found, status = status,
                    connection = connection, problem = problem, tab = tab, busy = busy, refreshing = refreshing,
                    rooms = rooms, roomNames = activeProfile()?.let { profile -> rooms?.ids?.associateWith { roomName(profile, rooms!!, it) } }.orEmpty(),
                    roomError = roomError, roomUnsupported = roomUnsupported,
                    detailBusy = detailBusy, selectedRooms = selectedRooms.toSet(),
                    repeat = repeat, careValues = careValues, robotProbe = robotProbe, probeLoading = probeLoading,
                    onTab = ::selectTab,
                    remoteMode = remoteMode, remoteState = remoteState, driveInput = driveInput,
                    remoteInvertTurn = remoteInvertTurn, remoteInvertForward = remoteInvertForward,
                    remoteEnabled = connected() && !busy, availableControllers = availableControllers,
                    activeController = activeController, onRemoteMode = ::selectRemoteMode,
                    onRemoteInvertTurn = ::saveRemoteInvertTurn, onRemoteInvertForward = ::saveRemoteInvertForward,
                    onRemoteDrive = ::setRemoteDrive, onStartRemote = ::startRemoteControl,
                    onStopRemote = { stopRemoteControl() }, onOpenBluetoothSettings = ::openBluetoothSettings,
                    onDevices = { showDevices = true }, onRefresh = { refreshStatus(silent = false) },
                    onImportAccount = { showDevices = false; startActivity(android.content.Intent(this, OnboardingActivity::class.java)) },
                    onCommand = ::command, onSlider = ::setPreference, onQuickAction = ::setQuickAction,
                    onDndEnabled = ::setDndEnabled, onDndSchedule = ::setDndSchedule,
                    onChildLock = ::setChildLock, onVoiceVolume = ::setVoiceVolume,
                    onCarpetBoost = ::setCarpetBoost,
                    selectedQuickAction = selectedQuickAction, advancedExpanded = advancedExpanded,
                    onToggleAdvanced = ::toggleAdvanced,
                    onRoomToggle = { id -> if (id in selectedRooms) selectedRooms.remove(id) else selectedRooms.add(id) },
                    onSelectAllRooms = { rooms?.ids?.let { selectedRooms.clear(); selectedRooms.addAll(it) } },
                    onClearRooms = { selectedRooms.clear() }, onRepeat = { repeat = it.coerceIn(1, 3) },
                    onRenameRoom = { id -> if (id >= 0) beginRenameRoom(id) else roomRenameId = null },
                    roomRenameId = roomRenameId, roomRenameValue = roomRenameValue,
                    onRenameValue = { roomRenameValue = it }, onSaveRoomName = ::saveRoomName,
                    onLoadRooms = ::loadRoomMapping,
                    onLoadDetails = { loadCareDetails(); loadCapabilities(force = true) },
                    onConfirmRoomClean = ::prepareRoomPlan,
                    onSelectProfile = { profile -> selectProfile(profile) }, onAddPairing = { pairDevice = null; showDevices = false; showPairDialog = true },
                    onPairDiscovered = { device -> pairDevice = device; showDevices = false; showPairDialog = true }, onForgetProfile = ::forgetProfile,
                    onEditPairing = { profile -> pairDevice = DiscoveredDevice(profile.id, profile.name, profile.host, LocalClient.PORT, "Saved", ""); showDevices = false; showPairDialog = true },
                    showDevices = showDevices, onDismissDevices = { showDevices = false },
                    pairDevice = pairDevice, onDismissPair = { pairDevice = null; showPairDialog = false }, onSavePair = ::savePair,
                    showPairDialog = showPairDialog,
                    confirmPlan = confirmPlan, onDismissPlan = { confirmPlan = null }, onStartPlan = ::startPlan,
                    onDismissProblem = { problem = "" },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("activeId", activeId)
        outState.putString("tab", tab)
        outState.putInt("repeat", repeat)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        isStarted = true
        inputManager.registerInputDeviceListener(inputDeviceListener, main)
        discovery?.start()
        if (activeProfile() != null) refreshStatus(silent = true)
        if (robotProbe != null) loadCapabilities(force = true)
        refreshControllerDevices()
        main.postDelayed(poll, 8_000)
    }

    override fun onStop() {
        isStarted = false
        main.removeCallbacks(poll)
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        discovery?.stop()
        if (remoteState == RemoteSessionState.IDLE) closeActiveClient() else stopRemoteControl(afterStop = ::closeActiveClient)
        connection = if (activeProfile() == null) "Searching your local network" else "Not connected · retrying locally"
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && remoteState == RemoteSessionState.ACTIVE) stopRemoteControl()
    }

    override fun onDestroy() {
        destroyed = true
        if (remoteState == RemoteSessionState.IDLE) remoteWorker.shutdown()
        else stopRemoteControl(afterStop = { remoteWorker.shutdown() })
        discovery?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            tab == "Remote" && remoteMode == RemoteMode.BLE_REMOTE && event.action == MotionEvent.ACTION_MOVE &&
            (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD))
        ) {
            val device = event.device
            val hasStickX = device?.getMotionRange(MotionEvent.AXIS_X, event.source) != null
            val hasStickY = device?.getMotionRange(MotionEvent.AXIS_Y, event.source) != null
            val leftX = normalizeControllerAxis(
                event.getAxisValue(if (hasStickX) MotionEvent.AXIS_X else MotionEvent.AXIS_HAT_X),
            )
            val y = normalizeControllerAxis(
                event.getAxisValue(if (hasStickY) MotionEvent.AXIS_Y else MotionEvent.AXIS_HAT_Y),
            )
            activeController = device?.name.orEmpty()
            activeControllerId = device?.id ?: -1
            // Android's conventional joystick Y axis is negative when pushed up.
            setBluetoothDrive(leftX, -y)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDpad = event.isFromSource(InputDevice.SOURCE_DPAD) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
        if (
            isDpad && tab == "Remote" && remoteMode == RemoteMode.BLE_REMOTE && event.keyCode in DPAD_KEYS
        ) {
            val pressed = event.action != KeyEvent.ACTION_UP
            if (pressed) pressedDpadKeys.add(event.keyCode) else pressedDpadKeys.remove(event.keyCode)
            activeController = event.device?.name.orEmpty().ifBlank { "Game controller" }
            activeControllerId = event.device?.id ?: -1
            val horizontal = (if (KeyEvent.KEYCODE_DPAD_RIGHT in pressedDpadKeys) 1 else 0) -
                (if (KeyEvent.KEYCODE_DPAD_LEFT in pressedDpadKeys) 1 else 0)
            val vertical = (if (KeyEvent.KEYCODE_DPAD_UP in pressedDpadKeys) 1 else 0) -
                (if (KeyEvent.KEYCODE_DPAD_DOWN in pressedDpadKeys) 1 else 0)
            setBluetoothDrive(horizontal.toFloat(), vertical.toFloat())
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private val pressedDpadKeys = mutableSetOf<Int>()
    private val inputManager by lazy { getSystemService(INPUT_SERVICE) as InputManager }
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshControllerDevices()

        override fun onInputDeviceChanged(deviceId: Int) = refreshControllerDevices()

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (activeControllerId == deviceId) {
                activeControllerId = -1
                activeController = ""
                pressedDpadKeys.clear()
                rawBluetoothDrive = RemoteDriveVector.ZERO
                setRemoteDrive(0f, 0f)
            }
            refreshControllerDevices()
        }
    }

    private fun refreshControllerDevices() {
        availableControllers = InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .filter { device ->
                !device.isVirtual && (
                    device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                        device.supportsSource(InputDevice.SOURCE_JOYSTICK) ||
                        device.supportsSource(InputDevice.SOURCE_DPAD)
                    )
            }
            .map(InputDevice::getName)
            .distinct()
            .toList()
    }

    private fun closeActiveClient() {
        activeClient?.close()
        activeClient = null
        activeClientProfileId = null
        remoteClient = null
    }

    private fun selectRemoteMode(mode: RemoteMode) {
        if (remoteMode == mode) return
        remoteMode = mode
        pressedDpadKeys.clear()
        rawBluetoothDrive = RemoteDriveVector.ZERO
        setRemoteDrive(0f, 0f)
    }

    private fun saveRemoteInvertTurn(invert: Boolean) {
        if (remoteState != RemoteSessionState.IDLE) return
        remoteInvertTurn = invert
        getSharedPreferences("remote_control", MODE_PRIVATE).edit().putBoolean("invert_turn", invert).apply()
        setBluetoothDrive(rawBluetoothDrive.turn, rawBluetoothDrive.forward)
    }

    private fun saveRemoteInvertForward(invert: Boolean) {
        if (remoteState != RemoteSessionState.IDLE) return
        remoteInvertForward = invert
        getSharedPreferences("remote_control", MODE_PRIVATE).edit().putBoolean("invert_forward", invert).apply()
        setBluetoothDrive(rawBluetoothDrive.turn, rawBluetoothDrive.forward)
    }

    private fun setBluetoothDrive(rawTurn: Float, rawForward: Float) {
        val raw = RemoteDriveVector(rawTurn.coerceIn(-1f, 1f), rawForward.coerceIn(-1f, 1f))
        rawBluetoothDrive = raw
        setRemoteDrive(
            if (remoteInvertTurn) -raw.turn else raw.turn,
            if (remoteInvertForward) -raw.forward else raw.forward,
        )
    }

    private fun setRemoteDrive(turn: Float, forward: Float) {
        val vector = RemoteDriveVector(turn.coerceIn(-1f, 1f), forward.coerceIn(-1f, 1f))
        driveInput = vector
        remoteDrive.set(vector)
    }

    private fun openBluetoothSettings() {
        try {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (_: Exception) {
            problem = "Android Bluetooth settings are not available on this device."
        }
    }

    private fun startRemoteControl() {
        if (remoteState != RemoteSessionState.IDLE) return
        val profile = activeProfile() ?: return
        val client = activeClient
        if (client == null || !client.isConnected() || activeClientProfileId != profile.id) {
            problem = "Connect to the vacuum over local Wi-Fi before starting remote control."
            return
        }

        pressedDpadKeys.clear()
        rawBluetoothDrive = RemoteDriveVector.ZERO
        setRemoteDrive(0f, 0f)
        remoteStopRequested.set(false)
        remoteWireActive = false
        remoteClient = client
        remoteSequence.set(0)
        remoteState = RemoteSessionState.STARTING

        remoteWorker.execute {
            try {
                val response = client.request("app_rc_start", JSONArray())
                if (!isAcknowledged(response)) throw IOException("The vacuum did not start manual control.")
                remoteWireActive = true
                if (remoteStopRequested.get()) {
                    val failure = finishRemoteWireSession(client)
                    finishRemoteUi(failure)
                    return@execute
                }
                main.post {
                    if (activeId == profile.id && remoteState == RemoteSessionState.STARTING) {
                        remoteState = RemoteSessionState.ACTIVE
                    }
                }
                remoteTicker = remoteWorker.scheduleWithFixedDelay(
                    {
                        if (remoteStopRequested.get()) {
                            remoteTicker?.cancel(false)
                            finishRemoteUi(finishRemoteWireSession(client))
                        } else sendRemoteVector(client)
                    },
                    300,
                    300,
                    TimeUnit.MILLISECONDS,
                )
            } catch (failure: Exception) {
                remoteWireActive = false
                finishRemoteUi(failure.message ?: "Could not start manual control over the local network.")
            }
        }
    }

    private fun sendRemoteVector(client: LocalClient) {
        if (remoteStopRequested.get()) return
        try {
            val vector = remoteDrive.get()
            val params = JSONArray().put(
                JSONObject()
                    .put("omega", vector.turn * -Math.PI)
                    .put("velocity", vector.forward * .3)
                    .put("duration", 300)
                    .put("seqnum", remoteSequence.incrementAndGet()),
            )
            val response = client.request("app_rc_move", params)
            if (!isAcknowledged(response)) throw IOException("The vacuum did not confirm a motion update.")
        } catch (failure: Exception) {
            remoteStopRequested.set(true)
            remoteTicker?.cancel(false)
            val cleanupFailure = finishRemoteWireSession(client)
            val detail = failure.message ?: "The local connection was lost."
            finishRemoteUi(cleanupFailure ?: "Remote control stopped after a connection error: $detail")
        }
    }

    private fun stopRemoteControl(afterStop: (() -> Unit)? = null) {
        if (afterStop != null) afterRemoteStop.add(afterStop)
        pressedDpadKeys.clear()
        rawBluetoothDrive = RemoteDriveVector.ZERO
        setRemoteDrive(0f, 0f)
        if (remoteState == RemoteSessionState.IDLE) {
            runAfterRemoteStop()
            return
        }

        remoteStopRequested.set(true)
        if (remoteState == RemoteSessionState.STARTING) {
            remoteState = RemoteSessionState.STOPPING
            return
        }
        if (remoteState == RemoteSessionState.STOPPING) return

        remoteState = RemoteSessionState.STOPPING
        remoteTicker?.cancel(false)
        val client = remoteClient
        remoteWorker.execute {
            val failure = finishRemoteWireSession(client)
            finishRemoteUi(failure)
        }
    }

    private fun finishRemoteWireSession(client: LocalClient?): String? {
        if (!remoteWireActive) return null
        remoteWireActive = false
        if (client == null || !client.isConnected()) {
            return "Remote control ended after the local connection was lost."
        }
        var error: String? = null
        listOf("app_rc_stop", "app_rc_end").forEach { method ->
            try {
                val response = client.request(method, JSONArray())
                if (!isAcknowledged(response)) throw IOException("The vacuum did not confirm $method.")
            } catch (failure: Exception) {
                if (error == null) error = failure.message ?: "The vacuum did not confirm $method."
            }
        }
        return error?.let { "Manual control ended with a local stop error: $it" }
    }

    private fun finishRemoteUi(failure: String?) {
        remoteTicker = null
        remoteClient = null
        main.post {
            remoteState = RemoteSessionState.IDLE
            setRemoteDrive(0f, 0f)
            activeController = ""
            activeControllerId = -1
            if (!failure.isNullOrBlank()) problem = failure
            runAfterRemoteStop()
            if (tab == "Remote" && isStarted) refreshStatus(silent = true)
            if (destroyed) remoteWorker.shutdown()
        }
    }

    private fun runAfterRemoteStop() {
        if (afterRemoteStop.isEmpty()) return
        val callbacks = afterRemoteStop.toList()
        afterRemoteStop.clear()
        callbacks.forEach { it() }
    }

    private fun normalizeControllerAxis(value: Float): Float {
        val clamped = value.coerceIn(-1f, 1f)
        val magnitude = kotlin.math.abs(clamped)
        if (magnitude <= .12f) return 0f
        return (if (clamped < 0f) -1f else 1f) * ((magnitude - .12f) / .88f).coerceIn(0f, 1f)
    }

    private fun activeProfile(): RobotProfile? = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    private fun updateAddress(profile: RobotProfile, host: String) {
        if (profile.host == host || host.isBlank()) return
        val updated = profile.withHost(host)
        try {
            store.save(updated)
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index >= 0) profiles[index] = updated
            connection = "Vacuum found · connecting locally"
            refreshStatus(silent = true)
        } catch (failure: RobotStore.StoreException) {
            problem = failure.message ?: "Could not save the vacuum address."
        }
    }

    private fun selectProfile(profile: RobotProfile) {
        if (remoteState != RemoteSessionState.IDLE) {
            stopRemoteControl { selectProfileNow(profile) }
            return
        }
        selectProfileNow(profile)
    }

    private fun selectProfileNow(profile: RobotProfile) {
        activeClient?.close()
        activeClient = null
        activeClientProfileId = null
        activeId = profile.id
        status = null
        robotProbe = null
        probeLoading = false
        rooms = null
        roomUnsupported = false
        roomSelectionInitialized = false
        selectedQuickAction = null
        advancedExpanded = false
        roomError = ""
        detailBusy = false
        busy = false
        selectedRooms.clear()
        connection = if (profile.hasAddress()) "Connecting over local Wi-Fi" else "Waiting for this vacuum on your network"
        showDevices = false
        refreshStatus(silent = true)
    }

    private fun selectTab(value: String) {
        if (tab == value) return
        val leavingRemote = tab == "Remote" && value != "Remote"
        tab = value
        if (leavingRemote) stopRemoteControl()
        when (value) {
            "Control" -> loadRoomMapping()
            "Care" -> { loadCareDetails(); loadCapabilities(force = true) }
        }
    }

    private fun connected(): Boolean = activeClient?.isConnected() == true

    private fun refreshStatus(silent: Boolean) {
        val profile = activeProfile() ?: return
        if (!requestInFlight.compareAndSet(false, true)) return
        refreshing = true
        if (!silent && !connected()) connection = "Connecting over local Wi-Fi"
        worker.execute {
            try {
                val client = getConnectedClient(profile)
                val result = client.request("get_status")
                val next = RobotState.from(result)
                main.post {
                    if (activeId == profile.id) {
                        if (next.valid) status = next
                        connection = if (client.isConnected()) "Connected directly over Wi-Fi" else "Disconnected · retrying locally"
                        if (problem.isNotBlank()) problem = ""
                    }
                }
            } catch (failure: Exception) {
                main.post {
                    if (activeId == profile.id) {
                        connection = "Disconnected · retrying locally"
                        if (!silent) problem = failure.message ?: "Could not reach the vacuum on your local network."
                    }
                }
            } finally {
                requestInFlight.set(false)
                main.post {
                    refreshing = false
                    if (activeId == profile.id && tab == "Control" && rooms == null && !roomUnsupported && !detailBusy) loadRoomMapping()
                    if (activeId == profile.id && connected() && status?.valid == true && robotProbe == null && !probeLoading) {
                        loadCapabilities()
                    }
                }
            }
        }
    }

    private fun getConnectedClient(profile: RobotProfile): LocalClient {
        if (profile.host.isBlank()) throw IOException("Waiting for ${profile.name} to appear on the local network.")
        val current = activeClient
        if (current != null && activeClientProfileId == profile.id && current.isConnected()) return current
        current?.close()
        val client = LocalClient(profile.host, profile.localKey)
        activeClient = client
        activeClientProfileId = profile.id
        client.connect()
        return client
    }

    private fun command(method: String, params: Any) {
        val profile = activeProfile() ?: return
        if (method == "resume_segment_clean" && status?.isPausedSegmentClean() != true) return
        if (busy || !connected()) {
            problem = if (profile.host.isBlank()) "Wait for the vacuum to appear on your Wi-Fi, then retry." else "The vacuum is not connected yet."
            return
        }
        runCommand(profile, method, params, expectOk = true)
    }

    private fun setPreference(method: String, value: Int) {
        selectedQuickAction = null
        command(method, JSONArray().put(value))
    }

    private fun toggleAdvanced() {
        advancedExpanded = !advancedExpanded
        if (advancedExpanded) selectedQuickAction = null
    }

    private fun runCommand(profile: RobotProfile, method: String, params: Any, expectOk: Boolean) {
        runCommandSequence(profile, listOf(method to params), expectOk)
    }

    private fun setQuickAction(mode: QuickCleaningMode) {
        advancedExpanded = false
        selectedQuickAction = null
        val profile = activeProfile() ?: return
        if (status?.isCleaning() == true) {
            problem = "Stop the current cleaning session before changing its cleaning mode."
            return
        }
        if (busy || !connected()) {
            problem = if (profile.host.isBlank()) "Wait for the vacuum to appear on your Wi-Fi, then retry." else "The vacuum is not connected yet."
            return
        }
        if (robotProbe?.combinedCleaningMode != true || status?.fan?.let { it >= 0 } != true ||
            status?.water?.let { it >= 0 } != true) return

        val modeParams = JSONArray().put(
            JSONObject()
                .put("fan_power", mode.fanPower)
                .put("water_box_mode", mode.waterBoxMode)
                .put("mop_mode", 300),
        )
        runCommandSequence(
            profile,
            listOf("set_clean_motor_mode" to modeParams),
            expectOk = true,
            onSuccess = { if (activeId == profile.id) selectedQuickAction = mode },
        )
    }

    private fun runCommandSequence(
        profile: RobotProfile,
        commands: List<Pair<String, Any>>,
        expectOk: Boolean,
        onSuccess: () -> Unit = {},
    ) {
        if (busy || !requestInFlight.compareAndSet(false, true)) return
        busy = true
        worker.execute {
            try {
                val client = getConnectedClient(profile)
                commands.forEach { (method, params) ->
                    val response = client.request(method, params)
                    if (expectOk && !isAcknowledged(response)) {
                        throw IOException("The vacuum did not confirm $method.")
                    }
                }
                main.post {
                    if (activeId == profile.id) {
                        busy = false
                        connection = "Connected directly over Wi-Fi"
                        onSuccess()
                    }
                }
                main.postDelayed({ if (activeId == profile.id) refreshStatus(silent = true) }, 450)
            } catch (failure: Exception) {
                main.post {
                    if (activeId == profile.id) {
                        busy = false
                        connection = "Disconnected · retrying locally"
                        problem = failure.message ?: "The local command failed."
                    }
                }
            } finally {
                requestInFlight.set(false)
            }
        }
    }

    private fun loadRoomMapping() = loadDetails(loadRooms = true, loadCare = false)

    private fun loadCareDetails() = loadDetails(loadRooms = false, loadCare = true)

    /** Query only read-only methods here; a failed getter leaves that setting absent from the UI. */
    private fun loadCapabilities(force: Boolean = false) {
        val profile = activeProfile() ?: return
        if (probeLoading || (!force && robotProbe != null)) return
        probeLoading = true
        worker.execute {
            try {
                val client = getConnectedClient(profile)
                val result = RobotCapabilityProbe.read(client)
                if (!client.isConnected()) throw IOException("The vacuum disconnected during capability discovery.")
                main.post {
                    if (activeId == profile.id) {
                        robotProbe = result
                        probeLoading = false
                    }
                }
            } catch (_: Exception) {
                main.post { if (activeId == profile.id) probeLoading = false }
            }
        }
    }

    private fun setRobotSetting(method: String, params: Any) {
        val profile = activeProfile() ?: return
        if (busy || !connected()) return
        runCommandSequence(profile, listOf(method to params), expectOk = true) {
            loadCapabilities(force = true)
        }
    }

    private fun setDndEnabled(enabled: Boolean) {
        val current = robotProbe?.settings?.dnd ?: return
        if (enabled) setRobotSetting("set_dnd_timer", current.scheduleParams())
        else setRobotSetting("close_dnd_timer", JSONArray())
    }

    private fun setDndSchedule(setting: DndSetting) {
        if (robotProbe?.settings?.dnd == null) return
        setRobotSetting("set_dnd_timer", setting.scheduleParams())
    }

    private fun setChildLock(enabled: Boolean) {
        if (robotProbe?.settings?.childLock == null) return
        setRobotSetting("set_child_lock_status", JSONObject().put("lock_status", if (enabled) 1 else 0))
    }

    private fun setVoiceVolume(volume: Int) {
        if (robotProbe?.settings?.volume == null) return
        setRobotSetting("change_sound_volume", JSONArray().put(volume.coerceIn(0, 100)))
    }

    private fun setCarpetBoost(enabled: Boolean) {
        val current = robotProbe?.settings?.carpet ?: return
        setRobotSetting("set_carpet_mode", current.params(enabled))
    }

    private fun loadDetails(loadRooms: Boolean, loadCare: Boolean) {
        val profile = activeProfile() ?: return
        if ((!loadRooms && !loadCare) || detailBusy || busy || !requestInFlight.compareAndSet(false, true)) return
        detailBusy = true
        if (loadRooms) roomError = ""
        worker.execute {
            try {
                val client = getConnectedClient(profile)
                val roomResult = if (loadRooms) client.request("get_room_mapping") else null
                val counters = if (loadCare) asObject(client.request("get_consumable")) else null
                val summary = if (loadCare) client.request("get_clean_summary") else null
                val mapping = if (roomResult != null) RoomMapping.parse(roomResult) else null
                val care = if (loadCare) parseCare(counters, summary) else emptyMap()
                main.post {
                    if (activeId == profile.id) {
                        if (mapping != null) {
                            rooms = mapping
                            roomUnsupported = false
                            selectedRooms.removeAll { it !in mapping.ids }
                            if (!roomSelectionInitialized) {
                                selectedRooms.addAll(mapping.ids)
                                roomSelectionInitialized = true
                            }
                        }
                        if (loadCare) careValues = care
                        detailBusy = false
                    }
                }
            } catch (failure: Exception) {
                main.post {
                    if (activeId == profile.id) {
                        detailBusy = false
                        if (loadRooms && failure is LocalClient.RpcException && failure.code == -32601) {
                            roomUnsupported = true
                            roomError = ""
                        } else {
                            roomError = if (loadRooms) failure.message ?: "Could not load rooms from the vacuum." else "Could not load maintenance information."
                        }
                    }
                }
            } finally {
                requestInFlight.set(false)
            }
        }
    }

    private fun prepareRoomPlan() {
        val profile = activeProfile() ?: return
        if (status?.isCleaning() == true || status?.isPaused() == true) return
        val mapping = rooms ?: return
        val labels = mapping.ids.associateWith { roomName(profile, mapping, it) }
        try {
            confirmPlan = RoomCleanPlan.create(profile.id, mapping.ids, labels, selectedRooms.toSet(), repeat)
        } catch (failure: IllegalArgumentException) {
            problem = failure.message ?: "Choose at least one available room."
        }
    }

    private fun startPlan() {
        val plan = confirmPlan ?: return
        confirmPlan = null
        val profile = activeProfile() ?: return
        if (status?.isCleaning() == true || status?.isPaused() == true) {
            problem = "Finish or resume the current cleaning session first."
            return
        }
        if (profile.id != plan.robotId || rooms?.ids?.containsAll(plan.roomIds) != true) {
            problem = "The vacuum or room list changed. Review your selection and try again."
            return
        }
        try {
            runCommand(profile, "app_segment_clean", plan.params(), expectOk = true)
        } catch (failure: JSONException) {
            problem = "Could not build the room cleaning request."
        }
    }

    private fun savePair(device: DiscoveredDevice?, name: String, host: String, localKey: String) {
        val address = host.trim().ifBlank { device?.host.orEmpty() }
        val key = localKey.trim()
        try {
            val id = device?.id?.takeIf(String::isNotBlank) ?: profiles.firstOrNull { it.host == address }?.id ?: "local-${address.replace('.', '-') }"
            val profileName = name.trim().ifBlank { device?.name ?: "Roborock" }
            val current = profiles.firstOrNull { it.id == id }
            val profile = RobotProfile(
                id = id,
                name = profileName,
                host = address,
                localKey = if (key.isEmpty() && current != null) current.localKey else key,
                model = current?.model ?: "",
                homeId = current?.homeId ?: "",
                homeName = current?.homeName ?: "",
                roomNames = current?.roomNames ?: emptyMap(),
            )
            store.save(profile)
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index >= 0) profiles[index] = profile else profiles.add(profile)
            activeId = profile.id
            status = null
            connection = "Connecting over local Wi-Fi"
            pairDevice = null
            showPairDialog = false
            showDevices = false
            refreshStatus(silent = true)
        } catch (failure: Exception) {
            problem = failure.message ?: "Could not save this local pairing."
        }
    }

    private fun forgetProfile(profile: RobotProfile) {
        if (activeId == profile.id && remoteState != RemoteSessionState.IDLE) {
            stopRemoteControl { forgetProfileNow(profile) }
            return
        }
        forgetProfileNow(profile)
    }

    private fun forgetProfileNow(profile: RobotProfile) {
        try {
            if (activeId == profile.id) {
                closeActiveClient()
            }
            store.remove(profile.id)
            profiles.removeAll { it.id == profile.id }
            if (activeId == profile.id) activeId = profiles.firstOrNull()?.id.orEmpty()
            status = null
            showDevices = false
            if (profiles.isEmpty()) connection = "Choose a vacuum on your network to pair it"
        } catch (failure: Exception) {
            problem = failure.message ?: "Could not forget this vacuum."
        }
    }

    private fun parseCare(counters: JSONObject?, summary: Any?): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val keys = listOf(
            "main_brush_work_time" to "Main brush", "side_brush_work_time" to "Side brush",
            "filter_work_time" to "Filter", "sensor_dirty_time" to "Sensors", "strainer_work_times" to "Mop",
        )
        keys.forEach { (key, label) ->
            val seconds = counters?.optLong(key, -1) ?: -1
            if (seconds >= 0) result[label] = formatDuration(seconds)
        }
        val summaryObject = asObject(summary)
        val summaryArray = summary as? JSONArray
        fun summaryNumber(key: String, index: Int): Long = summaryObject?.optLong(key, -1)
            ?: (summaryArray?.opt(index) as? Number)?.toLong()
            ?: (if (index == 0) (summary as? Number)?.toLong() else null) ?: -1
        val totalArea = summaryNumber("clean_area", 1)
        val totalTime = summaryNumber("clean_time", 0)
        val totalCount = summaryNumber("clean_count", 2)
        if (totalCount >= 0) result["Cleaning runs"] = totalCount.toString()
        if (totalArea >= 0) result["Total cleaned"] = String.format("%.1f m²", totalArea / 1_000_000.0)
        if (totalTime >= 0) result["Cleaning time"] = formatDuration(totalTime)
        return result
    }

    private fun formatDuration(seconds: Long): String {
        val days = seconds / 86_400
        val hours = seconds % 86_400 / 3_600
        val minutes = seconds % 3_600 / 60
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h $minutes min"
            else -> "$minutes min"
        }
    }

    private fun roomName(profile: RobotProfile, mapping: RoomMapping, id: Int): String {
        val cloudId = mapping.cloudIds[id]
        val cloudName = cloudId?.let(profile.roomNames::get)
        val localName = getPreferences(MODE_PRIVATE).getString("room.${profile.id}.$id", null)
        return localName?.takeIf(String::isNotBlank) ?: cloudName?.takeIf(String::isNotBlank) ?: "Room $id"
    }

    private fun beginRenameRoom(id: Int) {
        val profile = activeProfile() ?: return
        val mapping = rooms ?: return
        roomRenameId = id
        roomRenameValue = roomName(profile, mapping, id)
    }

    private fun saveRoomName() {
        val profile = activeProfile() ?: return
        val id = roomRenameId ?: return
        val value = roomRenameValue.trim()
        if (value.isEmpty()) {
            problem = "Enter a room name."
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString("room.${profile.id}.$id", value).apply()
        roomRenameId = null
    }

    private fun asObject(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is JSONArray -> value.optJSONObject(0)
        else -> null
    }

    private fun isAcknowledged(value: Any?): Boolean = value == "ok" || (value is JSONArray && value.length() == 1 && value.opt(0) == "ok")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    active: RobotProfile?, profiles: List<RobotProfile>, discovered: List<DiscoveredDevice>, status: RobotState?,
    connection: String, problem: String, tab: String, busy: Boolean, refreshing: Boolean,
    rooms: RoomMapping?, roomNames: Map<Int, String>, roomError: String, roomUnsupported: Boolean,
    detailBusy: Boolean, selectedRooms: Set<Int>, repeat: Int,
    careValues: Map<String, String>, robotProbe: RobotProbe?, probeLoading: Boolean,
    onTab: (String) -> Unit, onDevices: () -> Unit, onRefresh: () -> Unit,
    onImportAccount: () -> Unit,
    remoteMode: RemoteMode, remoteState: RemoteSessionState, driveInput: RemoteDriveVector,
    remoteInvertTurn: Boolean, remoteInvertForward: Boolean,
    remoteEnabled: Boolean, availableControllers: List<String>, activeController: String,
    onRemoteMode: (RemoteMode) -> Unit, onRemoteInvertTurn: (Boolean) -> Unit,
    onRemoteInvertForward: (Boolean) -> Unit, onRemoteDrive: (Float, Float) -> Unit,
    onStartRemote: () -> Unit, onStopRemote: () -> Unit, onOpenBluetoothSettings: () -> Unit,
    onCommand: (String, Any) -> Unit, onSlider: (String, Int) -> Unit,
    onDndEnabled: (Boolean) -> Unit, onDndSchedule: (DndSetting) -> Unit,
    onChildLock: (Boolean) -> Unit, onVoiceVolume: (Int) -> Unit, onCarpetBoost: (Boolean) -> Unit,
    onQuickAction: (QuickCleaningMode) -> Unit, selectedQuickAction: QuickCleaningMode?, advancedExpanded: Boolean,
    onToggleAdvanced: () -> Unit, onRoomToggle: (Int) -> Unit,
    onSelectAllRooms: () -> Unit, onClearRooms: () -> Unit, onRepeat: (Int) -> Unit,
    onLoadRooms: () -> Unit, onLoadDetails: () -> Unit,
    onConfirmRoomClean: () -> Unit, onRenameRoom: (Int) -> Unit, roomRenameId: Int?, roomRenameValue: String,
    onRenameValue: (String) -> Unit, onSaveRoomName: () -> Unit,
    onSelectProfile: (RobotProfile) -> Unit, onAddPairing: () -> Unit,
    onPairDiscovered: (DiscoveredDevice) -> Unit, onForgetProfile: (RobotProfile) -> Unit, onEditPairing: (RobotProfile) -> Unit,
    showDevices: Boolean, onDismissDevices: () -> Unit, pairDevice: DiscoveredDevice?, onDismissPair: () -> Unit,
    onSavePair: (DiscoveredDevice?, String, String, String) -> Unit, confirmPlan: RoomCleanPlan?,
    onDismissPlan: () -> Unit, onStartPlan: () -> Unit, onDismissProblem: () -> Unit, showPairDialog: Boolean,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Roboff", style = MaterialTheme.typography.titleLarge)
                },
                actions = { TextButton(onClick = onDevices) { Text(if (active == null) "Add vacuum" else "Devices") } },
            )
        },
        bottomBar = {
            if (active != null) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                listOf("Control", "Remote", "Care").forEach { item ->
                    val icons = when (item) {
                        "Control" -> Icons.Outlined.SmartToy to Icons.Filled.SmartToy
                        "Remote" -> Icons.Outlined.Gamepad to Icons.Filled.Gamepad
                        else -> Icons.Outlined.Build to Icons.Filled.Build
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { onTab(item) },
                        icon = { Icon(if (tab == item) icons.second else icons.first, contentDescription = null, modifier = Modifier.size(24.dp).hopOnSelect(tab == item)) },
                        label = { Text(item) },
                    )
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            if (active != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(active.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                HorizontalDivider()
            }
            AnimatedContent(
                targetState = if (active == null) "Welcome" else tab,
                transitionSpec = {
                    (fadeIn(tween(durationMillis = 260)) + scaleIn(
                        animationSpec = spring(dampingRatio = 0.9f, stiffness = 360f),
                        initialScale = 0.96f,
                    )).togetherWith(fadeOut(tween(durationMillis = 150)))
                },
                label = "main section",
                modifier = Modifier.weight(1f),
            ) { screen ->
                when (screen) {
                    "Welcome" -> WelcomeContent(connection, discovered, onDevices, onPairDiscovered)
                    "Care" -> CareContent(
                        values = careValues, loading = detailBusy, probe = robotProbe,
                        probeLoading = probeLoading, busy = busy, onRefresh = onLoadDetails,
                        onDndEnabled = onDndEnabled, onDndSchedule = onDndSchedule,
                        onChildLock = onChildLock, onVoiceVolume = onVoiceVolume,
                        onCarpetBoost = onCarpetBoost,
                    )
                    "Remote" -> RemoteControlScreen(
                        mode = remoteMode,
                        onMode = onRemoteMode,
                        state = remoteState,
                        connected = remoteEnabled,
                        drive = driveInput,
                        invertTurn = remoteInvertTurn,
                        invertForward = remoteInvertForward,
                        onDrive = onRemoteDrive,
                        onInvertTurn = onRemoteInvertTurn,
                        onInvertForward = onRemoteInvertForward,
                        onStart = onStartRemote,
                        onStop = onStopRemote,
                        availableControllers = availableControllers,
                        activeController = activeController,
                        onOpenBluetoothSettings = onOpenBluetoothSettings,
                    )
                    else -> ControlContent(
                        active = active!!,
                        status = status,
                        probe = robotProbe,
                        connection = connection,
                        busy = busy,
                        refreshing = refreshing,
                        onRefresh = onRefresh,
                        onCommand = onCommand,
                        onSlider = onSlider,
                        onQuickAction = onQuickAction,
                        selectedQuickAction = selectedQuickAction,
                        advancedExpanded = advancedExpanded,
                        onToggleAdvanced = onToggleAdvanced,
                        rooms = rooms,
                        roomNames = roomNames,
                        roomError = roomError,
                        roomUnsupported = roomUnsupported,
                        roomsLoading = detailBusy,
                        selectedRooms = selectedRooms,
                        repeat = repeat,
                        onRoomToggle = onRoomToggle,
                        onSelectAllRooms = onSelectAllRooms,
                        onClearRooms = onClearRooms,
                        onRepeat = onRepeat,
                        onLoadRooms = onLoadRooms,
                        onConfirmRoomClean = onConfirmRoomClean,
                        onRenameRoom = onRenameRoom,
                    )
                }
            }
        }
    }
    if (showDevices) DevicePickerDialog(profiles, discovered, onSelectProfile, onAddPairing, onPairDiscovered, onEditPairing, onForgetProfile, onImportAccount, onDismissDevices)
    if (showPairDialog) {
        PairDialog(pairDevice, onDismissPair) { name, host, key -> onSavePair(pairDevice, name, host, key) }
    }
    if (confirmPlan != null) {
        AlertDialog(
            onDismissRequest = onDismissPlan,
            title = { Text(if (confirmPlan.roomIds.size == 1) "Clean this room?" else "Clean ${confirmPlan.roomIds.size} rooms?") },
            text = { Text(confirmPlan.roomNames.joinToString("\n• ", prefix = "• ") + "\n\n" + confirmPlan.summary()) },
            confirmButton = { Button(onClick = onStartPlan) { Text("Start cleaning") } },
            dismissButton = { TextButton(onClick = onDismissPlan) { Text("Cancel") } },
        )
    }
    if (roomRenameId != null) {
        AlertDialog(
            onDismissRequest = { onRenameRoom(-1) },
            title = { Text("Rename room") },
            text = { OutlinedTextField(roomRenameValue, onRenameValue, label = { Text("Room name") }, singleLine = true) },
            confirmButton = { Button(onClick = onSaveRoomName) { Text("Save") } },
            dismissButton = { TextButton(onClick = { onRenameRoom(-1) }) { Text("Cancel") } },
        )
    }
    if (problem.isNotBlank()) {
        AlertDialog(onDismissRequest = onDismissProblem, title = { Text("Local connection") }, text = { Text(problem) }, confirmButton = { TextButton(onClick = onDismissProblem) { Text("OK") } })
    }
}

/** Gives the active destination icon a quick spring hop. */
private fun Modifier.hopOnSelect(selected: Boolean): Modifier = composed {
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(selected) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        if (selected) {
            scale.snapTo(0.7f)
            scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium))
        }
    }
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

@Composable
private fun WelcomeContent(connection: String, discovered: List<DiscoveredDevice>, onDevices: () -> Unit, onPair: (DiscoveredDevice) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Your home. Your connection.", style = MaterialTheme.typography.headlineMedium)
                Text("Connect directly to your vacuum over Wi-Fi. Controls and credentials stay on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(connection, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("NEARBY VACUUMS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (discovered.isEmpty()) Text("Listening for Roborock vacuums on this network…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                discovered.forEach { device ->
                    Row(Modifier.fillMaxWidth().clickable { onPair(device) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(device.name ?: "Roborock", style = MaterialTheme.typography.titleMedium); Text(device.host ?: "", style = MaterialTheme.typography.bodySmall) }
                        Text("Pair", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Button(onClick = onDevices, modifier = Modifier.fillMaxWidth()) { Text("Add local key") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One key, entered here", style = MaterialTheme.typography.titleLarge)
                Text("Discovery never retrieves your key. Enter the existing 16-byte local key once to pair this vacuum.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ControlContent(
    active: RobotProfile,
    status: RobotState?,
    probe: RobotProbe?,
    connection: String,
    busy: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onCommand: (String, Any) -> Unit,
    onSlider: (String, Int) -> Unit,
    onQuickAction: (QuickCleaningMode) -> Unit,
    selectedQuickAction: QuickCleaningMode?,
    advancedExpanded: Boolean,
    onToggleAdvanced: () -> Unit,
    rooms: RoomMapping?,
    roomNames: Map<Int, String>,
    roomError: String,
    roomUnsupported: Boolean,
    roomsLoading: Boolean,
    selectedRooms: Set<Int>,
    repeat: Int,
    onRoomToggle: (Int) -> Unit,
    onSelectAllRooms: () -> Unit,
    onClearRooms: () -> Unit,
    onRepeat: (Int) -> Unit,
    onLoadRooms: () -> Unit,
    onConfirmRoomClean: () -> Unit,
    onRenameRoom: (Int) -> Unit,
) {
    val hasKnownState = status?.state?.let { it > 0 } == true
    val cleaning = status?.isCleaning() == true
    val paused = status?.isPaused() == true
    val pausedSegment = status?.isPausedSegmentClean() == true
    val taskActive = cleaning || paused
    val s8Model = active.model.substringAfterLast('.') in setOf("a50", "a51")
    val fanAvailable = status?.fan?.let { it >= 0 && (s8Model || it in setOf(101, 102, 103, 104, 105, 108)) } == true
    val waterAvailable = status?.water?.let { it >= 0 && (s8Model || it in 200..203) } == true
    val mopRouteAvailable = status?.mop?.let { it in setOf(300, 301, 303) } == true ||
        probe?.features?.mopPath == true || probe?.features?.shakeMop == true
    val preferencesAvailable = probe != null && (fanAvailable || waterAvailable || mopRouteAvailable)
    val quickActionsAvailable = probe?.combinedCleaningMode == true && fanAvailable && waterAvailable
    var selectedMopRoute by remember(active.id) { mutableStateOf(status?.mop ?: -1) }
    LaunchedEffect(status) {
        selectedMopRoute = status?.mop ?: -1
    }
    val reportedMopRoute = selectedMopRoute.takeIf { it >= 0 } ?: status?.mop ?: -1
    val deepRouteActive = status?.mop == 301 || selectedMopRoute == 301
    val canStartSelectedRooms = hasKnownState && !taskActive && selectedRooms.isNotEmpty() &&
        rooms?.ids?.containsAll(selectedRooms) == true &&
        ((quickActionsAvailable && selectedQuickAction != null) || (preferencesAvailable && advancedExpanded))
    val cleaningActionLabel = when {
        !hasKnownState && refreshing -> "Checking vacuum status…"
        !hasKnownState -> "Check vacuum status"
        cleaning -> "Stop cleaning"
        pausedSegment -> "Resume room cleaning"
        paused -> "Cleaning paused"
        else -> "Start cleaning"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VACUUM STATUS", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                val cleaning = status?.isCleaning() == true
                val ringPercent = when {
                    status == null -> null
                    cleaning && status.cleanPercent in 0..100 -> status.cleanPercent
                    cleaning -> null
                    status.battery in 0..100 -> status.battery
                    else -> null
                }
                RobotProgressArt(
                    progress = ringPercent?.div(100f),
                    isConnecting = status == null && connection.contains("connect", ignoreCase = true),
                    isCleaning = cleaning,
                    modifier = Modifier.height(212.dp),
                )
                Text(status?.stateLabel() ?: "Waiting for live status", style = MaterialTheme.typography.headlineMedium)
                val subtitle = when {
                    status == null -> connection.takeUnless { it == "Connected directly over Wi-Fi" }.orEmpty()
                    status.error > 0 -> status.errorLabel() ?: "Vacuum error"
                    status.isCleaning() -> "Cleaning ${status.areaLabel()} · ${status.durationLabel()}"
                    else -> status.errorLabel() ?: "No errors"
                }
                if (subtitle.isNotBlank()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MetricTile("Battery", status?.battery?.let { "$it%" } ?: "—", Modifier.weight(1f))
                    MetricTile("Cleaned", status?.areaLabel() ?: "—", Modifier.weight(1f))
                    MetricTile("Duration", status?.durationLabel() ?: "—", Modifier.weight(1f))
                }
            }
        }
        if (!roomUnsupported && (rooms != null || roomsLoading || roomError.isNotBlank())) RoomSelectorCard(
            mapping = rooms,
            roomNames = roomNames,
            error = roomError,
            loading = roomsLoading,
            selected = selectedRooms,
            repeat = repeat,
            busy = busy,
            cleaning = taskActive,
            onToggle = onRoomToggle,
            onAll = onSelectAllRooms,
            onClear = onClearRooms,
            onRepeat = onRepeat,
            onRefresh = onLoadRooms,
            onRename = onRenameRoom,
        )
        if (quickActionsAvailable) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Quick actions", style = MaterialTheme.typography.titleLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    QuickCleaningMode.values().forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = !advancedExpanded && selectedQuickAction == mode,
                            onClick = { onQuickAction(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = QuickCleaningMode.values().size),
                            modifier = Modifier.weight(1f),
                            enabled = !busy && hasKnownState && !taskActive,
                            icon = {},
                        ) { Text(mode.label) }
                    }
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cleaning controls", style = MaterialTheme.typography.titleLarge)
                Button(
                    onClick = {
                        when {
                            !hasKnownState -> onRefresh()
                            cleaning -> onCommand("app_stop", JSONArray())
                            pausedSegment -> onCommand("resume_segment_clean", JSONArray())
                            else -> onConfirmRoomClean()
                        }
                    },
                    enabled = !busy && (cleaning || pausedSegment || canStartSelectedRooms || (!hasKnownState && !refreshing)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text(cleaningActionLabel) }
                CleaningActionSegmentedButtons(
                    pauseEnabled = !busy && cleaning,
                    dockEnabled = !busy && hasKnownState,
                    onPause = { onCommand("app_pause", JSONArray()) },
                    onDock = { onCommand("app_charge", JSONArray()) },
                )
            }
        }
        if (preferencesAvailable) OutlinedButton(
            onClick = onToggleAdvanced,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (advancedExpanded) "Hide advanced" else "Advanced") }
        if (advancedExpanded && preferencesAvailable) Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("Cleaning preferences", style = MaterialTheme.typography.titleLarge)
                if (fanAvailable && deepRouteActive) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("Suction", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Suction is minimized by the Deep route. Change the route to adjust suction.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (fanAvailable) {
                    // No V1 getter enumerates suction levels; known S8 family models supply Max+.
                    val maxPlus = status?.fan == 108 || active.model.substringAfterLast('.') in setOf("a50", "a51", "a68", "a69", "a70", "a96", "a97")
                    val suctionNames = listOf("Off", "Quiet", "Balanced", "Turbo", "Max") + if (maxPlus) listOf("Max+") else emptyList()
                    val suctionValues = listOf(105, 101, 102, 103, 104) + if (maxPlus) listOf(108) else emptyList()
                    ModePreferenceSlider("Suction", suctionNames, suctionValues, status?.fan ?: -1, !busy && !taskActive, { onSlider("set_custom_mode", it) })
                }
                if (waterAvailable) ModePreferenceSlider(
                    "Mop intensity",
                    listOf("Off", "Mild", "Moderate", "Intense"),
                    listOf(200, 201, 202, 203),
                    status?.water ?: -1,
                    !busy && !taskActive,
                    { onSlider("set_water_box_custom_mode", it) },
                    supportingText = "Intense sets water flow to maximum automatically.",
                )
                if (mopRouteAvailable) ModePreferenceSlider(
                    "Mop route",
                    listOf("Standard", "Deep") + if (probe?.features?.carefulSlowMop == true || status?.mop == 303) listOf("Deep+") else emptyList(),
                    listOf(300, 301) + if (probe?.features?.carefulSlowMop == true || status?.mop == 303) listOf(303) else emptyList(),
                    reportedMopRoute,
                    !busy && !taskActive,
                    {
                        selectedMopRoute = it
                        onSlider("set_mop_mode", it)
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Refresh") }
            OutlinedButton(onClick = { onCommand("find_me", JSONArray()) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Find vacuum") }
        }
    }
}

@Composable
private fun CleaningActionSegmentedButtons(
    pauseEnabled: Boolean,
    dockEnabled: Boolean,
    onPause: () -> Unit,
    onDock: () -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = false,
            onClick = onPause,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.weight(1f),
            enabled = pauseEnabled,
            icon = {},
        ) { Text("Pause") }
        SegmentedButton(
            selected = false,
            onClick = onDock,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.weight(1f),
            enabled = dockEnabled,
            icon = {},
        ) { Text("Dock") }
    }
}

@Composable
private fun RoomsContent(
    active: RobotProfile, mapping: RoomMapping?, roomNames: Map<Int, String>, error: String, loading: Boolean, selected: Set<Int>, repeat: Int,
    onToggle: (Int) -> Unit, onAll: () -> Unit, onClear: () -> Unit, onRepeat: (Int) -> Unit, onRefresh: () -> Unit, onClean: () -> Unit,
    onRename: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Choose rooms", style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
        }
        if (loading) LinearLoadingRow("Loading rooms from your vacuum…")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (mapping == null && !loading) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Rooms aren’t loaded yet", style = MaterialTheme.typography.titleMedium); Text("Connect to the vacuum to read its room map.", color = MaterialTheme.colorScheme.onSurfaceVariant); Button(onClick = onRefresh) { Text("Load rooms") } } }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onAll, enabled = mapping?.ids?.isNotEmpty() == true) { Text("Select all") }
                TextButton(onClick = onClear, enabled = selected.isNotEmpty()) { Text("Clear") }
            }
            mapping?.ids?.forEach { id ->
                val name = roomNames[id]?.takeIf(String::isNotBlank) ?: "Room $id"
                Card(Modifier.fillMaxWidth().clickable { onToggle(id) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = id in selected, onCheckedChange = { onToggle(id) })
                        Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleMedium); Text("Room $id", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { onRename(id) }) { Text("Rename") }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Cleaning passes", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..3).forEach { count ->
                            if (count == repeat) Button(onClick = { onRepeat(count) }) { Text("$count") }
                            else FilledTonalButton(onClick = { onRepeat(count) }) { Text("$count") }
                        }
                    }
                }
            }
            Button(onClick = onClean, enabled = selected.isNotEmpty() && mapping?.ids?.containsAll(selected) == true, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(if (selected.isEmpty()) "Select rooms to clean" else "Clean ${selected.size} ${if (selected.size == 1) "room" else "rooms"}")
            }
            Text("The vacuum will clean only the selected room IDs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoomSelectorCard(
    mapping: RoomMapping?,
    roomNames: Map<Int, String>,
    error: String,
    loading: Boolean,
    selected: Set<Int>,
    repeat: Int,
    busy: Boolean,
    cleaning: Boolean,
    onToggle: (Int) -> Unit,
    onAll: () -> Unit,
    onClear: () -> Unit,
    onRepeat: (Int) -> Unit,
    onRefresh: () -> Unit,
    onRename: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Room selector", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onRefresh, enabled = !loading && !busy) { Text("Refresh") }
            }
            if (loading) LinearLoadingRow("Loading rooms from your vacuum…")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (mapping == null && !loading) {
                Text("Connect to the vacuum to read its room map.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Load rooms") }
            } else if (mapping != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onAll, enabled = mapping.ids.isNotEmpty() && !busy && !cleaning) { Text("Select all") }
                    TextButton(onClick = onClear, enabled = selected.isNotEmpty() && !busy && !cleaning) { Text("Clear") }
                }
                mapping.ids.forEachIndexed { index, id ->
                    val name = roomNames[id]?.takeIf(String::isNotBlank) ?: "Room $id"
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy && !cleaning) { onToggle(id) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = id in selected, onCheckedChange = { if (!busy && !cleaning) onToggle(id) }, enabled = !busy && !cleaning)
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text("Room $id", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRename(id) }, enabled = !busy) { Text("Rename") }
                    }
                    if (index < mapping.ids.lastIndex) HorizontalDivider()
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cleaning passes", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow {
                        (1..3).forEachIndexed { index, count ->
                            SegmentedButton(
                                selected = count == repeat,
                                onClick = { onRepeat(count) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                                enabled = !busy && !cleaning,
                                icon = {},
                            ) { Text("$count") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CareContent(
    values: Map<String, String>, loading: Boolean, probe: RobotProbe?, probeLoading: Boolean,
    busy: Boolean, onRefresh: () -> Unit,
    onDndEnabled: (Boolean) -> Unit, onDndSchedule: (DndSetting) -> Unit,
    onChildLock: (Boolean) -> Unit, onVoiceVolume: (Int) -> Unit, onCarpetBoost: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Care and settings", style = MaterialTheme.typography.headlineSmall) }
            TextButton(onClick = onRefresh, enabled = !loading && !busy && !probeLoading) { Text("Refresh") }
        }
        if (loading) LinearLoadingRow("Reading maintenance data…")
        if (probeLoading && probe == null) LinearLoadingRow("Checking available settings…")
        if (values.isEmpty() && !loading) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("No maintenance data loaded", style = MaterialTheme.typography.titleMedium); Button(onClick = onRefresh) { Text("Load from vacuum") } } }
        }
        val settings = probe?.settings
        if (settings != null && settings.hasAny()) {
            RobotSettingsCard(settings, busy || probeLoading, onDndEnabled, onDndSchedule, onChildLock, onVoiceVolume, onCarpetBoost)
        }
        values.forEach { (label, value) ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun RobotSettingsCard(
    settings: RobotSettings, busy: Boolean,
    onDndEnabled: (Boolean) -> Unit, onDndSchedule: (DndSetting) -> Unit,
    onChildLock: (Boolean) -> Unit, onVoiceVolume: (Int) -> Unit, onCarpetBoost: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Robot settings", style = MaterialTheme.typography.titleLarge)
            settings.dnd?.let { dnd ->
                var start by remember(dnd) { mutableStateOf("%02d:%02d".format(dnd.startHour, dnd.startMinute)) }
                var end by remember(dnd) { mutableStateOf("%02d:%02d".format(dnd.endHour, dnd.endMinute)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Do not disturb", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = dnd.enabled, onCheckedChange = onDndEnabled, enabled = !busy)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("From") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                    OutlinedTextField(end, { end = it }, label = { Text("Until") }, singleLine = true, modifier = Modifier.weight(1f), enabled = !busy)
                }
                val startTime = parseHourMinute(start)
                val endTime = parseHourMinute(end)
                TextButton(
                    onClick = { onDndSchedule(dnd.copy(
                        startHour = startTime!!.first, startMinute = startTime.second,
                        endHour = endTime!!.first, endMinute = endTime.second,
                        enabled = true,
                    )) },
                    enabled = !busy && startTime != null && endTime != null,
                ) { Text("Save DND hours") }
                HorizontalDivider()
            }
            settings.childLock?.let { enabled ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Child lock", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = enabled, onCheckedChange = onChildLock, enabled = !busy)
                }
                HorizontalDivider()
            }
            settings.volume?.let { volume ->
                var draft by remember(volume) { mutableStateOf(volume) }
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Voice volume", style = MaterialTheme.typography.titleMedium)
                        Text("$draft%", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = draft.toFloat(),
                        onValueChange = { draft = it.roundToInt() },
                        onValueChangeFinished = { if (draft != volume) onVoiceVolume(draft) },
                        valueRange = 0f..100f,
                        enabled = !busy,
                    )
                }
                HorizontalDivider()
            }
            settings.carpet?.let { carpet ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Carpet boost", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(checked = carpet.enabled, onCheckedChange = onCarpetBoost, enabled = !busy)
                }
            }
        }
    }
}

private fun parseHourMinute(text: String): Pair<Int, Int>? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour to minute else null
}

@Composable
private fun LinearLoadingRow(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DevicePickerDialog(
    profiles: List<RobotProfile>, discovered: List<DiscoveredDevice>, onSelect: (RobotProfile) -> Unit,
    onAdd: () -> Unit, onPair: (DiscoveredDevice) -> Unit, onEdit: (RobotProfile) -> Unit,
    onForget: (RobotProfile) -> Unit, onImport: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your devices") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { onSelect(profile) }.padding(vertical = 8.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (profile.host.isBlank()) "Waiting for local discovery" else profile.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onEdit(profile) }) { Text("Edit") }
                        TextButton(onClick = { onForget(profile) }) { Text("Forget") }
                    }
                }
                if (discovered.isNotEmpty()) {
                    HorizontalDivider()
                    Text("NEARBY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    discovered.forEach { device ->
                        Row(Modifier.fillMaxWidth().clickable { onPair(device) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column { Text(device.name ?: "Roborock"); Text(device.host ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("Pair", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import from Roborock account") }
            }
        },
        confirmButton = { Button(onClick = onAdd) { Text("Add local key") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun PairDialog(device: DiscoveredDevice?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by androidx.compose.runtime.remember(device?.host) { mutableStateOf(device?.name.orEmpty()) }
    var host by androidx.compose.runtime.remember(device?.host) { mutableStateOf(device?.host.orEmpty()) }
    var key by androidx.compose.runtime.remember(device?.host) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (device == null) "Add a local vacuum" else "Pair ${device.name ?: "Roborock"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Vacuum name") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("Private LAN address") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("16-byte local key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Text("The local key stays encrypted on this device and is sent only to the vacuum on your Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(name, host, key) }) { Text("Save and connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
