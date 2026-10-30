package com.roboff.lan

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.roboff.lan.auth.RobotProfile
import com.roboff.lan.auth.RobotStore
import com.roboff.lan.cloud.CloudCatalog
import com.roboff.lan.cloud.CloudClient
import com.roboff.lan.ui.theme.RoboffTheme
import java.io.Closeable
import java.util.concurrent.Executors

/** Cloud access is used only here to import local keys and room names. */
class OnboardingActivity : ComponentActivity() {
    private lateinit var session: SetupSession
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = lastCustomNonConfigurationInstance as? SetupSession ?: SetupSession()
        session.screen = this
        syncWindowSecurity(session.page == SetupPage.CATALOG)
        setContent {
            RoboffTheme {
                OnboardingScreen(
                    session = session,
                    onUseLocalKey = { finishSetup() },
                    onSaved = { finishSetup() },
                )
            }
        }
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = session

    override fun onResume() {
        super.onResume()
        main.post(session.resendTicker)
    }

    override fun onPause() {
        main.removeCallbacks(session.resendTicker)
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(session.resendTicker)
        if (session.screen === this) session.screen = null
        if (!isChangingConfigurations) session.close()
        super.onDestroy()
    }

    internal fun syncWindowSecurity(catalog: Boolean) {
        if (catalog) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun finishSetup() {
        getSharedPreferences("app_settings", MODE_PRIVATE).edit().putBoolean("local_setup", true).apply()
        session.close()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}

private enum class SetupPage { SIGN_IN, CODE, RETRIEVE, CATALOG }

private class SetupSession : Closeable {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    @Volatile private var closed = false
    var screen: OnboardingActivity? = null
    var page by mutableStateOf(SetupPage.SIGN_IN)
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var code by mutableStateOf("")
    var error by mutableStateOf("")
    var progress by mutableStateOf("")
    var busy by mutableStateOf(false)
    var resendAt by mutableStateOf(0L)
    var secondsUntilResend by mutableStateOf(0)
    var catalog by mutableStateOf<CloudCatalog?>(null)
    var selectedIds by mutableStateOf(setOf<String>())
    private var client: CloudClient? = null
    private var clientEmail: String? = null

    val resendTicker = object : Runnable {
        override fun run() {
            if (closed) return
            secondsUntilResend = (((resendAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000).toInt()
            main.postDelayed(this, 1000)
        }
    }

    fun requestCode() {
        val address = email.trim()
        if (!validEmail(address)) { error = "Enter the email address for your Roborock account."; return }
        if (!begin(address)) return
        val request = client!!
        launch("Requesting your sign-in email…", onSuccess = {
            page = SetupPage.CODE
            resendAt = android.os.SystemClock.elapsedRealtime() + 60_000
            code = ""
        }) { request.requestEmailCode() }
    }

    fun loginWithPassword() {
        val address = email.trim()
        if (!validEmail(address)) { error = "Enter the email address for your Roborock account."; return }
        if (password.isEmpty()) { error = "Enter your password, or choose email-code sign-in."; return }
        if (!begin(address)) return
        val request = client!!
        val secret = password
        password = ""
        launch("Signing in and finding your home…", onCatalog = true) { request.loginWithPassword(secret) }
    }

    fun verifyCode() {
        if (!code.trim().matches(Regex("[0-9]{4,8}"))) { error = "Enter the numeric code from your email."; return }
        val request = client ?: run { page = SetupPage.SIGN_IN; error = "Start sign-in again to request a new code."; return }
        val secret = code.trim()
        code = ""
        launch("Verifying your code and finding your home…", onCatalog = true) { request.loginWithEmailCode(secret) }
    }

    fun resendCode() {
        if (busy || secondsUntilResend > 0) return
        requestCode()
    }

    fun retryCatalog() {
        val request = client ?: run { page = SetupPage.SIGN_IN; error = "Sign in again to retrieve your vacuum details."; return }
        launch("Loading your vacuums and room names…", onCatalog = true) { request.fetchCatalog() }
    }

    fun toggle(deviceId: String) {
        selectedIds = if (deviceId in selectedIds) selectedIds - deviceId else selectedIds + deviceId
    }

    fun save(store: RobotStore, onSaved: () -> Unit) {
        if (busy) return
        val current = catalog ?: return
        val selected = current.devices.filter { it.id in selectedIds && supported(it) }
        if (selected.isEmpty()) { error = "Select at least one supported vacuum."; return }
        val profiles = selected.map { device ->
            RobotProfile(device.id, device.name, "", device.localKey(), device.model, current.homeId, current.homeName, device.roomNames)
        }
        launch("Saving encrypted local keys and room names…", onCatalog = false, success = onSaved) {
            store.saveAll(profiles)
        }
    }

    fun switchAccount() {
        generation++
        client?.close()
        client = null
        clientEmail = null
        catalog = null
        error = ""
        busy = false
        page = SetupPage.SIGN_IN
    }

    fun handleBack() {
        if (busy || page != SetupPage.SIGN_IN) {
            generation++
            client?.close()
            client = null
            clientEmail = null
            busy = false
            progress = ""
            error = ""
            catalog = null
            page = SetupPage.SIGN_IN
        } else {
            screen?.finish()
        }
    }

    private fun begin(address: String): Boolean {
        if (busy || closed) return false
        if (client == null || clientEmail != address) {
            client?.close()
            client = CloudClient(address)
            clientEmail = address
        }
        email = address
        error = ""
        return true
    }

    private fun launch(
        message: String,
        onCatalog: Boolean = false,
        success: (() -> Unit)? = null,
        onSuccess: (() -> Unit)? = null,
        action: () -> Any?,
    ) {
        if (busy || closed) return
        busy = true
        error = ""
        progress = message
        val taskGeneration = ++generation
        val requestClient = client
        worker.execute {
            try {
                val result = action()
                main.post {
                    if (closed || taskGeneration != generation) return@post
                    busy = false
                    progress = ""
                    if (onCatalog) {
                        val resultCatalog = result as? CloudCatalog
                        if (resultCatalog == null) {
                            fail("Roborock returned no vacuum catalog.", requestClient?.isAuthenticated() == true)
                        } else {
                            catalog = resultCatalog
                            selectedIds = resultCatalog.devices.filter(::supported).map { it.id }.toSet()
                            page = SetupPage.CATALOG
                            client?.close()
                            client = null
                            clientEmail = null
                        }
                    } else {
                        onSuccess?.invoke()
                        success?.invoke()
                    }
                }
            } catch (failure: Exception) {
                main.post {
                    if (closed || taskGeneration != generation) return@post
                    busy = false
                    progress = ""
                    fail(failure.message ?: "Could not complete setup. Check your connection and try again.", requestClient?.isAuthenticated() == true)
                }
            }
        }
    }

    private fun fail(message: String, authenticated: Boolean) {
        error = message
        if (authenticated) page = SetupPage.RETRIEVE
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        client?.close()
        client = null
        clientEmail = null
        worker.shutdownNow()
    }

    companion object {
        fun validEmail(value: String): Boolean = android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()
        fun supported(device: CloudCatalog.Device): Boolean = device.protocol.isBlank() || device.protocol == "1.0" || device.protocol == "L01"
    }
}

@Composable
private fun OnboardingScreen(session: SetupSession, onUseLocalKey: () -> Unit, onSaved: () -> Unit) {
    val page = session.page
    BackHandler(onBack = session::handleBack)
    androidx.compose.runtime.SideEffect { session.screen?.syncWindowSecurity(page == SetupPage.CATALOG) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Roboff", style = MaterialTheme.typography.displaySmall)
        Text("A little closer to home", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (page) {
                        SetupPage.SIGN_IN -> "01 · ACCOUNT SETUP"
                        SetupPage.CODE -> "01 · CHECK YOUR EMAIL"
                        SetupPage.RETRIEVE, SetupPage.CATALOG -> "02 · YOUR VACUUMS"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                AnimatedContent(page, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "setup step") { current ->
                    when (current) {
                        SetupPage.SIGN_IN -> SignInForm(session)
                        SetupPage.CODE -> CodeForm(session)
                        SetupPage.RETRIEVE -> RetryForm(session)
                        SetupPage.CATALOG -> CatalogForm(session, onSaved)
                    }
                }
                if (session.error.isNotBlank()) Text(session.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (session.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.width(24.dp), strokeWidth = 3.dp)
                        Text(session.progress, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Sign-in retrieves your vacuum keys and home details from Roborock. Everyday cleaning uses your local Wi-Fi connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(enabled = !session.busy, onClick = onUseLocalKey, modifier = Modifier.fillMaxWidth()) {
            Text("Use an existing local key instead")
        }
    }
}

@Composable
private fun SignInForm(session: SetupSession) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bring your Roborock home.", style = MaterialTheme.typography.headlineMedium)
        Text("Sign in to find your vacuums and save their local keys and room names on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = session.email, onValueChange = { session.email = it }, label = { Text("Email address") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, enabled = !session.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = session.password, onValueChange = { session.password = it }, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, enabled = !session.busy, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = session::loginWithPassword, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in with password") }
        FilledTonalButton(onClick = session::requestCode, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("Email me a sign-in code") }
        Text("Your password and sign-in session are used only during setup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CodeForm(session: SetupSession) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Check your inbox.", style = MaterialTheme.typography.headlineMedium)
        Text("Enter the sign-in code sent to ${session.email}. You can switch to your email app and come back here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = session.code, onValueChange = { session.code = it.filter(Char::isDigit).take(8) }, label = { Text("Email verification code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, enabled = !session.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = session::verifyCode, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("Verify and find my vacuums") }
        FilledTonalButton(onClick = session::resendCode, enabled = !session.busy && session.secondsUntilResend == 0, modifier = Modifier.fillMaxWidth()) {
            Text(if (session.secondsUntilResend > 0) "Resend code in ${session.secondsUntilResend}s" else "Resend code")
        }
        OutlinedButton(onClick = session::switchAccount, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("Use a different sign-in") }
    }
}

@Composable
private fun RetryForm(session: SetupSession) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("You’re signed in.", style = MaterialTheme.typography.headlineMedium)
        Text("Your sign-in worked, but the vacuum details could not be loaded. Try again without requesting another code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = session::retryCatalog, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("Retry loading vacuums") }
        OutlinedButton(onClick = session::switchAccount, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("Back to sign-in") }
    }
}

@Composable
private fun CatalogForm(session: SetupSession, onSaved: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val catalog = session.catalog ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your home, ready to connect.", style = MaterialTheme.typography.headlineMedium)
        Text("${catalog.homeName}\nChoose which vacuums to save on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        catalog.devices.forEach { device ->
            val supported = SetupSession.supported(device)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(checked = device.id in session.selectedIds, enabled = supported && !session.busy, onCheckedChange = { session.toggle(device.id) })
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (supported) "${device.model} · ${device.roomNames.size} room names" else "This local protocol is not supported yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (catalog.devices.isEmpty()) Text("No compatible vacuum keys were returned for this account. Retry sign-in or use an existing local key.")
        catalog.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = { session.save(RobotStore(appContext), onSaved) }, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Save and connect over Wi-Fi")
        }
        OutlinedButton(onClick = session::switchAccount, enabled = !session.busy, modifier = Modifier.fillMaxWidth()) { Text("Back to sign-in") }
    }
}
