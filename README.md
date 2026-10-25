# Immich Companion (Android)

A companion app for [Immich](https://immich.app) that adds what the official app lacks:

- **Embedded Tailscale.** A userspace Tailscale node (tsnet) runs inside the app, so Immich traffic goes over your tailnet without the Tailscale app or a VPN slot.
- **Automatic backup** *(planned, milestone 3)*
- **Immich as the photo picker's cloud source** *(planned, milestone 4)*

## Layout

| Path | What it is |
| --- | --- |
| `tailscale-bridge/` | Go package wrapping `tailscale.com/tsnet`, built into `app/libs/tsbridge.aar` with gomobile. It runs a token-protected HTTP proxy on 127.0.0.1 that dials through the tailnet. |
| `app/.../tailscale/` | `TailscaleManager`: starts and stops the node, polls status, passes Android's network interfaces to Go (Android blocks netlink for apps). |
| `app/.../net/HttpClients.kt` | The single OkHttpClient. It routes through the tailnet proxy when Tailscale is on and fails fast when it's on but not connected. |
| `app/.../immich/` | Immich API client (server discovery via `/.well-known/immich`, login, version, current user). |
| `app/.../data/` | DataStore settings; the session token is encrypted with an Android Keystore key. |

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
