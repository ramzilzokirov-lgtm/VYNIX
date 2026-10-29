# VYNIX API

FastAPI backend for VYNIX accounts, server-issued profile badges, and profile/library metadata.

## Endpoints
- `GET /health` — service/database health
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

Set `DATABASE_URL` to a PostgreSQL connection string on the hosting provider. The service initializes its tables at startup when the database is configured. Audio files are never uploaded by the account endpoints; only account/profile and track metadata are stored. Use HTTPS in production.

## Spotify playback setup

VYNIX uses the official Spotify Android App Remote SDK to control the Spotify app on the listener's device. The Spotify app must be installed and the listener must authorize VYNIX. Spotify Premium is required for on-demand playback of individual tracks.

Configure these environment variables on the Render web service:
- `SPOTIFY_CLIENT_ID` — VYNIX Spotify app Client ID (defaults to the public app ID configured in the Android client).
- `SPOTIFY_CLIENT_SECRET` — the matching app secret from Spotify Developer Dashboard. This is a server secret: never put it in Android source, Gradle BuildConfig, or a public repository.

In Spotify Developer Dashboard, set Android package name to `app.vynix`, register the exact redirect URI `https://app.vynix/callback`, and register the SHA-1 fingerprint printed by the GitHub Actions job named `Print Spotify Android application fingerprint`. The CI debug signing key is cached to keep this fingerprint stable across ordinary builds.

If Spotify rejects the app, returns API access denied, or the dashboard does not allow Web API use, VYNIX cannot bypass Spotify's developer access restrictions; use the access options Spotify makes available to the app.
