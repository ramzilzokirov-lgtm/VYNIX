package app.vynix

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Audius catalog search with stream URLs for the built-in Media3 player. */
internal object AudiusSearch {
    private const val API = "https://api.audius.co/v1"

    fun search(query: String): List<AudioTrack> {
        val url = "$API/tracks/search?query=" + URLEncoder.encode(query, "UTF-8") + "&limit=25"
        val root = request(url)
        val data = root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val trackId = item.optString("id").takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null
            val title = item.optString("title").trim().takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val user = item.optJSONObject("user")
            val artist = user?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("artist").takeIf { it.isNotBlank() && it != "null" }
                ?: "Audius"
            val artwork = item.optJSONObject("artwork")
            val artworkUrl = artwork?.optString("480x480")?.takeIf { it.startsWith("http") }
                ?: artwork?.optString("150x150")?.takeIf { it.startsWith("http") }
            val duration = item.optLong("duration", 0L).coerceAtLeast(0L) * 1000L
            val stream = "$API/tracks/${Uri.encode(trackId)}/stream"
            val numericId = trackId.toLongOrNull()
                ?: (trackId.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L)
            AudioTrack(numericId, Uri.parse(stream), title, artist, item.optString("album", ""),
                duration, artworkUrl, "Audius", false)
        }.distinctBy { it.uri.toString() }.take(25)
    }

    fun resolve(track: AudioTrack): String = track.uri.toString()

    private fun request(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VYNIX Android")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                    .getOrNull().orEmpty()
                val message = runCatching { JSONObject(detail).optString("message").takeIf { it.isNotBlank() } }
                    .getOrNull()
                error("Audius API HTTP $code" + (message?.let { ": $it" } ?: ""))
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
