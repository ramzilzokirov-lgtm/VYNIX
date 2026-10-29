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

        // Prefer the full royalty-free catalogue. If it has no matching track,
        // fall back to Apple's public Search API, which provides legal 30-second previews.
        val freeTracks = runCatching { searchFreeToUse(normalized) }.getOrDefault(emptyList())
        if (freeTracks.isNotEmpty()) return freeTracks
        val previews = runCatching { searchApplePreviews(normalized) }.getOrDefault(emptyList())
        if (previews.isNotEmpty()) return previews
        return emptyList()
    }

    private fun searchFreeToUse(query: String): List<AudioTrack> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val root = JSONObject(request("$API_BASE/music/tracks/search?query=$encodedQuery&limit=30&order=plays&sort=desc"))
        if (!root.optBoolean("ok", false)) {
            error(root.optString("error").ifBlank { "Free To Use не смог выполнить поиск." })
        }
        val data = root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("title").trim()
            val idText = item.optString("id").trim()
            val audioUrl = item.optJSONObject("files")?.optString("mp3")?.trim().orEmpty()
            if (title.isBlank() || audioUrl.isBlank() || !audioUrl.startsWith("https://")) return@mapNotNull null
            if (item.optBoolean("is_premium", false)) return@mapNotNull null

            val artists = item.optJSONArray("artists")
            val artistNames = mutableListOf<String>()
            if (artists != null) {
                for (artistIndex in 0 until artists.length()) {
                    val pair = artists.optJSONArray(artistIndex) ?: continue
                    val artist = pair.optJSONObject(1)?.optString("name")?.trim().orEmpty()
                    if (artist.isNotBlank()) artistNames += artist
                }
            }
            val artist = artistNames.distinct().joinToString(", ").ifBlank { "Free To Use" }
            val thumbs = item.optJSONObject("thumbnails")
            val artwork = listOf("lg", "md", "sm").firstNotNullOfOrNull { key ->
                thumbs?.optString(key)?.takeIf { it.startsWith("https://") }
            }
            val id = (idText.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L)
            AudioTrack(
                id = id, uri = Uri.parse(audioUrl), title = title, artist = artist,
                album = item.optString("genre").takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
                duration = (item.optDouble("duration", 0.0) * 1000).toLong(),
                artworkUrl = artwork, source = "Free To Use", isPreview = false
            )
        }.distinctBy { it.uri.toString() }.take(30)
    }

    private fun searchApplePreviews(query: String): List<AudioTrack> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val root = JSONObject(request("https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=25"))
        val data = root.optJSONArray("results") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("trackName").trim()
            val artist = item.optString("artistName").trim()
            val preview = item.optString("previewUrl").trim()
            if (title.isBlank() || artist.isBlank() || !preview.startsWith("https://")) return@mapNotNull null
            val artwork = item.optString("artworkUrl100").takeIf { it.startsWith("https://") }
                ?.replace("100x100bb", "600x600bb")
            val id = item.optLong("trackId", preview.hashCode().toLong()).coerceAtLeast(1L)
            AudioTrack(
                id = id, uri = Uri.parse(preview), title = title, artist = artist,
                album = item.optString("collectionName"), duration = 30000L,
                artworkUrl = artwork, source = "Apple Music · фрагмент", isPreview = true
            )
        }.distinctBy { it.uri.toString() }.take(25)
    }

    fun resolve(track: AudioTrack): String {
        val url = track.uri.toString()
        if (url.startsWith("https://") &&
            (track.source == "Free To Use" || track.source == "Apple Music · фрагмент")) return url
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
