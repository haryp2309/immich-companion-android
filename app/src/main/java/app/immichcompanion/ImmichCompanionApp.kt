package app.immichcompanion

import android.app.Application
import app.immichcompanion.backup.BackupManager
import app.immichcompanion.data.SettingsStore
import app.immichcompanion.immich.AuthRepository
import app.immichcompanion.immich.ImmichApi
import app.immichcompanion.net.HttpClients
import app.immichcompanion.tailscale.TailscaleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ImmichCompanionApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.tailscale.init()
        container.scope.launch { container.backup.init() }
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
}
