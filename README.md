# VYNIX Beta

VYNIX is an independent Android music player built from its own Kotlin and Jetpack Compose source in this repository. The app uses a graphite-and-gold visual style and is not built from Riff.

## Player features

- Online track search and in-app playback through the providers implemented in the native VYNIX source.
- Background playback with media notification, lock-screen and headset controls.
- Queue management, play next, shuffle, repeat and playback-position restoration.
- Local music library, file/folder import, playlists and liked tracks.
- Lyrics lookup with offline cache, synced highlighting and tap-to-seek.
- Download support with progress and cancellation where the selected source permits downloads.
- Playback speed and pitch, equalizer, bass boost, echo, distortion and reverb where supported by the device.
- Trim local audio, export/share audio, sleep timer and configurable accent colors.
- Application ID: `app.vynix`; graphite-and-gold branding and VYNIX launcher icon.

Online playback depends on the upstream provider and its current API/stream availability. Some sources may refuse requests or change their APIs; the app must be validated with real tracks on a device. VYNIX does not bypass DRM or paid access.

## Build an APK (phone-only workflow)

1. Open [VYNIX Android Beta Actions](https://github.com/ramzilzokirov-lgtm/VYNIX/actions/workflows/android-beta.yml).
2. Tap **Run workflow** or push a change under `app/**` on `main`.
3. Open the completed run and download the `VYNIX-Native-APK` artifact. It contains the APK and `SHA256SUMS.txt`.

GitHub Actions builds the native Kotlin/Compose project using JDK 17, Gradle 8.9 and Android SDK tooling. Android Studio or a local PC build is not required.

## VYNIX backend

The existing FastAPI service remains in `server/app.py`. Configure `DATABASE_URL` with a PostgreSQL connection string on the hosting provider. The `/health` endpoint reports whether the database is configured and reachable.

## Build and playback status

A successful workflow confirms compilation and configured checks, not that every external provider can stream every track. Real-device playback, downloads and device-specific audio effects still need validation.
