import os
import uuid
import base64
import hashlib
import hmac
import secrets
import httpx
from datetime import datetime, timezone, timedelta
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel, Field
import psycopg
from psycopg.rows import dict_row

DATABASE_URL = os.environ.get("DATABASE_URL", "")
app = FastAPI(title="VYNIX API", version="1.0.0")

def connect():
    if not DATABASE_URL:
        raise RuntimeError("DATABASE_URL is not configured")
    return psycopg.connect(DATABASE_URL, row_factory=dict_row, connect_timeout=8)

def init_db():
    with connect() as conn:
        conn.execute("""
        CREATE TABLE IF NOT EXISTS profiles (
          user_id TEXT PRIMARY KEY, display_name TEXT NOT NULL DEFAULT 'VYNIX Listener',
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        conn.execute("""
        CREATE TABLE IF NOT EXISTS library_items (
          user_id TEXT NOT NULL, item_id TEXT NOT NULL, title TEXT NOT NULL,
          artist TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT 'local',
          favorite BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          PRIMARY KEY(user_id, item_id)
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS library_items_user_idx ON library_items(user_id)")
        conn.execute("""
        CREATE TABLE IF NOT EXISTS accounts (
          account_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          username TEXT NOT NULL UNIQUE,
          password_hash TEXT NOT NULL,
          badge TEXT NOT NULL DEFAULT 'LISTENER',
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        conn.execute("""
        CREATE TABLE IF NOT EXISTS sessions (
          token_hash TEXT PRIMARY KEY,
          account_id UUID NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
          expires_at TIMESTAMPTZ NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        conn.execute("CREATE INDEX IF NOT EXISTS sessions_expiry_idx ON sessions(expires_at)")

@app.on_event("startup")
def startup():
    if DATABASE_URL:
        init_db()

def valid_user(user_id: str):
    try:
        return str(uuid.UUID(user_id))
    except (ValueError, AttributeError):
        raise HTTPException(status_code=400, detail="user_id must be a UUID")

class ProfileUpdate(BaseModel):
    display_name: str = Field(default="VYNIX Listener", min_length=1, max_length=40)

class LibraryItem(BaseModel):
    item_id: str = Field(min_length=1, max_length=180)
    title: str = Field(min_length=1, max_length=240)
    artist: str = Field(default="", max_length=240)
    source: str = Field(default="local", max_length=40)
    favorite: bool = False

class LibrarySync(BaseModel):
    items: list[LibraryItem] = Field(default_factory=list, max_length=1000)

class AccountCredentials(BaseModel):
    username: str = Field(min_length=3, max_length=32, pattern=r"^[a-zA-Z0-9_.-]+$")
    password: str = Field(min_length=8, max_length=128)

class BadgeUpdate(BaseModel):
    badge: str = Field(pattern=r"^(LISTENER|EARLY|CREATOR|TEAM)$")

def hash_password(password: str, salt: bytes | None = None) -> str:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 310000)
    return base64.urlsafe_b64encode(salt).decode().rstrip("=") + "$" + base64.urlsafe_b64encode(digest).decode().rstrip("=")

def verify_password(password: str, stored: str) -> bool:
    try:
        salt_text, digest_text = stored.split("$", 1)
        salt = base64.urlsafe_b64decode(salt_text + "=" * (-len(salt_text) % 4))
        expected = base64.urlsafe_b64decode(digest_text + "=" * (-len(digest_text) % 4))
        actual = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 310000)
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False

def token_digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()

def require_account(authorization: str | None):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="authentication_required")
    token = authorization[7:].strip()
    if len(token) < 32:
        raise HTTPException(status_code=401, detail="invalid_token")
    try:
        with connect() as conn:
            row = conn.execute("""
              SELECT a.account_id, a.username, a.badge
              FROM sessions s JOIN accounts a ON a.account_id=s.account_id
              WHERE s.token_hash=%s AND s.expires_at > NOW()
            """, (token_digest(token),)).fetchone()
            if not row:
                raise HTTPException(status_code=401, detail="session_expired")
            return row
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

def create_session(conn, account_id):
    token = secrets.token_urlsafe(48)
    conn.execute("INSERT INTO sessions(token_hash, account_id, expires_at) VALUES (%s,%s,%s)",
                 (token_digest(token), account_id, datetime.now(timezone.utc) + timedelta(days=30)))
    return token

@app.get("/health")
def health():
    if not DATABASE_URL:
        return {"status": "degraded", "database": "not_configured", "service": "vynix-api"}
    try:
        with connect() as conn:
            conn.execute("SELECT 1")
        return {"status": "ok", "database": "connected", "service": "vynix-api"}
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.post("/v1/auth/register")
def register_account(payload: AccountCredentials):
    username = payload.username.strip().lower()
    try:
        with connect() as conn:
            account_id = conn.execute("""
              INSERT INTO accounts(username, password_hash) VALUES (%s,%s)
              RETURNING account_id
            """, (username, hash_password(payload.password))).fetchone()["account_id"]
            token = create_session(conn, account_id)
            account = conn.execute("SELECT account_id, username, badge, created_at FROM accounts WHERE account_id=%s", (account_id,)).fetchone()
            return {"token": token, "token_type": "Bearer", "expires_in": 2592000, "account": account}
    except psycopg.errors.UniqueViolation:
        raise HTTPException(status_code=409, detail="username_taken")
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.post("/v1/auth/login")
def login_account(payload: AccountCredentials):
    username = payload.username.strip().lower()
    try:
        with connect() as conn:
            account = conn.execute("SELECT account_id, username, password_hash, badge, created_at FROM accounts WHERE username=%s", (username,)).fetchone()
            if not account or not verify_password(payload.password, account["password_hash"]):
                raise HTTPException(status_code=401, detail="invalid_credentials")
            token = create_session(conn, account["account_id"])
            account.pop("password_hash", None)
            return {"token": token, "token_type": "Bearer", "expires_in": 2592000, "account": account}
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.get("/v1/auth/me")
def current_account(authorization: str | None = Header(default=None)):
    account = require_account(authorization)
    return {"account": account}

@app.post("/v1/auth/logout")
def logout_account(authorization: str | None = Header(default=None)):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="authentication_required")
    token = authorization[7:].strip()
    try:
        with connect() as conn:
            conn.execute("DELETE FROM sessions WHERE token_hash=%s", (token_digest(token),))
        return {"ok": True}
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.get("/v1/badges")
def list_badges():
    return {"badges": [
        {"id": "LISTENER", "name": "Слушатель VYNIX", "description": "Стандартный профиль"},
        {"id": "EARLY", "name": "Ранний пользователь", "description": "Бейдж раннего доступа"},
        {"id": "CREATOR", "name": "Создатель плейлистов", "description": "Для музыкальных кураторов"},
        {"id": "TEAM", "name": "Команда VYNIX", "description": "Выдаётся администрацией"}
    ]}

@app.get("/v1/auth/me/badges")
def my_badges(authorization: str | None = Header(default=None)):
    account = require_account(authorization)
    badge = account["badge"]
    names = {"LISTENER": "Слушатель VYNIX", "EARLY": "Ранний пользователь", "CREATOR": "Создатель плейлистов", "TEAM": "Команда VYNIX"}
    return {"badges": [{"id": badge, "name": names.get(badge, "Пользователь VYNIX")} ]}

@app.put("/v1/admin/accounts/{username}/badge")
def assign_badge(username: str, payload: BadgeUpdate, x_vynix_admin_key: str | None = Header(default=None)):
    admin_key = os.environ.get("VYNIX_ADMIN_KEY", "")
    if not admin_key:
        raise HTTPException(status_code=503, detail="admin_key_not_configured")
    if not x_vynix_admin_key or not hmac.compare_digest(x_vynix_admin_key, admin_key):
        raise HTTPException(status_code=403, detail="forbidden")
    try:
        with connect() as conn:
            row = conn.execute(
                "UPDATE accounts SET badge=%s WHERE username=%s RETURNING account_id, username, badge",
                (payload.badge, username.strip().lower())
            ).fetchone()
            if not row:
                raise HTTPException(status_code=404, detail="account_not_found")
            return {"account": row}
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.get("/v1/profile/{user_id}")
def get_profile(user_id: str):
    user_id = valid_user(user_id)
    try:
        with connect() as conn:
            conn.execute("INSERT INTO profiles(user_id) VALUES (%s) ON CONFLICT(user_id) DO NOTHING", (user_id,))
            row = conn.execute("SELECT user_id, display_name, created_at, updated_at FROM profiles WHERE user_id=%s", (user_id,)).fetchone()
            return row
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.put("/v1/profile/{user_id}")
def update_profile(user_id: str, payload: ProfileUpdate):
    user_id = valid_user(user_id)
    try:
        with connect() as conn:
            conn.execute("""INSERT INTO profiles(user_id, display_name) VALUES (%s,%s)
            ON CONFLICT(user_id) DO UPDATE SET display_name=EXCLUDED.display_name, updated_at=NOW()""",
            (user_id, payload.display_name.strip()))
            return conn.execute("SELECT user_id, display_name, created_at, updated_at FROM profiles WHERE user_id=%s", (user_id,)).fetchone()
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.get("/v1/library/{user_id}")
def get_library(user_id: str):
    user_id = valid_user(user_id)
    try:
        with connect() as conn:
            rows = conn.execute("""SELECT item_id, title, artist, source, favorite, updated_at
            FROM library_items WHERE user_id=%s ORDER BY updated_at DESC""", (user_id,)).fetchall()
            return {"items": rows, "count": len(rows)}
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")

@app.put("/v1/library/{user_id}")
def sync_library(user_id: str, payload: LibrarySync):
    user_id = valid_user(user_id)
    if len(payload.items) > 1000:
        raise HTTPException(status_code=413, detail="too_many_items")
    try:
        with connect() as conn:
            for item in payload.items:
                conn.execute("""INSERT INTO library_items(user_id,item_id,title,artist,source,favorite,updated_at)
                VALUES (%s,%s,%s,%s,%s,%s,NOW())
                ON CONFLICT(user_id,item_id) DO UPDATE SET title=EXCLUDED.title, artist=EXCLUDED.artist,
                source=EXCLUDED.source, favorite=EXCLUDED.favorite, updated_at=NOW()""",
                (user_id, item.item_id, item.title, item.artist, item.source, item.favorite))
            rows = conn.execute("""SELECT item_id,title,artist,source,favorite,updated_at
            FROM library_items WHERE user_id=%s ORDER BY updated_at DESC""", (user_id,)).fetchall()
            return {"items": rows, "count": len(rows)}
    except Exception:
        raise HTTPException(status_code=503, detail="database_unavailable")


# Music discovery uses Audius' public API. It does not scrape paid services or
# claim that catalog metadata from Spotify/Apple is a playable full track.
AUDIUS_APP_NAME = os.environ.get("AUDIUS_APP_NAME", "VYNIX")
AUDIUS_API_BASE = "https://api.audius.co/v1"

@app.get("/v1/music/search")
async def music_search(q: str, limit: int = 20):
    query = q.strip()
    if len(query) < 2:
        raise HTTPException(status_code=400, detail="query_too_short")
    limit = max(1, min(limit, 30))
    try:
        async with httpx.AsyncClient(timeout=12.0, follow_redirects=True) as client:
            response = await client.get(
                f"{AUDIUS_API_BASE}/tracks/search",
                params={"query": query, "limit": limit, "app_name": AUDIUS_APP_NAME},
            )
            response.raise_for_status()
            payload = response.json()
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="music_provider_timeout")
    except (httpx.HTTPError, ValueError):
        raise HTTPException(status_code=502, detail="music_provider_unavailable")
    results = []
    for track in payload.get("data", []):
        track_id = str(track.get("id", "")).strip()
        title = str(track.get("title", "")).strip()
        if not track_id or not title:
            continue
        user = track.get("user") or {}
        results.append({
            "id": track_id,
            "title": title,
            "artist": str(user.get("name") or "Unknown artist"),
            "artwork": (track.get("artwork") or {}).get("150x150"),
            "duration_seconds": track.get("duration"),
            "source": "Audius",
            "stream_url": f"/v1/music/stream/{track_id}",
            "permalink": track.get("permalink"),
        })
    return {"query": query, "source": "Audius", "count": len(results), "tracks": results}

@app.get("/v1/music/resolve/{track_id}")
async def music_resolve(track_id: str):
    if not track_id or len(track_id) > 120 or not track_id.replace("-", "").replace("_", "").isalnum():
        raise HTTPException(status_code=400, detail="invalid_track_id")
    try:
        async with httpx.AsyncClient(timeout=12.0, follow_redirects=False) as client:
            response = await client.get(
                f"{AUDIUS_API_BASE}/tracks/{track_id}/stream",
                params={"app_name": AUDIUS_APP_NAME},
            )
        if response.status_code in (301, 302, 303, 307, 308) and response.headers.get("location"):
            return {"track_id": track_id, "source": "Audius", "stream_url": response.headers["location"]}
        if response.status_code == 404:
            raise HTTPException(status_code=404, detail="track_stream_not_found")
        response.raise_for_status()
        return {"track_id": track_id, "source": "Audius", "stream_url": str(response.url)}
    except HTTPException:
        raise
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="music_provider_timeout")
    except httpx.HTTPError:
        raise HTTPException(status_code=502, detail="music_provider_unavailable")

@app.get("/v1/music/stream/{track_id}")
async def music_stream(track_id: str):
    resolved = await music_resolve(track_id)
    # Redirect clients to the provider's official stream endpoint; do not store
    # copyrighted audio on VYNIX servers or proxy third-party content.
    from fastapi.responses import RedirectResponse
    return RedirectResponse(url=resolved["stream_url"], status_code=307)
