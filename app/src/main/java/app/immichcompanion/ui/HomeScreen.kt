package app.immichcompanion.ui

import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.immichcompanion.AppContainer
import app.immichcompanion.immich.ConnectionCheck
import app.immichcompanion.library.LibrarySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    var check by mutableStateOf<ConnectionCheck?>(null)
        private set
    var checkError by mutableStateOf<String?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    fun runCheck() {
        if (checking) return
        checking = true
        viewModelScope.launch {
            runCatching { container.auth.checkConnection() }
                .onSuccess { check = it; checkError = null }
                .onFailure { check = null; checkError = it.friendlyMessage() }
            checking = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            container.auth.logout()
            check = null
            checkError = null
        }
    }
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenTailscale: () -> Unit,
    onSignIn: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val vm = viewModel { HomeViewModel(container) }
    val session by container.settings.session.collectAsStateWithLifecycle(initialValue = null)
    val tsEnabled by container.tailscale.enabled.collectAsStateWithLifecycle()
    val tsStatus by container.tailscale.status.collectAsStateWithLifecycle()
    val backup by container.settings.backup.collectAsStateWithLifecycle(initialValue = null)
    val backupProgress by container.backup.progress.collectAsStateWithLifecycle()

    // Re-check whenever the account or the route to the server changes.
    LaunchedEffect(session?.apiBaseUrl, tsEnabled, tsStatus.isRunning) {
        if (session != null && (!tsEnabled || tsStatus.isRunning)) vm.runCheck()
    }

    Scaffold(topBar = { TopBar("Immich Companion") }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("Tailscale") {
                InfoRow("Status", if (tsEnabled) tsStatus.state.toDisplayState() else "Off")
                tsStatus.ips.firstOrNull()?.let { InfoRow("Tailnet IP", it) }
                OutlinedButton(onClick = onOpenTailscale) { Text("Manage Tailscale") }
            }

            SectionCard("Immich") {
                val s = session
                if (s == null) {
                    Text("Not signed in", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onSignIn) { Text("Sign in") }
                } else {
                    InfoRow("Server", s.serverUrl)
                    InfoRow("Account", s.userEmail)
                    when {
                        vm.checking -> InfoRow("Connection", "Checking…")
                        vm.check != null -> InfoRow("Connection", "OK · Immich ${vm.check!!.version}")
                        vm.checkError != null -> Text(vm.checkError!!, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::runCheck, enabled = !vm.checking) { Text("Test connection") }
                        OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
                    }
                }
            }

            SectionCard("Backup") {
                InfoRow(
                    "Automatic backup",
                    when {
                        backupProgress.running -> "Backing up ${backupProgress.processed} of ${backupProgress.total}"
                        backup?.enabled == true -> "On"
                        else -> "Off"
                    },
                )
                OutlinedButton(onClick = onOpenBackup) { Text("Manage backup") }
            }

            PhotoPickerCard(container)
        }
    }
}

fun String.toDisplayState(): String = when (this) {
    "Running" -> "Connected"
    "NeedsLogin" -> "Needs sign-in"
    "NeedsMachineAuth" -> "Waiting for admin approval"
    "Starting", "NoState" -> "Connecting…"
    else -> this
}

@Composable
private fun PhotoPickerCard(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncing by container.library.syncing.collectAsStateWithLifecycle()
    val count by produceState<Int?>(null, syncing) {
        value = withContext(Dispatchers.IO) { container.library.db.count() }
    }
    // Re-read on every recomposition; the user changes this in the picker.
    val resolver = context.contentResolver
    val authority = LibrarySync.CLOUD_AUTHORITY
    SectionCard("Photo picker") {
        InfoRow("Library", if (syncing) "Syncing…" else count?.let { "$it photos and videos" } ?: "–")
        InfoRow(
            "Cloud media app",
            when {
                MediaStore.isCurrentCloudMediaProviderAuthority(resolver, authority) -> "Immich"
                MediaStore.isSupportedCloudMediaProviderAuthority(resolver, authority) -> "Not chosen yet"
                else -> "Not allowed"
            },
        )
        if (!MediaStore.isSupportedCloudMediaProviderAuthority(resolver, authority)) {
            Text(
                "Run scripts/enable-cloud-picker.sh with the phone connected over adb, then choose " +
                    "Immich Companion as the cloud media app in the photo picker. Apps that use " +
                    "\"Get content\" can always pick from Immich.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { scope.launch { runCatching { container.library.sync() } } }, enabled = !syncing) {
            Text("Sync now")
        }
    }
}
