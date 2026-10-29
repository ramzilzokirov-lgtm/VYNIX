package app.vynix

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Unified VYNIX search client for the server's Audius + Deezer catalogue. */
internal object NuclearStyleSearch {
    private const val API_BASE = "https://vynix-api.onrender.com"

    fun search(query: String): List<AudioTrack> {
        val normalized = query.trim()
        require(normalized.length >= 2) { "Введи не менее двух символов." }
        val encodedQuery = URLEncoder.encode(normalized, "UTF-8")
        val root = JSONObject(request("$API_BASE/v1/music/search?q=$encodedQuery&limit=30"))
        val data = root.optJSONArray("tracks") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
            val source = item.optString("source", "Audius")
            val artist = item.optString("artist").trim().ifBlank { "Неизвестный исполнитель" }
            val artwork = item.optString("artwork").takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val durationSeconds = item.optDouble("duration_seconds", 0.0).takeIf { it.isFinite() } ?: 0.0
            val previewUrl = item.optString("preview_url").takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val uri = if (source == "Deezer") {
                Uri.parse(previewUrl ?: item.optString("permalink"))
            } else {
                Uri.parse("$API_BASE/v1/music/stream/${Uri.encode(id)}")
            }
            if (source == "Deezer" && previewUrl == null) return@mapNotNull null
            AudioTrack(
                id = (id.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L),
                uri = uri,
                title = title,
                artist = artist,
                album = item.optString("album").ifBlank { source },
                duration = (durationSeconds * 1000).toLong(),
                artworkUrl = artwork,
                source = source,
                isPreview = source == "Deezer" || item.optBoolean("is_preview", false)
            )
        }.distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }.take(30)
    }

    fun resolve(track: AudioTrack): String {
        if (track.source == "Deezer") {
            val url = track.uri.toString()
            // Prefer the official Deezer preview when present. If it is missing, search
            // Audius for the same recording instead of failing immediately.
            if (track.isPreview && (url.startsWith("https://") || url.startsWith("http://"))) return url
            val fallback = search("${track.title} ${track.artist}")
                .firstOrNull { it.source == "Audius" && it.title.equals(track.title, ignoreCase = true) }
                ?: search(track.title).firstOrNull { it.source == "Audius" }
                ?: error("У Deezer нет превью, а совпадение не найдено в Audius. Попробуй другой трек.")
            return resolve(fallback)
        }
        if (track.source == "Audius") {
            val trackId = track.uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: error("Не удалось определить ID трека. Повтори поиск.")
            val encodedId = URLEncoder.encode(trackId, "UTF-8")
            val root = JSONObject(request("$API_BASE/v1/music/resolve/$encodedId"))
            val streamUrl = root.optString("stream_url").trim()
            if (streamUrl.startsWith("https://") || streamUrl.startsWith("http://")) return streamUrl
            error("Сервис не вернул ссылку на аудио. Попробуй другой трек.")
        }
        error("Источник этого трека не поддерживается. Выполни поиск заново.")
    }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "GET"
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
