package app.vynix

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** VYNIX music catalogue client. Search and stream resolution are handled by the VYNIX API. */
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
            val trackId = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (trackId.isBlank() || title.isBlank()) return@mapNotNull null
            val artist = item.optString("artist").trim().ifBlank { "Неизвестный исполнитель" }
            val artwork = item.optString("artwork").takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val durationSeconds = item.optDouble("duration_seconds", 0.0).takeIf { it.isFinite() } ?: 0.0
            val streamUri = Uri.parse("$API_BASE/v1/music/stream/${Uri.encode(trackId)}")
            AudioTrack(
                id = (trackId.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L),
                uri = streamUri,
                title = title,
                artist = artist,
                album = "Audius",
                duration = (durationSeconds * 1000).toLong(),
                artworkUrl = artwork,
                source = "Audius",
                isPreview = false
            )
        }.distinctBy { it.uri.toString() }.take(30)
    }

    fun resolve(track: AudioTrack): String {
        if (track.source == "Audius") {
            val trackId = track.uri.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: error("Не удалось определить ID трека. Повтори поиск.")
            val encodedId = URLEncoder.encode(trackId, "UTF-8")
            val root = JSONObject(request("$API_BASE/v1/music/resolve/$encodedId"))
            val streamUrl = root.optString("stream_url").trim()
            if (streamUrl.startsWith("https://") || streamUrl.startsWith("http://")) return streamUrl
            error("Сервис не вернул ссылку на аудио. Попробуй другой трек.")
        }
        error("Источник этого трека больше не поддерживается. Выполни поиск заново.")
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
