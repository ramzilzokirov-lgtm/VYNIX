import os
import uuid
import base64
import hashlib
import hmac
import secrets
import httpx
from datetime import datetime, timezone, timedelta
from fastapi import FastAPI, HTTPException, Header, Query
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


# Spotify search uses a server-side client secret. Never put the secret in the Android APK.
SPOTIFY_CLIENT_ID = os.environ.get("SPOTIFY_CLIENT_ID", "e9907b95f5bf435e8fb260a3eff6a7e8")
SPOTIFY_CLIENT_SECRET = os.environ.get("SPOTIFY_CLIENT_SECRET", "")
_spotify_token = ""
_spotify_token_expires_at = 0.0

def get_spotify_access_token():
    global _spotify_token, _spotify_token_expires_at
    import time
    if _spotify_token and time.time() < _spotify_token_expires_at - 30:
        return _spotify_token
    if not SPOTIFY_CLIENT_SECRET:
        raise HTTPException(status_code=503, detail="spotify_client_secret_not_configured")
    try:
        response = httpx.post(
            "https://accounts.spotify.com/api/token",
            data={"grant_type": "client_credentials"},
            auth=(SPOTIFY_CLIENT_ID, SPOTIFY_CLIENT_SECRET),
            timeout=10.0,
        )
        response.raise_for_status()
        payload = response.json()
        _spotify_token = payload["access_token"]
        _spotify_token_expires_at = time.time() + int(payload.get("expires_in", 3600))
        return _spotify_token
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        import logging
        logging.getLogger("vynix.spotify").warning("Spotify token endpoint returned HTTP %s", status)
        if status in (400, 401, 403):
            raise HTTPException(status_code=503, detail="spotify_credentials_rejected")
        if status == 429:
            raise HTTPException(status_code=503, detail="spotify_auth_rate_limited")
        raise HTTPException(status_code=502, detail="spotify_auth_upstream_error")
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="spotify_auth_timeout")
    except httpx.HTTPError as exc:
        import logging
        logging.getLogger("vynix.spotify").warning("Spotify token network error: %s", type(exc).__name__)
        raise HTTPException(status_code=502, detail="spotify_auth_unavailable")
    except Exception as exc:
        import logging
        logging.getLogger("vynix.spotify").exception("Unexpected Spotify token error: %s", type(exc).__name__)
        raise HTTPException(status_code=502, detail="spotify_auth_unavailable")

@app.get("/v1/spotify/search")
def spotify_search(q: str = Query(min_length=2, max_length=180)):
    token = get_spotify_access_token()
    try:
        params = {"q": q.strip(), "type": "track", "limit": 5}
        response = httpx.get("https://api.spotify.com/v1/search", params=params,
            headers={"Authorization": f"Bearer {token}"}, timeout=10.0)
        if response.status_code == 401:
            global _spotify_token, _spotify_token_expires_at
            _spotify_token, _spotify_token_expires_at = "", 0.0
            token = get_spotify_access_token()
            response = httpx.get("https://api.spotify.com/v1/search", params=params,
                headers={"Authorization": f"Bearer {token}"}, timeout=10.0)
        if response.status_code == 403:
            raise HTTPException(status_code=503, detail="spotify_web_api_access_denied")
        response.raise_for_status()
        items = response.json().get("tracks", {}).get("items", [])
        if not items:
            raise HTTPException(status_code=404, detail="spotify_track_not_found")
        import re
        def norm(value):
            return re.sub(r"[^\\w ]", " ", value.casefold()).strip()
        wanted_title = norm(q.split(" - ")[0])
        chosen = items[0]
        for item in items:
            title = item.get("name", "")
            artists = " ".join(a.get("name", "") for a in item.get("artists", []))
            if norm(title) == wanted_title or (norm(title) in norm(q) and norm(artists) in norm(q)):
                chosen = item
                break
        return {
            "spotify_uri": chosen.get("uri", ""),
            "id": chosen.get("id", ""),
            "title": chosen.get("name", ""),
            "artist": ", ".join(a.get("name", "") for a in chosen.get("artists", [])),
            "album": chosen.get("album", {}).get("name", ""),
            "duration_ms": chosen.get("duration_ms", 0),
            "artwork": (chosen.get("album", {}).get("images") or [{}])[0].get("url", ""),
        }
    except HTTPException:
        raise
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="spotify_search_timeout")
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        import logging
        logging.getLogger("vynix.spotify").warning("Spotify search endpoint returned HTTP %s", status)
        if status == 429:
            raise HTTPException(status_code=503, detail="spotify_rate_limited")
        if status in (401, 403):
            raise HTTPException(status_code=503, detail="spotify_web_api_access_denied")
        raise HTTPException(status_code=502, detail="spotify_search_upstream_error")
    except httpx.HTTPError as exc:
        import logging
        logging.getLogger("vynix.spotify").warning("Spotify search network error: %s", type(exc).__name__)
        raise HTTPException(status_code=502, detail="spotify_search_unavailable")
    except Exception as exc:
        import logging
        logging.getLogger("vynix.spotify").exception("Unexpected Spotify search error: %s", type(exc).__name__)
        raise HTTPException(status_code=502, detail="spotify_search_unavailable")

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


# Spotify is the sole online music catalog and playback destination.
@app.get("/v1/spotify/search-tracks")
def spotify_search_tracks(q: str = Query(min_length=2, max_length=180), limit: int = Query(default=20, ge=1, le=30)):
    token = get_spotify_access_token()
    # Spotify currently accepts at most 10 results per item type.
    # Keep accepting larger client limits for compatibility, but clamp upstream requests.
    params = {"q": q.strip(), "type": "track", "limit": min(limit, 10)}
    try:
        response = httpx.get(
            "https://api.spotify.com/v1/search",
            params=params,
            headers={"Authorization": f"Bearer {token}"},
            timeout=10.0,
        )
        if response.status_code == 401:
            global _spotify_token, _spotify_token_expires_at
            _spotify_token, _spotify_token_expires_at = "", 0.0
            token = get_spotify_access_token()
            response = httpx.get(
                "https://api.spotify.com/v1/search",
                params=params,
                headers={"Authorization": f"Bearer {token}"},
                timeout=10.0,
            )
        if response.status_code == 403:
            raise HTTPException(status_code=503, detail="spotify_web_api_access_denied")
        response.raise_for_status()
        payload = response.json()
        tracks = []
        for item in payload.get("tracks", {}).get("items", []):
            track_id = str(item.get("id") or "")
            title = str(item.get("name") or "").strip()
            if not track_id or not title:
                continue
            album = item.get("album") or {}
            artists = item.get("artists") or []
            tracks.append({
                "id": track_id,
                "spotify_uri": item.get("uri") or f"spotify:track:{track_id}",
                "title": title,
                "artist": ", ".join(str(artist.get("name") or "") for artist in artists),
                "album": str(album.get("name") or ""),
                "duration_ms": int(item.get("duration_ms") or 0),
                "artwork": (album.get("images") or [{}])[0].get("url", ""),
                "source": "Spotify",
                "is_preview": False,
            })
        return {
            "query": q.strip(),
            "source": "Spotify",
            "providers": ["Spotify"],
            "count": len(tracks),
            "tracks": tracks,
        }
    except HTTPException:
        raise
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="spotify_search_timeout")
    except httpx.HTTPStatusError as exc:
        status = exc.response.status_code
        import logging
        logging.getLogger("vynix.spotify").warning("Spotify track search returned HTTP %s", status)
        if status == 429:
            raise HTTPException(status_code=503, detail="spotify_rate_limited")
        if status in (401, 403):
            raise HTTPException(status_code=503, detail="spotify_web_api_access_denied")
        raise HTTPException(status_code=502, detail="spotify_search_upstream_error")
    except httpx.HTTPError as exc:
        import logging
        logging.getLogger("vynix.spotify").warning("Spotify track search network error: %s", type(exc).__name__)
        raise HTTPException(status_code=502, detail="spotify_search_unavailable")
    except Exception as exc:
        import logging
        logging.getLogger("vynix.spotify").exception("Unexpected Spotify track search error: %s", type(exc).__name__)
        raise HTTPException(status_code=502, detail="spotify_search_unavailable")


# Public no-login playback catalog. Spotify provides discovery metadata only;
# Audius supplies playable tracks through its public API.
AUDIUS_API_BASE = "https://api.audius.co/v1"
AUDIUS_APP_NAME = os.environ.get("AUDIUS_APP_NAME", "VYNIX")

def audius_request(path: str, params: dict | None = None):
    query = dict(params or {})
    query.setdefault("app_name", AUDIUS_APP_NAME)
    try:
        response = httpx.get(f"{AUDIUS_API_BASE}{path}", params=query, timeout=12.0, follow_redirects=True)
        response.raise_for_status()
        payload = response.json()
        if not isinstance(payload, dict):
            raise HTTPException(status_code=502, detail="audius_invalid_response")
        return payload
    except HTTPException:
        raise
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="audius_timeout")
    except httpx.HTTPStatusError as exc:
        import logging
        logging.getLogger("vynix.audius").warning("Audius returned HTTP %s for %s", exc.response.status_code, path)
        raise HTTPException(status_code=502, detail="audius_catalog_unavailable")
    except (httpx.HTTPError, ValueError):
        raise HTTPException(status_code=502, detail="audius_catalog_unavailable")

@app.get("/v1/music/search")
def music_search(q: str = Query(min_length=2, max_length=180), limit: int = Query(default=20, ge=1, le=30)):
    query = q.strip()
    safe_limit = min(limit, 30)
    # Audius supports popularity sorting. Merge popular and relevance-ranked
    # searches so well-known matches aren't buried by low-play-count uploads.
    result_sets = []
    for sort_method in ("popular", "relevant"):
        try:
            payload = audius_request("/tracks/search", {
                "query": query,
                "limit": safe_limit,
                "sort_method": sort_method,
            })
            result_sets.extend(payload.get("data") or [])
        except HTTPException:
            if not result_sets:
                raise

    tracks = []
    seen_ids = set()
    for item in result_sets:
        if not isinstance(item, dict):
            continue
        track_id = str(item.get("id") or "").strip()
        title = str(item.get("title") or "").strip()
        if not track_id or not title or track_id in seen_ids:
            continue
        seen_ids.add(track_id)
        user = item.get("user") or {}
        artwork = item.get("artwork") or {}
        artwork_url = next((str(artwork.get(size)) for size in ("480x480", "1000x1000", "150x150") if artwork.get(size)), "")
        try:
            duration_seconds = max(0, int(float(item.get("duration") or 0)))
        except (ValueError, TypeError):
            duration_seconds = 0
        tracks.append({
            "id": track_id, "title": title,
            "artist": str(user.get("name") or item.get("artist_name") or "Неизвестный исполнитель"),
            "album": str(item.get("album") or "Audius"),
            "duration_seconds": duration_seconds,
            "artwork": artwork_url, "source": "Audius", "is_preview": False,
            "permalink": str(item.get("permalink") or ""),
        })
        if len(tracks) >= safe_limit:
            break
    return {"query": query, "source": "Audius", "providers": ["Audius"], "count": len(tracks), "tracks": tracks}

@app.get("/v1/music/resolve/{track_id}")
def music_resolve(track_id: str):
    safe_id = track_id.strip()
    if not safe_id or len(safe_id) > 180 or "/" in safe_id:
        raise HTTPException(status_code=400, detail="invalid_track_id")
    # Media3 follows Audius' official redirect to the playable stream.
    from urllib.parse import urlencode
    stream_url = f"{AUDIUS_API_BASE}/tracks/{safe_id}/stream?{urlencode({'app_name': AUDIUS_APP_NAME})}"
    return {"id": safe_id, "stream_url": stream_url, "source": "Audius"}


# Lyrics are fetched from LRCLIB's public API. VYNIX stores no lyric files;
# the Android client may cache responses locally for offline viewing.
LRCLIB_API_BASE = "https://lrclib.net/api"

@app.get("/v1/music/lyrics/search")
async def music_lyrics_search(q: str, limit: int = 10):
    query = q.strip()
    if len(query) < 2:
        raise HTTPException(status_code=400, detail="query_too_short")
    limit = max(1, min(limit, 20))
    try:
        async with httpx.AsyncClient(timeout=10.0, follow_redirects=True) as client:
            response = await client.get(
                f"{LRCLIB_API_BASE}/search",
                params={"q": query},
                headers={"User-Agent": "VYNIX/0.1 (Android music player)"},
            )
            if response.status_code == 404:
                return {"query": query, "count": 0, "lyrics": []}
            response.raise_for_status()
            payload = response.json()
        lyrics = []
        if isinstance(payload, list):
            for item in payload[:limit]:
                if not isinstance(item, dict):
                    continue
                synced = item.get("syncedLyrics")
                plain = item.get("plainLyrics")
                lyrics.append({
                    "id": item.get("id"),
                    "track_name": item.get("trackName") or "",
                    "artist_name": item.get("artistName") or "",
                    "album_name": item.get("albumName") or "",
                    "duration": item.get("duration"),
                    "instrumental": bool(item.get("instrumental", False)),
                    "synced_lyrics": synced if isinstance(synced, str) and synced.strip() else None,
                    "plain_lyrics": plain if isinstance(plain, str) and plain.strip() else None,
                    "source": "LRCLIB",
                })
        return {"query": query, "count": len(lyrics), "lyrics": lyrics}
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="lyrics_provider_timeout")
    except (httpx.HTTPError, ValueError):
        raise HTTPException(status_code=502, detail="lyrics_provider_unavailable")

@app.get("/v1/music/lyrics")
async def music_lyrics(track_name: str, artist_name: str = "", album_name: str = "", duration: int = 0):
    title = track_name.strip()
    artist = artist_name.strip()
    album = album_name.strip()
    if len(title) < 1:
        raise HTTPException(status_code=400, detail="track_name_required")
    if len(title) > 240 or len(artist) > 240 or len(album) > 240:
        raise HTTPException(status_code=400, detail="metadata_too_long")
    params = {"track_name": title, "artist_name": artist, "album_name": album}
    if duration > 0:
        params["duration"] = min(duration, 86400)
    try:
        async with httpx.AsyncClient(timeout=10.0, follow_redirects=True) as client:
            response = await client.get(
                f"{LRCLIB_API_BASE}/get",
                params=params,
                headers={"User-Agent": "VYNIX/0.1 (Android music player)"},
            )
            if response.status_code == 404:
                return {"found": False, "track_name": title, "artist_name": artist, "source": "LRCLIB"}
            response.raise_for_status()
            item = response.json()
        return {
            "found": bool(item.get("syncedLyrics") or item.get("plainLyrics")),
            "id": item.get("id"),
            "track_name": item.get("trackName") or title,
            "artist_name": item.get("artistName") or artist,
            "album_name": item.get("albumName") or album,
            "duration": item.get("duration"),
            "instrumental": bool(item.get("instrumental", False)),
            "synced_lyrics": item.get("syncedLyrics") or None,
            "plain_lyrics": item.get("plainLyrics") or None,
            "source": "LRCLIB",
        }
    except httpx.TimeoutException:
        raise HTTPException(status_code=504, detail="lyrics_provider_timeout")
    except (httpx.HTTPError, ValueError):
        raise HTTPException(status_code=502, detail="lyrics_provider_unavailable")
