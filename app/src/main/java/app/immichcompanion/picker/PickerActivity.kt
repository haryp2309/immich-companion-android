package app.immichcompanion.picker

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.immichcompanion.AppContainer
import app.immichcompanion.ImmichCompanionApp
import app.immichcompanion.library.AssetFiles
import app.immichcompanion.library.RemoteAsset
import app.immichcompanion.ui.friendlyMessage
import app.immichcompanion.ui.theme.ImmichCompanionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets apps pick Immich photos and videos with ACTION_GET_CONTENT, on devices
 * where the photo picker doesn't offer Immich as its cloud source.
 */
class PickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ImmichCompanionApp).container
        val request = PickRequest.from(intent)
        setContent {
            ImmichCompanionTheme {
                PickerScreen(container, request, onCancel = ::finish, onPicked = ::finishWith)
            }
        }
    }

    private fun finishWith(assets: List<RemoteAsset>) {
        val uris = assets.map { AssetContentProvider.uriFor(it.id, it.fileName) }
        val result = Intent().setData(uris.first()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size > 1) {
            result.clipData = ClipData.newRawUri(null, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}

/** What the calling app asked for. */
data class PickRequest(val mimePrefixes: List<String>, val allowMultiple: Boolean) {
    companion object {
        fun from(intent: Intent): PickRequest {
            val types = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: listOfNotNull(intent.type)
            return PickRequest(mimePrefixes(types), intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        }

        // "image/*" matches by prefix and "image/png" exactly; anything else, such as */*, means everything.
        fun mimePrefixes(types: List<String>): List<String> {
            val prefixes = types.map { it.lowercase().removeSuffix("*") }
            return if (prefixes.any { it.isEmpty() || it == "*/" || !(it.startsWith("image/") || it.startsWith("video/")) }) {
                emptyList()
            } else {
                prefixes
            }
        }
    }
}

class PickerViewModel(private val container: AppContainer, private val request: PickRequest) : ViewModel() {
    var assets by mutableStateOf<List<RemoteAsset>?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val selected = mutableStateListOf<RemoteAsset>()

    init {
        viewModelScope.launch {
            load()
            runCatching { container.library.sync() }.onFailure { error = it.friendlyMessage() }
            load()
        }
    }

    private suspend fun load() {
        assets = withContext(Dispatchers.IO) { container.library.db.assets(mimePrefixes = request.mimePrefixes) }
    }

    fun toggle(asset: RemoteAsset) {
        if (!selected.remove(asset)) selected += asset
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    container: AppContainer,
    request: PickRequest,
    onCancel: () -> Unit,
    onPicked: (List<RemoteAsset>) -> Unit,
) {
    val vm = viewModel { PickerViewModel(container, request) }
    val syncing by container.library.syncing.collectAsStateWithLifecycle()
    val session by container.settings.session.collectAsStateWithLifecycle(initialValue = null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.selected.isEmpty()) "Immich" else "${vm.selected.size} selected") },
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Default.Close, "Cancel") } },
                actions = {
                    if (request.allowMultiple && vm.selected.isNotEmpty()) {
                        TextButton(onClick = { onPicked(vm.selected.toList()) }) { Text("Done") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val assets = vm.assets
            when {
                assets == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                assets.isEmpty() -> Text(
                    when {
                        session == null -> "Sign in to Immich in Immich Companion first."
                        syncing -> "Loading your library…"
                        else -> vm.error ?: "No photos or videos"
                    },
                    Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    contentPadding = PaddingValues(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(assets, key = { it.id }) { asset ->
                        AssetCell(
                            asset,
                            container.assetFiles,
                            selected = asset in vm.selected,
                            onClick = { if (request.allowMultiple) vm.toggle(asset) else onPicked(listOf(asset)) },
                        )
                    }
                }
            }
            if (syncing && !assets.isNullOrEmpty()) LinearProgressIndicator(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun AssetCell(asset: RemoteAsset, files: AssetFiles, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        val bitmap = rememberThumbnail(asset.id, files)
        if (bitmap != null) {
            Image(bitmap, asset.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (asset.isVideo) {
            Icon(Icons.Default.PlayCircle, "Video", Modifier.align(Alignment.BottomStart).padding(4.dp), tint = Color.White)
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle, "Selected",
                Modifier.align(Alignment.TopEnd).padding(4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private val thumbnailCache = LruCache<String, ImageBitmap>(400)
private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(6)

@Composable
private fun rememberThumbnail(assetId: String, files: AssetFiles): ImageBitmap? {
    val state = produceState(thumbnailCache[assetId], assetId) {
        if (value != null) return@produceState
        value = withContext(thumbnailDispatcher) {
            runCatching {
                BitmapFactory.decodeFile(files.thumbnail(assetId).path)?.asImageBitmap()
            }.getOrNull()
        }?.also { thumbnailCache.put(assetId, it) }
    }
    return state.value
}
