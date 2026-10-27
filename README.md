# VYNIX Beta

VYNIX is a native Android music-player beta with a dark graphite interface and gold accents.

## Included
- Five-tab navigation: Home, Library, Search, Radio, Profile.
- Import local audio files using Android's document picker.
- Local playback powered by AndroidX Media3 ExoPlayer.
- Unified search across public Deezer and Apple Music/iTunes catalogs.
- Quick links to Spotify, SoundCloud, VK Music, YouTube Music, Apple Music, Deezer, and Yandex Music.
- Persistent local profile ID and display name, with optional server synchronization.
- Native profile settings dialogs for appearance preference, player preferences, integrations, and app information.
- GitHub Actions debug APK artifact and SHA-256 checksum.

## Build an APK
1. Open [GitHub Actions](https://github.com/ramzilzokirov-lgtm/VYNIX/actions/workflows/android-beta.yml).
2. Run **VYNIX Android Beta** manually, or push a change under `app/**` on `main`.
3. Open the completed run and download the `VYNIX-Beta-APK` artifact.

## Backend
The FastAPI service is in `server/app.py`. Configure `DATABASE_URL` with a PostgreSQL connection string on the hosting provider. The `/health` endpoint reports whether the database is configured and reachable.

## Known limitations
- Search results open their source service; this does not bypass subscriptions, DRM, or provider authentication.
- Radio is currently a placeholder until a legitimate stream source is configured.
- The current player focuses on local files; background playback media session, seek/progress controls, queue persistence, and real audio-effect controls still need implementation and device testing.
- Server library sync currently uploads local-library metadata; local audio itself is not uploaded.
- A successful GitHub Actions run and real-device testing are required before calling any build release-ready.
