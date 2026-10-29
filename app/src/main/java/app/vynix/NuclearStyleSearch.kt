package app.vynix

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Search and playback URLs for Free To Use's public royalty-free music catalogue.
 * API documentation: https://freetouse.com/api
 */
internal object NuclearStyleSearch {
    private const val API_BASE = "https://api.freetouse.com/v3"

    fun search(query: String): List<AudioTrack> {
        val normalized = query.trim()
        require(normalized.length >= 2) { "Введи не менее двух символов." }
        return searchFreeToUse(normalized)
    }

    private fun searchFreeToUse(query: String): List<AudioTrack> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val root = JSONObject(request("$API_BASE/music/tracks/search?query=$encodedQuery&limit=30&order=plays&sort=desc"))
        if (!root.optBoolean("ok", false)) error(root.optString("error").ifBlank { "Free To Use не смог выполнить поиск." })
        val data = root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val audioUrl = item.optJSONObject("files")?.optString("mp3")?.trim().orEmpty()
            if (title.isBlank() || !audioUrl.startsWith("https://") || item.optBoolean("is_premium", false)) return@mapNotNull null
            val artistArray = item.optJSONArray("artists")
            val artistNames = mutableListOf<String>()
            if (artistArray != null) for (i in 0 until artistArray.length()) {
                val pair = artistArray.optJSONArray(i) ?: continue
                pair.optJSONObject(1)?.optString("name")?.trim()?.takeIf { it.isNotBlank() }?.let(artistNames::add)
            }
            val thumbs = item.optJSONObject("thumbnails")
            val artwork = listOf("lg", "md", "sm").firstNotNullOfOrNull { thumbs?.optString(it)?.takeIf { u -> u.startsWith("https://") } }
            AudioTrack(
                id = (item.optString("id").hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L),
                uri = Uri.parse(audioUrl), title = title,
                artist = artistNames.distinct().joinToString(", ").ifBlank { "Free To Use" },
                album = item.optString("genre").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
                duration = (item.optDouble("duration", 0.0) * 1000).toLong(),
                artworkUrl = artwork, source = "Free To Use", isPreview = false
            )
        }.distinctBy { it.uri.toString() }.take(30)
    }

    fun resolve(track: AudioTrack): String {
        val url = track.uri.toString()
        if (track.source == "Free To Use" && url.startsWith("https://")) return url
        error("Ссылка на аудио недоступна. Повтори поиск.")
    }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VYNIX Android Music Player/0.1")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Free To Use API: HTTP $code" + if (response.isNotBlank()) " — " + response.take(160) else "")
            response
        } finally {
            connection.disconnect()
        }
    }
}
