package app.vynix

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** YouTube Music-only online catalog search. */
internal object NuclearStyleSearch {
    fun search(query: String): List<AudioTrack> {
        val home = text("https://music.youtube.com/")
        val apiKey = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"")
            .find(home)?.groupValues?.getOrNull(1) ?: error("YouTube Music API key unavailable")
        val endpoint = "https://music.youtube.com/youtubei/v1/search?key=" + URLEncoder.encode(apiKey, "UTF-8")
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", "1.20261020.01.00")
                .put("hl", "en").put("gl", "US")))
            .put("query", query)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000; readTimeout = 12000; requestMethod = "POST"; doOutput = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("Referer", "https://music.youtube.com/")
        }
        val root = try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            if (connection.responseCode !in 200..299) error("YouTube Music HTTP " + connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
        val renderers = mutableListOf<JSONObject>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val renderer = value.optJSONObject("musicResponsiveListItemRenderer")
                    if (renderer != null) renderers += renderer
                    val keys = value.keys()
                    while (keys.hasNext()) walk(value.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i))
            }
        }
        walk(root)
        return renderers.mapNotNull { item ->
            val columns = item.optJSONArray("flexColumns") ?: return@mapNotNull null
            fun columnText(index: Int): String {
                val renderer = columns.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                val runs = renderer?.optJSONObject("text")?.optJSONArray("runs") ?: return ""
                return (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }.joinToString("")
            }
            val title = columnText(0).takeIf(String::isNotBlank) ?: return@mapNotNull null
            val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                ?: run {
                    val nav = columns.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
                        ?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                    nav?.optString("videoId")?.takeIf { it.isNotBlank() && it != "null" }
                } ?: return@mapNotNull null
            val artist = columnText(1).ifBlank { "YouTube Music" }
            var artwork: String? = null
            fun findThumb(value: Any?) {
                if (artwork != null) return
                when (value) {
                    is JSONObject -> {
                        val thumbs = value.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        if (thumbs != null && thumbs.length() > 0) artwork = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url")?.takeIf { it.startsWith("http") }
                        if (artwork == null) { val keys = value.keys(); while (keys.hasNext() && artwork == null) findThumb(value.opt(keys.next())) }
                    }
                    is JSONArray -> for (i in 0 until value.length()) { findThumb(value.opt(i)); if (artwork != null) break }
                }
            }
            findThumb(item)
            val durationText = (0 until (item.optJSONArray("fixedColumns")?.length() ?: 0)).mapNotNull { i ->
                item.optJSONArray("fixedColumns")?.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            }.firstOrNull()
            val duration = durationText?.split(":")?.mapNotNull(String::toLongOrNull)?.fold(0L) { acc, part -> acc * 60 + part }?.times(1000) ?: 0L
            val id = (videoId.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L)
            AudioTrack(id, Uri.parse("https://music.youtube.com/watch?v=" + videoId), title, artist, "", duration, artwork, "YouTube Music", false)
        }.distinctBy { it.uri.toString() }.take(30)
    }


    fun resolve(track: AudioTrack): String = track.uri.toString()

    private fun text(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Accept", "*/*")
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
