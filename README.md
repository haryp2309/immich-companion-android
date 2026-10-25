# Immich Companion (Android)

A companion app for [Immich](https://immich.app) that adds what the official app lacks:

- **Embedded Tailscale.** A userspace Tailscale node (tsnet) runs inside the app, so Immich traffic goes over your tailnet without the Tailscale app or a VPN slot.
- **Automatic backup** *(planned, milestone 3)*
- **Immich as the photo picker's cloud source** *(planned, milestone 4)*

## Layout

| Path | What it is |
| --- | --- |
| `tailscale-bridge/` | Go package wrapping `tailscale.com/tsnet`, built into `app/libs/tsbridge.aar` with gomobile. It runs a token-protected HTTP proxy on 127.0.0.1 that dials through the tailnet. |
| `app/.../tailscale/` | `TailscaleManager`: starts the node on demand, waits for it to connect before requests go out, and passes Android's network interfaces to Go on every network change (Android blocks netlink for apps, and tsnet would otherwise only notice a change after 10 minutes). Status is polled only while a screen shows it. |
| `app/.../net/HttpClients.kt` | The single OkHttpClient. It routes through the tailnet proxy when Tailscale is on and fails fast when it's on but not connected. |
| `app/.../immich/` | Immich API client (server discovery via `/.well-known/immich`, login, version, current user). |
| `app/.../data/` | DataStore settings; the session token is encrypted with an Android Keystore key. |

## Notes

- There is no always-on background service. The node starts with the app process and reconnects from its saved state in a few seconds. Right after a cold start it runs on a cached network map without peers, so the bridge retries connections in short attempts until the live map arrives.
- Log uploads to Tailscale (`log.tailscale.com`) are turned off.

## Building

Requirements: Go, Android SDK with platform 37 and an NDK, and a JDK 17+ (Android Studio's bundled JDK works).

```bash
./tailscale-bridge/build.sh        # builds app/libs/tsbridge.aar (rerun after changing Go code)
./gradlew :app:assembleDebug
```

Tests:

```bash
(cd tailscale-bridge && go test ./...)
./gradlew :app:testDebugUnitTest
```
