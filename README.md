# Immich Companion (Android)

A companion app for [Immich](https://immich.app) that adds what the official app lacks:

- **Embedded Tailscale.** A userspace Tailscale node (tsnet) runs inside the app, so Immich traffic goes over your tailnet without the Tailscale app or a VPN slot.
- **Automatic backup** of photos and videos from the albums you choose, over that tailnet.
- **Immich in the Android photo picker.** Your Immich library shows up next to the device's photos in the system picker (Android 13+), and in a fallback picker for apps that use "Get content".

Sign-in is with your Immich email and password; Tailscale sign-in happens in the browser (or with an auth key).

## Setup

1. Install the app (see [Building](#building)), open it and turn on Tailscale. Sign in to your tailnet when asked.
2. Sign in to Immich with your server's tailnet address, e.g. `https://immich.your-tailnet.ts.net`.
3. **Backup:** open *Manage backup*, allow photo access, pick albums and turn on automatic backup. By default it only runs on Wi-Fi.
4. **Photo picker:** Android only lists allowlisted cloud apps, and the allowlist is a device flag that only adb can change. With the phone connected:

   ```bash
   scripts/enable-cloud-picker.sh
   ```

   Then tap *Open photo picker* on the home screen (or open any app's photo picker), go to ⋮ › *Cloud media app* and choose *Immich Companion*. Android resets this choice when the app is updated, and some vendors may reset the allowlist with system updates; run the script again if Immich disappears from the list.

## How it works

| Path | What it is |
| --- | --- |
| `tailscale-bridge/` | Go package wrapping `tailscale.com/tsnet`, built into `app/libs/tsbridge.aar` with gomobile. It runs a token-protected HTTP proxy on 127.0.0.1 that dials through the tailnet. |
| `app/.../tailscale/` | `TailscaleManager`: starts the node on demand, waits for it to connect before requests go out, and passes Android's network interfaces to Go on every network change (Android blocks netlink for apps, and tsnet would otherwise only notice a change after 10 minutes). Status is polled only while a screen shows it. |
| `app/.../net/HttpClients.kt` | The single OkHttpClient. It routes through the tailnet proxy when Tailscale is on and fails fast when it's on but not connected. |
| `app/.../immich/` | Immich API client: server discovery via `/.well-known/immich`, login, uploads, duplicate checks, the sync stream, thumbnails and originals. |
| `app/.../backup/` | MediaStore scanning, a small SQLite table of what's backed up (per account, with cached SHA-1 checksums), and the WorkManager worker and schedule. |
| `app/.../library/` | A local copy of the Immich library for the picker, kept current with Immich's incremental sync stream (`/sync/stream`, the protocol the official app uses), plus the thumbnail/original download cache. |
| `app/.../picker/` | The `CloudMediaProvider` for the system photo picker, the `GET_CONTENT` picker activity, and the content provider that hands picked files to other apps. |
| `app/.../data/` | DataStore settings; the session token is encrypted with an Android Keystore key. |

### Backup

- Runs about 30 s after new photos or videos appear (a MediaStore content trigger), every 6 hours as a fallback, and on *Back up now*.
- Before uploading, the app asks the server which checksums it already has (`/assets/bulk-upload-check`), so files that are already in Immich (e.g. from the official app) aren't uploaded again. Uploads stream from MediaStore and carry the checksum, oldest first.
- With *photo location* access the original files (including GPS data) are uploaded; without it Android strips the location.
- When the backup can't continue without you (Immich session expired, Tailscale needs sign-in or admin approval, photo access revoked) the app shows a *Backup paused* notification.

### Photo picker

- The picker copies the media list from the app (paged, and incrementally by sync generation), then asks for thumbnails and files as it needs them. Files are downloaded on demand over the tailnet into the app's cache (originals capped at 1 GB).
- Photos this app backed up point at their local copy, so the picker shows them once.
- Only your own photos and videos from the timeline and archive are listed; trashed, hidden and locked items aren't. Your albums are listed too.

## Limitations

- Android lets a backup that starts in the background run for about 10 minutes at a time, without a foreground service. A large first backup therefore runs in chunks; progress is kept, and it continues on the next run. Opening the app and tapping *Back up now* runs it in the foreground.
- There is no always-on background service. The Tailscale node starts with the app process (also when the picker or a backup starts it) and reconnects from its saved state in a few seconds.
- Log uploads to Tailscale (`log.tailscale.com`) are turned off.

## Building

Requirements: Go, Android SDK with platform 37 and an NDK, and a JDK 17+ (Android Studio's bundled JDK works).

```bash
./tailscale-bridge/build.sh        # builds app/libs/tsbridge.aar (rerun after changing Go code)
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

The release build is shrunk with R8 and split per ABI (`arm64-v8a` for phones, `x86_64` for emulators). It's signed with the debug key, which is fine for sideloading and lets it update a debug install in place; configure a real signing key before distributing it.

Tests:

```bash
(cd tailscale-bridge && go test ./...)
./gradlew :app:testDebugUnitTest
```
