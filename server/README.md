# VYNIX API

FastAPI backend for VYNIX accounts, server-issued profile badges, and profile/library metadata. The Android client owns the VYNIX interface and playback engine. Public audio streams are resolved on-device from supported YouTube and SoundCloud sources; the backend does not proxy or rehost audio.

## Endpoints

- `GET /health` — service and database health.
- `GET /v1/spotify/search-tracks?q=...` — Spotify catalogue metadata; requires `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` in the Render environment.
- `GET /v1/spotify/playlist-tracks?playlist_id=...` — metadata for a public Spotify playlist; no audio URLs or previews are returned.
- `POST /v1/auth/register` — create an account with `{"username":"...","password":"..."}`.
- `POST /v1/auth/login` — sign in and receive a bearer session token.
- `GET /v1/auth/me` — current account and badge (requires `Authorization: Bearer <token>`).
- `POST /v1/auth/logout` — revoke the current session.
- `GET /v1/auth/me/badges` — badges for the signed-in account.
- `GET /v1/badges` — public badge catalogue.
- `PUT /v1/admin/accounts/{username}/badge` — protected badge assignment using `X-VYNIX-ADMIN-KEY` and JSON `{"badge":"EARLY"}`; configure `VYNIX_ADMIN_KEY` in the hosting environment.
- `GET /v1/profile/{user_id}` and `PUT /v1/profile/{user_id}` — read or update profile metadata.
- `GET /v1/library/{user_id}` and `PUT /v1/library/{user_id}` — read or sync library metadata only.
- `GET /v1/music/lyrics/search` and `GET /v1/music/lyrics` — search and retrieve lyrics from LRCLIB.

## Playback design

YouTube and SoundCloud are the public playback sources. Their media URLs can expire, so the Android client resolves the stream immediately before playback rather than storing a stream URL as permanent track data. Spotify and Deezer are catalogue/metadata sources only; a metadata result is not treated as an audio stream. The client searches supported public sources for a matching full track and reports an error rather than substituting a short preview when no match is found.

Audius is not integrated. VYNIX does not bypass DRM, paid access, private cookies, or provider authentication. The public Dotify repository is a Python CLI, not a drop-in HTTP music server; its service credentials or protected media must not be copied into a public app or shared backend.

## Security and privacy

Passwords are hashed with PBKDF2-HMAC-SHA256 and random per-account salts. Session tokens are random, stored as SHA-256 hashes in PostgreSQL, and expire after 30 days. Usernames are case-insensitive and unique. The default account badge is `LISTENER`; elevated badges must only be granted by trusted server-side administration (never trust a badge sent by the client).

Set `DATABASE_URL` to a PostgreSQL connection string on the hosting provider. The service initializes its tables at startup when the database is configured. Account endpoints store account/profile and track metadata, not audio files. Use HTTPS in production.

## Spotify catalogue setup

Configure `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` on the Render service using values from the Spotify Developer Dashboard. Keep the client secret on the server only—never put it in Android source, Gradle BuildConfig, or a public repository. If Spotify denies Web API access or rate-limits the app, VYNIX cannot bypass those restrictions; public YouTube/SoundCloud search remains independent of Spotify metadata.
