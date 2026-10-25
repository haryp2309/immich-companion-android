package app.immichcompanion.net

import app.immichcompanion.tailscale.TailscaleManager
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

class TailscaleNotConnectedException : IOException("Tailscale is enabled but not connected")

/**
 * Builds the single OkHttpClient used for all Immich traffic. When Tailscale is
 * enabled, every request goes through the embedded node's loopback proxy; when it
 * is disabled, requests go out directly.
 */
object HttpClients {
    private const val PROXY_USER = "tsbridge"

    fun create(tailscale: TailscaleManager): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .proxySelector(TailnetProxySelector(tailscale))
        // Fail instead of silently going direct when the user expects the tailnet.
        .addInterceptor { chain ->
            if (tailscale.enabled.value && tailscale.proxy == null) throw TailscaleNotConnectedException()
            chain.proceed(chain.request())
        }
        // HTTPS goes through a CONNECT tunnel; OkHttp asks for the credentials up front.
        .proxyAuthenticator(Authenticator { _, response ->
            val proxy = tailscale.proxy ?: return@Authenticator null
            val credential = Credentials.basic(PROXY_USER, proxy.token)
            if (response.request.header("Proxy-Authorization") == credential) return@Authenticator null
            response.request.newBuilder().header("Proxy-Authorization", credential).build()
        })
        // Plain HTTP is forwarded by the proxy, so each request carries the credentials.
        .addNetworkInterceptor(PlainHttpProxyAuth(tailscale))
        .build()

    private class TailnetProxySelector(private val tailscale: TailscaleManager) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val proxy = tailscale.proxy
            if (!tailscale.enabled.value || proxy == null) return listOf(Proxy.NO_PROXY)
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    private class PlainHttpProxyAuth(private val tailscale: TailscaleManager) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val viaProxy = chain.connection()?.route()?.proxy?.type() == Proxy.Type.HTTP
            val proxy = tailscale.proxy
            if (!viaProxy || request.isHttps || proxy == null) return chain.proceed(request)
            return chain.proceed(
                request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(PROXY_USER, proxy.token))
                    .build(),
            )
        }
    }
}
