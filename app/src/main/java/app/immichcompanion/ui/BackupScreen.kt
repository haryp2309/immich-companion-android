package app.immichcompanion.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.immichcompanion.AppContainer
import app.immichcompanion.backup.Album
import app.immichcompanion.backup.BackupSummary
import app.immichcompanion.data.BackupSettings
import kotlinx.coroutines.launch

/** How much of the photo library the app may read. */
enum class MediaAccess { None, Partial, Full }

private val mediaPermissions = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
)

fun Context.mediaAccess(): MediaAccess {
    fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> MediaAccess.Full
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Partial
        else -> MediaAccess.None
    }
}

class BackupViewModel(private val container: AppContainer) : ViewModel() {
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var summary by mutableStateOf<BackupSummary?>(null)
        private set

    fun refresh() {
        viewModelScope.launch {
            runCatching { container.backup.albums() }.onSuccess { albums = it }
            runCatching { container.backup.summary() }.onSuccess { summary = it }
        }
    }

    fun update(settings: BackupSettings) {
        viewModelScope.launch {
            container.backup.updateSettings(settings)
            summary = runCatching { container.backup.summary() }.getOrNull()
        }
    }

    fun backUpNow() = container.backup.backUpNow()
}

@Composable
fun BackupScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = viewModel { BackupViewModel(container) }
    val settings by container.settings.backup.collectAsStateWithLifecycle(initialValue = null)
    val progress by container.backup.progress.collectAsStateWithLifecycle()
    val session by container.settings.session.collectAsStateWithLifecycle(initialValue = null)
    var access by remember { mutableStateOf(context.mediaAccess()) }

    val requestPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = context.mediaAccess()
        vm.refresh()
    }
    // The user may change access in system settings, or pick more photos.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = context.mediaAccess()
        vm.refresh()
    }
    LaunchedEffect(progress.running) { if (!progress.running) vm.refresh() }

    Scaffold(topBar = { TopBar("Backup", onBack) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (access != MediaAccess.Full) {
                SectionCard("Photo access") {
                    Text(
                        if (access == MediaAccess.None) "Allow access to your photos and videos to back them up."
                        else "You allowed access to selected photos only. Only those are backed up.",
                    )
                    Button(onClick = { requestPermissions.launch(mediaPermissions) }) {
                        Text(if (access == MediaAccess.None) "Allow access" else "Change selection")
                    }
                }
            }

            val s = settings ?: return@Column
            SectionCard("Automatic backup") {
                SwitchRow(
                    "Back up new photos and videos",
                    s.enabled,
                    enabled = s.enabled || (access != MediaAccess.None && session != null),
                ) {
                    vm.update(s.copy(enabled = it))
                    if (it) requestPermissions.launch(mediaPermissions)
                }
                SwitchRow("Only on Wi-Fi", s.wifiOnly) { vm.update(s.copy(wifiOnly = it)) }
                SwitchRow("Only while charging", s.chargingOnly) { vm.update(s.copy(chargingOnly = it)) }
                if (session == null) {
                    Text("Sign in to Immich first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (s.enabled && s.albums.isEmpty()) {
                    Text("Choose at least one album below.", color = MaterialTheme.colorScheme.error)
                }
            }

            SectionCard("Status") {
                vm.summary?.let {
                    InfoRow("Selected", "${it.selected}")
                    InfoRow("Backed up", "${it.backedUp}")
                    InfoRow("Remaining", "${it.remaining}")
                }
                if (progress.running) {
                    Text(
                        if (progress.total == 0) "Looking for new files…"
                        else "Backing up ${progress.processed} of ${progress.total}",
                    )
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.processed / progress.total.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    progress.current?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (progress.finishedAt != null) {
                    val p = progress
                    InfoRow("Last run", "${p.uploaded} uploaded · ${p.duplicates} already there · ${p.failed} failed")
                }
                progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(
                    onClick = vm::backUpNow,
                    enabled = !progress.running && session != null && s.albums.isNotEmpty() && access != MediaAccess.None,
                ) { Text("Back up now") }
            }

            if (access != MediaAccess.None) {
                SectionCard("Albums") {
                    if (vm.albums.isEmpty()) {
                        Text("No photos or videos found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    vm.albums.forEach { album ->
                        val checked = album.id in s.albums
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.update(s.copy(albums = s.albums.toggle(album.id))) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { vm.update(s.copy(albums = s.albums.toggle(album.id))) })
                            Text(album.name, Modifier.weight(1f))
                            Text("${album.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id
