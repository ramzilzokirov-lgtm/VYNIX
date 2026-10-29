import unittest
from unittest.mock import patch

from fastapi import HTTPException

import music_api


class MusicApiTests(unittest.TestCase):
    def setUp(self):
        music_api._search_cache.clear()

    def test_normalise_track_marks_full_stream_not_preview(self):
        track = music_api._normalise_track({
            "id": "track_123",
            "title": "Full Track",
            "user": {"name": "Artist"},
            "duration": 215,
            "artwork": {"480x480": "https://cdn.example/cover.jpg"},
        })
        self.assertIsNotNone(track)
        self.assertEqual(track["artist"], "Artist")
        self.assertEqual(track["duration_ms"], 215000)
        self.assertFalse(track["is_preview"])
        self.assertEqual(track["source"], "Audius")
        self.assertEqual(track["stream_url"], "/v1/music/stream/track_123")

    def test_normalise_track_rejects_missing_or_invalid_ids(self):
        self.assertIsNone(music_api._normalise_track({"id": "../private", "title": "Bad"}))
        self.assertIsNone(music_api._normalise_track({"id": "valid", "title": ""}))

    def test_search_returns_cached_provider_results(self):
        fixture = [{
            "id": "track_123",
            "title": "Full Track",
            "artist": "Artist",
            "duration_seconds": 215,
            "source": "Audius",
            "is_preview": False,
            "stream_url": "/v1/music/stream/track_123",
        }]
        with patch.object(music_api, "_fetch_search", return_value=fixture) as fetch:
            first = music_api.search_music("full track", 25)
            second = music_api.search_music("full track", 25)
        self.assertEqual(first["tracks"], second["tracks"])
        self.assertEqual(first["providers"], ["Audius"])
        self.assertEqual(first["count"], 1)
        fetch.assert_called_once()

    def test_resolve_rejects_path_like_track_id(self):
        with self.assertRaises(HTTPException) as raised:
            music_api.resolve_music("../etc/passwd")
        self.assertEqual(raised.exception.status_code, 400)

    def test_resolve_uses_vynix_stream_endpoint(self):
        result = music_api.resolve_music("track_123")
        self.assertIn("/v1/music/stream/track_123", result["stream_url"])
        self.assertFalse(result["is_preview"])

    def test_stream_redirects_only_to_audius(self):
        response = music_api.stream_music("track_123")
        self.assertEqual(response.status_code, 307)
        self.assertTrue(response.headers["location"].startswith("https://api.audius.co/v1/tracks/track_123/stream"))
        with self.assertRaises(HTTPException):
            music_api.stream_music("https://attacker.invalid/audio")


if __name__ == "__main__":
    unittest.main()
