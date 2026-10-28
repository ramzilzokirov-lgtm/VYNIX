# VYNIX Beta

VYNIX is a rebranded Android music player built from the open-source Riff for Android engine, with VYNIX graphite-and-gold branding.

## Player features

- Search and stream supported YouTube and SoundCloud tracks inside the app when the provider permits extraction.
- Background playback with media notification, lock-screen and headset controls.
- Queue management, play next, shuffle, repeat and playback-position restoration.
- Local music library, file/folder import, playlists and liked tracks.
- Lyrics lookup with offline cache, synced highlighting and tap-to-seek.
- Download support for MP3, M4A, FLAC, Opus and WAV, with progress and cancellation.
- Playback speed and pitch, equalizer, bass boost, echo, distortion and reverb where supported by the device.
- Trim local audio, export/share audio, sleep timer and configurable accent colors.
- VYNIX application ID: `app.vynix`; default graphite-and-gold branding and VYNIX launcher icon.

Online providers can change their APIs or reject extraction. Some tracks may be unavailable or require authorization. The project does not bypass DRM or paid access, and online playback must be validated on the target device.

## Build an APK (phone-only workflow)

1. Open [VYNIX Android Beta Actions](https://github.com/ramzilzokirov-lgtm/VYNIX/actions/workflows/android-beta.yml).
2. Tap **Run workflow** (or push a change under `app/**` or `scripts/**` on `main`).
3. Open the completed run and download the `VYNIX-Beta-APK` artifact. It contains the debug APK variants and `SHA256SUMS.txt`.

GitHub Actions uses JDK 17 and Android SDK 35. No Android Studio or local PC build is required.

## Source and licensing

The Android player engine is based on [aidentallen/riff-android](https://github.com/aidentallen/riff-android), pinned to commit `d8a4e0df941801c602cb05f6db025460a5a8d8fb`. The workflow checks out that source and applies the branding script in `scripts/rebrand_riff.py` before building. The Riff Android port is GPL-3.0-or-later; preserve the license and attribution notices and make the corresponding modified source available when distributing builds. The original desktop Riff components retain their MIT attribution.

## VYNIX backend

The existing FastAPI service remains in `server/app.py`. Configure `DATABASE_URL` with a PostgreSQL connection string on the hosting provider. The `/health` endpoint reports whether the database is configured and reachable.

## Build status

A workflow run proves only that the CI build and configured checks passed. YouTube/SoundCloud extraction, downloads, playback controls and device-specific effects still require real-device testing before describing the APK as fully verified.
