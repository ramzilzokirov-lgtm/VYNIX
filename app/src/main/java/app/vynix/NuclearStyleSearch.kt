package app.vynix

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Search playable tracks without requiring a Spotify login. */
internal object NuclearStyleSearch {
    private const val API_BASE = "https://vynix-api.onrender.com"
    private const val AUDIUS_API_BASE = "https://api.audius.co/v1"
    private const val AUDIUS_APP_NAME = "VYNIX"

    fun search(query: String): List<AudioTrack> {
        val normalized = query.trim()
        require(normalized.length >= 2) { "Введи не менее двух символов." }
        val encodedQuery = URLEncoder.encode(normalized, "UTF-8")

        // Prefer the VYNIX API, but fall back to Audius directly if Render is asleep,
        // not yet deployed, or returns an empty catalogue response.
        val serverResult = runCatching {
            parseTracks(request("$API_BASE/v1/music/search?q=$encodedQuery&limit=30"))
        }.getOrNull().orEmpty()
        val results = if (serverResult.isNotEmpty()) serverResult else {
            val direct = request(
                "$AUDIUS_API_BASE/tracks/search?query=$encodedQuery&limit=30&app_name=$AUDIUS_APP_NAME"
            )
            parseTracks(direct)
        }
        return results.distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }.take(30)
    }

    private fun parseTracks(json: String): List<AudioTrack> {
        val root = JSONObject(json)
        // VYNIX API returns {tracks:[...]}; Audius returns {data:[...]}.
        val data = root.optJSONArray("tracks") ?: root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            val source = item.optString("source", "Audius").ifBlank { "Audius" }
            val user = item.optJSONObject("user")
            val artist = item.optString("artist")
                .ifBlank { user?.optString("name").orEmpty() }
                .trim().ifBlank { "Неизвестный исполнитель" }
            val artworkObject = item.optJSONObject("artwork")
            val artwork = item.optString("artwork").takeIf { it.startsWith("https://") || it.startsWith("http://") }
                ?: artworkObject?.let {
                    listOf("480x480", "1000x1000", "150x150")
                        .firstNotNullOfOrNull { size -> it.optString(size).takeIf { value -> value.startsWith("https://") } }
                }
            val durationSeconds = item.optDouble("duration_seconds",
                item.optDouble("duration", 0.0)).takeIf { it.isFinite() } ?: 0.0
            val trackUri = Uri.parse("$API_BASE/v1/music/stream/${Uri.encode(id)}")
            AudioTrack(
                id = (id.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L),
                uri = trackUri,
                title = title,
                artist = artist,
                album = item.optString("album").ifBlank { "Audius" },
                duration = (durationSeconds * 1000).toLong(),
                artworkUrl = artwork,
                source = "Audius",
                isPreview = false
            )
        }
    }

    fun resolve(track: AudioTrack): String {
        val trackId = track.uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: error("Не удалось определить ID трека. Повтори поиск.")
        val encodedId = URLEncoder.encode(trackId, "UTF-8")

        // Use the server resolver when available. The direct Audius stream endpoint is
        // a fallback; Media3 follows its redirect to the actual audio file.
        val serverUrl = runCatching {
            JSONObject(request("$API_BASE/v1/music/resolve/$encodedId"))
                .optString("stream_url").trim()
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }.getOrNull()
        return serverUrl ?: "$AUDIUS_API_BASE/tracks/$encodedId/stream?app_name=$AUDIUS_APP_NAME"
    }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VYNIX Android Music Player/0.1")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try {
                    val json = JSONObject(response)
                    json.optString("detail").ifBlank { json.optString("error") }
                } catch (_: Exception) { response }
                val reason = when (code) {
                    400 -> "Проверь поисковый запрос."
                    404 -> "Трек не найден или больше недоступен."
                    502, 503, 504 -> "Музыкальный каталог временно недоступен. Подожди и повтори поиск."
                    else -> "Сервер музыки ответил с ошибкой HTTP $code."
                }
                error(if (detail.isNotBlank()) "$reason ($detail)" else reason)
            }
            response
        } finally {
            connection.disconnect()
        }
    }
}
