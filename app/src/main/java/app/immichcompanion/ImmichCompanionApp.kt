package app.immichcompanion

import android.app.Application
import android.util.Log
import app.immichcompanion.backup.BackupManager
import app.immichcompanion.data.SettingsStore
import app.immichcompanion.immich.AuthRepository
import app.immichcompanion.immich.ImmichApi
import app.immichcompanion.library.AssetFiles
import app.immichcompanion.library.LibraryDb
import app.immichcompanion.library.LibrarySync
import app.immichcompanion.net.HttpClients
import app.immichcompanion.tailscale.TailscaleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ImmichCompanionApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.tailscale.init()
        container.scope.launch { container.backup.init() }
        container.keepLibraryInSync()
    }
}

/** App-wide singletons. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob())
    val settings = SettingsStore(app)
    val tailscale = TailscaleManager(app, settings, scope)
    val httpClient = HttpClients.create(tailscale)
    val immichApi = ImmichApi(httpClient)
    val auth = AuthRepository(immichApi, settings)
    val backup = BackupManager(app, settings, immichApi)
    val library = LibrarySync(app, settings, immichApi, LibraryDb(app))
    val assetFiles = AssetFiles(app, settings, immichApi)

    /** Syncs the library copy for the photo picker on start, on sign-in/out and after uploads. */
    fun keepLibraryInSync() {
        scope.launch {
            settings.session.map { it?.apiBaseUrl to it?.userEmail }.distinctUntilChanged().collect {
                runCatching { library.sync() }.onFailure { Log.w("LibrarySync", "sync failed", it) }
            }
        }
        scope.launch {
            backup.progress.filter { it.finishedAt != null && it.uploaded > 0 }.map { it.finishedAt }
                .distinctUntilChanged().collect {
                    runCatching { library.sync() }.onFailure { Log.w("LibrarySync", "sync failed", it) }
                }
        }
    }
}
