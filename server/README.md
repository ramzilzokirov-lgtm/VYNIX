# VYNIX API

FastAPI backend for VYNIX accounts, server-issued profile badges, and profile/library metadata.

## Endpoints
- `GET /health` — service/database health
- `POST /v1/auth/register` — create an account with `{"username":"...","password":"..."}`
- `POST /v1/auth/login` — sign in and receive a bearer session token
- `GET /v1/auth/me` — current account and badge (requires `Authorization: Bearer <token>`)
- `POST /v1/auth/logout` — revoke the current session
- `GET /v1/auth/me/badges` — badges for the signed-in account
- `GET /v1/badges` — public badge catalogue
- `GET /v1/profile/{user_id}`
- `PUT /v1/profile/{user_id}` — JSON `{"display_name":"VYNIX Listener"}`
- `GET /v1/library/{user_id}`
- `PUT /v1/library/{user_id}` — syncs track metadata only.

## Security and privacy
Passwords are hashed with PBKDF2-HMAC-SHA256 and random per-account salts. Session tokens are random, stored as SHA-256 hashes in PostgreSQL, and expire after 30 days. Usernames are case-insensitive and unique. The default account badge is `LISTENER`; elevated badges must only be granted by trusted server-side administration (never trust a badge sent by the client).

Set `DATABASE_URL` to a PostgreSQL connection string on the hosting provider. The service initializes its tables at startup when the database is configured. Audio files are never uploaded by the account endpoints; only account/profile and track metadata are stored. Use HTTPS in production.
