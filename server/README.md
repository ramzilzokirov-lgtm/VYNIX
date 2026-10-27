# VYNIX API

Minimal backend for VYNIX profile and library metadata.

## Endpoints
- `GET /health` — service/database health
- `GET /v1/profile/{user_id}`
- `PUT /v1/profile/{user_id}` — JSON `{"display_name":"VYNIX Listener"}`
- `GET /v1/library/{user_id}`
- `PUT /v1/library/{user_id}` — JSON `{"items":[{"item_id":"...","title":"...","artist":"","source":"local","favorite":false}]}`

The client-generated UUID is an anonymous profile identifier, **not authentication**. Do not put secrets or private personal data in this API. Add proper authentication before exposing account-sensitive features. Audio files are not uploaded; only metadata is synced.
