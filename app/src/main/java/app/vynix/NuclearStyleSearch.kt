package app.vynix

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Native Android adaptation of Nuclear's multi-source resolver pattern:
 * discover candidates independently, score/deduplicate them, and resolve a stream only on play.
 * This is not a port of Nuclear's AGPL implementation.
 */
internal object NuclearStyleSearch {
    @Volatile private var soundCloudClientId: String? = null

    fun search(query: String): List<AudioTrack> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        return try {
            val sc = pool.submit<List<AudioTrack>> { runCatching { searchSoundCloud(query) }.getOrDefault(emptyList()) }
            val bc = pool.submit<List<AudioTrack>> { runCatching { searchBandcamp(query) }.getOrDefault(emptyList()) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(16)
            fun remainingSeconds(): Long = ((deadline - System.nanoTime()).coerceAtLeast(1L) / 1_000_000_000L).coerceAtLeast(1L)
            val scItems = runCatching { sc.get(remainingSeconds(), java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
            val bcItems = runCatching { bc.get(remainingSeconds(), java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
            val candidates = scItems + bcItems
            val normalized = normalize(query)
            candidates.distinctBy { it.key }
                .sortedByDescending { score(it, normalized) }
                .take(50)
        } finally {
            pool.shutdownNow()
        }
    }

    fun resolve(track: AudioTrack): String {
        return when {
            track.uri.scheme == "vynix" && track.uri.host == "soundcloud" ->
                resolveSoundCloud(track.uri.lastPathSegment?.toLongOrNull() ?: track.id)
            track.uri.scheme == "vynix" && track.uri.host == "bandcamp" ->
                resolveBandcamp(track.uri.lastPathSegment ?: error("Invalid Bandcamp candidate"))
            else -> track.uri.toString()
        }
    }

    private fun score(track: AudioTrack, query: String): Double {
        val title = normalize(track.title)
        val artist = normalize(track.artist)
        val words = query.split(" ").filter { it.length > 1 }.toSet()
        val titleWords = title.split(" ").filter { it.length > 1 }.toSet()
        val titleScore = if (title == query) 1.0 else if (title.contains(query) || query.contains(title)) 0.8
            else if (words.isEmpty() || titleWords.isEmpty()) 0.0
            else 2.0 * words.intersect(titleWords).size / (words.size + titleWords.size).toDouble()
        val durationBonus = if (track.duration >= 120_000L) 0.08 else 0.0
        val wrongVersionPenalty = if (listOf("karaoke", "cover", "instrumental", "reaction").any { title.contains(it) && !query.contains(it) }) 0.25 else 0.0
        return titleScore + durationBonus - wrongVersionPenalty + if (artist.isNotBlank()) 0.02 else 0.0
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun searchSoundCloud(query: String): List<AudioTrack> {
        val clientId = getSoundCloudClientId()
        val url = "https://api-v2.soundcloud.com/search/tracks?q=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=25&client_id=" + clientId
        val array = json(url).optJSONArray("collection") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            if (!item.optBoolean("streamable", true)) return@mapNotNull null
            val id = item.optLong("id", -1L).takeIf { it > 0L } ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val user = item.optJSONObject("user")
            val artist = user?.optString("username")?.takeIf { it.isNotBlank() } ?: "SoundCloud"
            val image = item.optString("artwork_url").takeIf { it.startsWith("http") }
                ?: user?.optString("avatar_url")?.takeIf { it.startsWith("http") }
            AudioTrack(id, Uri.parse("vynix://soundcloud/" + id), title, artist, "",
                item.optLong("full_duration", 0L).coerceAtLeast(0L),
                image?.replace("-large", "-t200x200"), "SoundCloud", false)
        }
    }

    private fun getSoundCloudClientId(): String {
        soundCloudClientId?.let { return it }
        val home = text("https://soundcloud.com/")
        val scripts = Regex("https?://[^\\s\\\"]*sndcdn\\.com[^\\s\\\"]*\\.js")
            .findAll(home).map { it.value }.toList()
        for (scriptUrl in scripts.asReversed()) {
            val script = runCatching { text(scriptUrl) }.getOrNull() ?: continue
            val id = Regex("[{,]client_id:\\s*\\\"(\\w+)\\\"").find(script)?.groupValues?.getOrNull(1)
            if (!id.isNullOrBlank()) {
                soundCloudClientId = id
                return id
            }
        }
        error("SoundCloud search is temporarily unavailable")
    }

    private fun searchBandcamp(query: String): List<AudioTrack> {
        val connection = (URL("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 12000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", "VYNIX-Android/2.3")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = JSONObject().put("search_text", query).put("search_filter", "t")
                .put("full_page", false).put("fan_id", JSONObject.NULL).toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            if (connection.responseCode !in 200..299) error("Bandcamp HTTP " + connection.responseCode)
            val items = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optJSONObject("auto")?.optJSONArray("results") ?: JSONArray()
            (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val page = item.optString("item_url_path").takeIf { it.startsWith("http") }
                    ?: item.optString("item_url_root").takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val title = item.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = (page.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L)
                val artist = item.optString("band_name").takeIf { it.isNotBlank() } ?: "Bandcamp"
                val image = item.optString("img").takeIf { it.startsWith("http") }
                val encoded = Base64.encodeToString(page.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                AudioTrack(id, Uri.parse("vynix://bandcamp/" + encoded), title, artist,
                    item.optString("album_name"), 0L, image, "Bandcamp", false)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveSoundCloud(trackId: Long): String {
        val clientId = getSoundCloudClientId()
        val track = json("https://api-v2.soundcloud.com/tracks/" + trackId + "?client_id=" + clientId)
        if (!track.optBoolean("streamable", true)) error("SoundCloud track is not streamable")
        val transcodings = track.optJSONObject("media")?.optJSONArray("transcodings") ?: JSONArray()
        val candidate = (0 until transcodings.length()).mapNotNull { transcodings.optJSONObject(it) }
            .firstOrNull {
                val format = it.optJSONObject("format")
                format?.optString("protocol") == "hls" && format.optString("mime_type").contains("audio")
            } ?: error("No playable SoundCloud stream")
        val endpoint = candidate.optString("url").takeIf { it.startsWith("http") } ?: error("SoundCloud stream unavailable")
        val separator = if (endpoint.contains("?")) "&" else "?"
        return json(endpoint + separator + "client_id=" + URLEncoder.encode(clientId, "UTF-8"))
            .optString("url").takeIf { it.startsWith("http") } ?: error("SoundCloud stream unavailable")
    }

    private fun resolveBandcamp(encodedPage: String): String {
        val bytes = Base64.decode(encodedPage, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val pageUrl = String(bytes, Charsets.UTF_8)
        val html = text(pageUrl)
        val raw = Regex("data-tralbum=\\\"([^\\\"]+)\\\"").find(html)?.groupValues?.getOrNull(1)
            ?: error("Bandcamp doesn't expose a playable full track")
        val decoded = raw.replace("&quot;", "\\\"").replace("&amp;", "&")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        val trackInfo = JSONObject(decoded).optJSONArray("trackinfo") ?: JSONArray()
        val first = (0 until trackInfo.length()).mapNotNull { trackInfo.optJSONObject(it) }.firstOrNull()
            ?: error("Bandcamp track metadata unavailable")
        val file = first.optJSONObject("file") ?: error("Bandcamp provides preview only")
        return file.optString("mp3-128").takeIf { it.startsWith("http") }
            ?: error("Bandcamp provides preview only")
    }

    private fun text(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "VYNIX-Android/2.3")
            setRequestProperty("Accept", "*/*")
        }
        return try {
            if (connection.responseCode !in 200..299) error("HTTP " + connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun json(url: String): JSONObject = JSONObject(text(url))
}
