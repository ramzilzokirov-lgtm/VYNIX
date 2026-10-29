# VYNIX API

FastAPI backend for VYNIX accounts, server-issued profile badges, profile/library metadata, and a server-backed full-track catalogue. The music endpoints are implemented in `music_api.py`; the Android client retains the VYNIX UI and playback engine.

## Endpoints
- `GET /health` — service/database health
- `GET /v1/music/search?q=...&limit=25` — search Audius for published full tracks; returns normalized metadata and a VYNIX stream URL.
- `GET /v1/music/resolve/{track_id}` — resolves a track to the VYNIX stream endpoint so the client can refresh it before playback.
- `GET /v1/music/stream/{track_id}` — redirects to the official Audius stream endpoint; accepts track IDs only and does not proxy arbitrary URLs.
- `GET /v1/spotify/search?q=...` — finds a Spotify track URI for App Remote playback; requires `SPOTIFY_CLIENT_SECRET` in the Render environment.
- `POST /v1/auth/register` — create an account with `{"username":"...","password":"..."}`
- `POST /v1/auth/login` — sign in and receive a bearer session token
- `GET /v1/auth/me` — current account and badge (requires `Authorization: Bearer <token>`)
- `POST /v1/auth/logout` — revoke the current session
- `GET /v1/auth/me/badges` — badges for the signed-in account
- `GET /v1/badges` — public badge catalogue
- `PUT /v1/admin/accounts/{username}/badge` — protected badge assignment using `X-VYNIX-ADMIN-KEY` and JSON `{"badge":"EARLY"}`; configure `VYNIX_ADMIN_KEY` in the hosting environment
- `GET /v1/profile/{user_id}`
- `PUT /v1/profile/{user_id}` — JSON `{"display_name":"VYNIX Listener"}`
- `GET /v1/library/{user_id}`
- `PUT /v1/library/{user_id}` — syncs track metadata only.

## Security and privacy
Passwords are hashed with PBKDF2-HMAC-SHA256 and random per-account salts. Session tokens are random, stored as SHA-256 hashes in PostgreSQL, and expire after 30 days. Usernames are case-insensitive and unique. The default account badge is `LISTENER`; elevated badges must only be granted by trusted server-side administration (never trust a badge sent by the client).

Set `DATABASE_URL` to a PostgreSQL connection string on the hosting provider. The service initializes its tables at startup when the database is configured. Audio files are never uploaded by the account endpoints; only account/profile and track metadata are stored. Music search responses are cached in memory for 45 seconds. The backend redirects to Audius for playback rather than storing or rehosting audio. Use HTTPS in production.

## Spotify playback setup

VYNIX uses the official Spotify Android App Remote SDK to control the Spotify app on the listener's device. The Spotify app must be installed and the listener must authorize VYNIX. Spotify Premium is required for on-demand playback of individual tracks.

Configure these environment variables on the Render web service:
- `SPOTIFY_CLIENT_ID` — VYNIX Spotify app Client ID (defaults to the public app ID configured in the Android client).
- `SPOTIFY_CLIENT_SECRET` — the matching app secret from Spotify Developer Dashboard. This is a server secret: never put it in Android source, Gradle BuildConfig, or a public repository.

In Spotify Developer Dashboard, set Android package name to `app.vynix`, register the exact redirect URI `https://app.vynix/callback`, and register the SHA-1 fingerprint printed by the GitHub Actions job named `Print Spotify Android application fingerprint`. The CI debug signing key is cached to keep this fingerprint stable across ordinary builds.

If Spotify rejects the app, returns API access denied, or the dashboard does not allow Web API use, VYNIX cannot bypass Spotify's developer access restrictions; use the access options Spotify makes available to the app.


## Dotify-inspired integration notes

VYNIX adopts the useful separation of catalogue lookup, stream resolution, refreshable stream URLs, and downloading/playback as separate stages. The public Dotify repository is a Python CLI for authenticated Spotify downloads, not a drop-in HTTP music server. Spotify-protected media, private cookies, Widevine material, or service credentials must not be copied into a public Android app or a shared VYNIX server. Spotify search remains metadata/control only; the server music stream endpoint currently serves published Audius tracks and does not substitute short previews.
