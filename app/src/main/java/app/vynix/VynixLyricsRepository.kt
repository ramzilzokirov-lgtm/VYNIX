package app.vynix

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * LRCLIB-backed lyrics client for VYNIX.
 *
 * Call from Dispatchers.IO. Synced LRC timestamps are kept intact so the player
 * can highlight lines and seek to a selected line without parsing network data twice.
 */
internal object VynixLyricsRepository {
    private const val API_BASE = "https://vynix-api.onrender.com/v1/music/lyrics"

    data class Lyrics(
        val id: Long?,
        val title: String,
        val artist: String,
        val album: String,
        val durationSeconds: Double?,
        val instrumental: Boolean,
        val plainText: String?,
        val syncedLrc: String?,
        val source: String = "LRCLIB"
    ) {
        val hasLyrics: Boolean get() = !plainText.isNullOrBlank() || !syncedLrc.isNullOrBlank()
    }

    data class TimedLine(val timestampMs: Long, val text: String)

    fun find(title: String, artist: String = "", album: String = "", durationMs: Long = 0L): Lyrics? {
        require(title.isNotBlank()) { "Укажи название трека." }
        val query = buildString {
            append("track_name=").append(enc(title.trim()))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist.trim()))
            if (album.isNotBlank()) append("&album_name=").append(enc(album.trim()))
            if (durationMs > 0L) append("&duration=").append((durationMs / 1000L).coerceIn(1L, 86400L))
        }
        val json = JSONObject(request("$API_BASE?$query"))
        if (!json.optBoolean("found", false)) return null
        return parse(json)
    }

    fun search(query: String, limit: Int = 10): List<Lyrics> {
        require(query.trim().length >= 2) { "Введи минимум два символа для поиска текста." }
        val url = "$API_BASE/search?q=${enc(query.trim())}&limit=${limit.coerceIn(1, 20)}"
        val array = JSONObject(request(url)).optJSONArray("lyrics") ?: JSONArray()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            parse(item).takeIf { it.hasLyrics }
        }
    }

    fun timedLines(lrc: String): List<TimedLine> {
        val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
        return lrc.lineSequence().flatMap { line ->
            val matches = timestamp.findAll(line).toList()
            if (matches.isEmpty()) emptySequence()
            else {
                val text = line.replace(timestamp, "").trim()
                matches.asSequence().map { match ->
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fractionText = match.groupValues[3]
                    val millis = when (fractionText.length) {
                        1 -> (fractionText.toLongOrNull() ?: 0L) * 100L
                        2 -> (fractionText.toLongOrNull() ?: 0L) * 10L
                        3 -> fractionText.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    TimedLine((minutes * 60L + seconds) * 1000L + millis, text)
                }
            }
        }.sortedBy { it.timestampMs }.toList()
    }

    private fun parse(item: JSONObject): Lyrics = Lyrics(
        id = if (item.has("id") && !item.isNull("id")) item.optLong("id").takeIf { it > 0L } else null,
        title = item.optString("track_name", item.optString("trackName", "")),
        artist = item.optString("artist_name", item.optString("artistName", "")),
        album = item.optString("album_name", item.optString("albumName", "")),
        durationSeconds = if (item.has("duration") && !item.isNull("duration")) item.optDouble("duration").takeIf { it > 0.0 } else null,
        instrumental = item.optBoolean("instrumental", false),
        plainText = item.optString("plain_lyrics", item.optString("plainLyrics", "")).takeIf { it.isNotBlank() && it != "null" },
        syncedLrc = item.optString("synced_lyrics", item.optString("syncedLyrics", "")).takeIf { it.isNotBlank() && it != "null" },
        source = item.optString("source", "LRCLIB")
    )

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VYNIX Android Music Player/0.1")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = try { JSONObject(body).optString("detail") } catch (_: Exception) { "" }
                val message = when (code) {
                    404 -> "Текст песни не найден."
                    502, 503, 504 -> "Сервис текстов временно недоступен."
                    else -> "Ошибка сервера текстов HTTP $code."
                }
                error(if (detail.isNotBlank()) "$message ($detail)" else message)
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}
