import os
import uuid
from datetime import datetime, timezone
from fastapi import FastAPI, HTTPException
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
