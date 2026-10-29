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

        // Both sources below provide full-length streams, not short previews.
        val freeTracks = runCatching { searchFreeToUse(normalized) }.getOrDefault(emptyList())
        if (freeTracks.isNotEmpty()) return freeTracks
        val archiveTracks = runCatching { searchInternetArchive(normalized) }.getOrDefault(emptyList())
        if (archiveTracks.isNotEmpty()) return archiveTracks
        return emptyList()
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

    private fun searchInternetArchive(query: String): List<AudioTrack> {
        val encodedQuery = URLEncoder.encode("mediatype:audio AND ($query)", "UTF-8")
        val root = JSONObject(request("https://archive.org/advancedsearch.php?q=$encodedQuery&fl[]=identifier,title,creator&rows=12&page=1&output=json"))
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        val results = mutableListOf<AudioTrack>()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = doc.optString("identifier").trim()
            if (identifier.isBlank()) continue
            val metadata = runCatching {
                JSONObject(request("https://archive.org/metadata/${URLEncoder.encode(identifier, "UTF-8")}"))
            }.getOrNull() ?: continue
            val files = metadata.optJSONObject("files") ?: continue
            var audioUrl: String? = null
            var durationMs = 0L
            for (j in 0 until files.length()) {
                val file = files.optJSONObject(files.names().optString(j)) ?: continue
                val name = file.optString("name")
                val format = file.optString("format").lowercase()
                if (file.optString("source") == "original" &&
                    (format.contains("mp3") || format.contains("mpeg") || format.contains("ogg vorbis") || name.endsWith(".mp3", true) || name.endsWith(".ogg", true))) {
                    audioUrl = "https://archive.org/download/${Uri.encode(identifier)}/${Uri.encode(name)}"
                    durationMs = (file.optString("length").toDoubleOrNull()?.times(1000))?.toLong() ?: 0L
                    break
                }
            }
            val stream = audioUrl ?: continue
            results += AudioTrack(
                id = (identifier.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L),
                uri = Uri.parse(stream), title = doc.optString("title").ifBlank { identifier },
                artist = doc.opt("creator")?.let { if (it is JSONArray) (0 until it.length()).joinToString(", ") { n -> it.optString(n) } else it.toString() } ?: "Internet Archive",
                album = "Internet Archive", duration = durationMs, artworkUrl = null,
                source = "Internet Archive", isPreview = false
            )
            if (results.size >= 20) break
        }
        return results
    }

    fun resolve(track: AudioTrack): String {
        val url = track.uri.toString()
        if (url.startsWith("https://") && (track.source == "Free To Use" || track.source == "Internet Archive")) return url
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
