"""VYNIX music-provider API.

This module provides a server-side catalogue and stream resolver for Audius,
whose API exposes full tracks published by their rightsholders. Spotify is kept
as metadata/control integration only; this service does not proxy protected
Spotify/Deezer audio or expose user credentials.
"""
from __future__ import annotations

import logging
import re
import time
from typing import Any
from urllib.parse import quote

import httpx
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import RedirectResponse

logger = logging.getLogger("vynix.music")
router = APIRouter(tags=["music"])

AUDIUS_API = "https://api.audius.co/v1"
AUDIUS_APP_NAME = "VYNIX"
TRACK_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,180}$")
_CACHE_TTL_SECONDS = 45
_search_cache: dict[str, tuple[float, list[dict[str, Any]]]] = {}
_MAX_CACHE_ENTRIES = 100


def _client() -> httpx.Client:
    return httpx.Client(
        timeout=httpx.Timeout(12.0, connect=5.0),
        follow_redirects=True,
        headers={
            "User-Agent": "VYNIX-Music-API/1.0",
            "Accept": "application/json",
        },
    )


def _artwork(track: dict[str, Any]) -> str | None:
    value = track.get("artwork")
    if isinstance(value, str) and value.startswith(("https://", "http://")):
        return value
    if isinstance(value, dict):
        for key in ("480x480", "1000x1000", "150x150"):
            candidate = value.get(key)
            if isinstance(candidate, str) and candidate.startswith("https://"):
                return candidate
    return None


def _normalise_track(item: dict[str, Any]) -> dict[str, Any] | None:
    track_id = str(item.get("id") or "").strip()
    title = str(item.get("title") or "").strip()
    if not track_id or not TRACK_ID_RE.fullmatch(track_id) or not title:
        return None
    user = item.get("user") if isinstance(item.get("user"), dict) else {}
    artist = str(item.get("artist") or user.get("name") or "Unknown artist").strip()
    duration = item.get("duration_seconds", item.get("duration", 0))
    try:
        duration_seconds = max(0, int(float(duration or 0)))
    except (ValueError, TypeError, OverflowError):
        duration_seconds = 0
    return {
        "id": track_id,
        "title": title[:240],
        "artist": artist[:240],
        "album": str(item.get("album") or "Audius")[:240],
        "duration_seconds": duration_seconds,
        "duration_ms": duration_seconds * 1000,
        "artwork": _artwork(item),
        "source": "Audius",
        "is_preview": False,
        "stream_url": f"/v1/music/stream/{quote(track_id, safe='')}",
    }


def _fetch_search(query: str, limit: int) -> list[dict[str, Any]]:
    with _client() as client:
        response = client.get(
            f"{AUDIUS_API}/tracks/search",
            params={"query": query, "limit": limit, "app_name": AUDIUS_APP_NAME},
        )
        if response.status_code == 429:
            raise HTTPException(status_code=503, detail="audius_rate_limited")
        response.raise_for_status()
        payload = response.json()
    data = payload.get("data") or payload.get("tracks") or []
    if not isinstance(data, list):
        return []
    result = []
    seen: set[str] = set()
    for item in data:
        if not isinstance(item, dict):
            continue
        track = _normalise_track(item)
        if track and track["id"] not in seen:
            seen.add(track["id"])
            result.append(track)
    return result


@router.get("/v1/music/search")
def search_music(
    q: str = Query(min_length=2, max_length=180),
    limit: int = Query(default=25, ge=1, le=50),
):
    """Search full-track catalogue entries; no short preview URLs are returned."""
    query = " ".join(q.split())
    key = f"{query.casefold()}::{limit}"
    now = time.monotonic()
    cached = _search_cache.get(key)
    if cached and now - cached[0] < _CACHE_TTL_SECONDS:
        tracks = cached[1]
    else:
        try:
            tracks = _fetch_search(query, limit)
        except HTTPException:
            raise
        except httpx.TimeoutException:
            raise HTTPException(status_code=504, detail="audius_search_timeout")
        except httpx.HTTPStatusError as exc:
            logger.warning("Audius search returned HTTP %s", exc.response.status_code)
            raise HTTPException(status_code=502, detail="audius_search_upstream_error")
        except (httpx.HTTPError, ValueError) as exc:
            logger.warning("Audius search failed: %s", type(exc).__name__)
            raise HTTPException(status_code=502, detail="audius_search_unavailable")
        if len(_search_cache) >= _MAX_CACHE_ENTRIES:
            oldest = min(_search_cache, key=lambda cache_key: _search_cache[cache_key][0])
            _search_cache.pop(oldest, None)
        _search_cache[key] = (now, tracks)
    return {
        "query": query,
        "source": "Audius",
        "providers": ["Audius"],
        "count": len(tracks),
        "tracks": tracks,
        "data": tracks,
    }


@router.get("/v1/music/resolve/{track_id}")
def resolve_music(track_id: str):
    """Return a short-lived resolver URL; clients must resolve again when it expires."""
    if not TRACK_ID_RE.fullmatch(track_id):
        raise HTTPException(status_code=400, detail="invalid_track_id")
    return {
        "id": track_id,
        "source": "Audius",
        "stream_url": f"https://vynix-api.onrender.com/v1/music/stream/{quote(track_id, safe='')}",
        "is_preview": False,
        "refresh_before_playback": True,
    }


@router.get("/v1/music/stream/{track_id}")
def stream_music(track_id: str):
    """Redirect only to the official Audius stream endpoint; no arbitrary URL proxying."""
    if not TRACK_ID_RE.fullmatch(track_id):
        raise HTTPException(status_code=400, detail="invalid_track_id")
    target = (
        f"{AUDIUS_API}/tracks/{quote(track_id, safe='')}/stream"
        f"?app_name={quote(AUDIUS_APP_NAME, safe='')}"
    )
    return RedirectResponse(url=target, status_code=307)
