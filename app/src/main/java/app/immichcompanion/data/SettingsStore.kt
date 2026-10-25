package app.immichcompanion.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")
private val Context.sessionDataStore by preferencesDataStore("session")

/** A signed-in Immich session. [apiBaseUrl] is the resolved API root, e.g. `http://immich:2283/api`. */
data class Session(
    val serverUrl: String,
    val apiBaseUrl: String,
    val accessToken: String,
    val userEmail: String,
    val userName: String,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val tailscaleEnabled = booleanPreferencesKey("tailscale_enabled")
        val lastServerUrl = stringPreferencesKey("last_server_url")

        val serverUrl = stringPreferencesKey("server_url")
        val apiBaseUrl = stringPreferencesKey("api_base_url")
        val token = stringPreferencesKey("access_token_enc")
        val email = stringPreferencesKey("user_email")
        val name = stringPreferencesKey("user_name")
    }

    val tailscaleEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.tailscaleEnabled] ?: false }

    val lastServerUrl: Flow<String> =
        context.settingsDataStore.data.map { it[Keys.lastServerUrl] ?: "" }

    val session: Flow<Session?> = context.sessionDataStore.data.map { p ->
        val token = p[Keys.token]?.let(TokenCipher::decrypt) ?: return@map null
        Session(
            serverUrl = p[Keys.serverUrl] ?: return@map null,
            apiBaseUrl = p[Keys.apiBaseUrl] ?: return@map null,
            accessToken = token,
            userEmail = p[Keys.email] ?: "",
            userName = p[Keys.name] ?: "",
        )
    }

    suspend fun currentSession(): Session? = session.first()

    suspend fun setTailscaleEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.tailscaleEnabled] = enabled }
    }

    suspend fun saveSession(session: Session) {
        context.settingsDataStore.edit { it[Keys.lastServerUrl] = session.serverUrl }
        context.sessionDataStore.edit {
            it[Keys.serverUrl] = session.serverUrl
            it[Keys.apiBaseUrl] = session.apiBaseUrl
            it[Keys.token] = TokenCipher.encrypt(session.accessToken)
            it[Keys.email] = session.userEmail
            it[Keys.name] = session.userName
        }
    }

    suspend fun clearSession() {
        context.sessionDataStore.edit { it.clear() }
    }
}
