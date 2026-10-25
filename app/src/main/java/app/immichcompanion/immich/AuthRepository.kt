package app.immichcompanion.immich

import app.immichcompanion.data.Session
import app.immichcompanion.data.SettingsStore
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ConnectionCheck(val version: ServerVersion, val user: UserResponse)

class AuthRepository(private val api: ImmichApi, private val settings: SettingsStore) {

    suspend fun login(serverInput: String, email: String, password: String): Session {
        val server = ServerUrl.normalize(serverInput).getOrThrow()
        val apiBase = api.discover(server)
        val res = api.login(apiBase, email.trim(), password)
        val session = Session(
            serverUrl = server.toString().trimEnd('/'),
            apiBaseUrl = apiBase.toString(),
            accessToken = res.accessToken,
            userEmail = res.userEmail,
            userName = res.name,
        )
        settings.saveSession(session)
        return session
    }

    /** Checks that the server answers and the stored token is still valid. */
    suspend fun checkConnection(): ConnectionCheck {
        val session = settings.currentSession() ?: error("Not signed in")
        val apiBase = session.apiBaseUrl.toHttpUrl()
        return ConnectionCheck(api.serverVersion(apiBase), api.me(apiBase, session.accessToken))
    }

    /** Signs out locally even if the server can't be reached. */
    suspend fun logout() {
        val session = settings.currentSession() ?: return
        runCatching { api.logout(session.apiBaseUrl.toHttpUrl(), session.accessToken) }
        settings.clearSession()
    }
}
