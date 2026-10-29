package app.vynix

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Dotify-inspired source router.
 *
 * Public sources are searched independently and merged into one VYNIX list.
 * Spotify catalogue metadata is not treated as a playable stream: the catalogue API
 * does not grant VYNIX permission to fetch protected Spotify audio.
 */
internal object NuclearStyleSearch {
    private const val API_BASE = "https://vynix-api.onrender.com"
    private const val AUDIUS_API_BASE = "https://api.audius.co/v1"
    private const val AUDIUS_APP_NAME = "VYNIX"

    private val extractorClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Volatile private var extractorInitialized = false
    private val extractorLock = Any()

    private fun ensureExtractor() {
        if (extractorInitialized) return
        synchronized(extractorLock) {
            if (extractorInitialized) return
            NewPipe.init(object : Downloader() {
                override fun execute(request: ExtractorRequest): ExtractorResponse {
                    val builder = okhttp3.Request.Builder().url(request.url())
                    request.headers().forEach { (name, values) ->
                        values.forEach { value -> builder.addHeader(name, value) }
                    }
                    val method = request.httpMethod().uppercase()
                    val body = request.dataToSend()?.toRequestBody(null)
                    builder.method(method, when {
                        method == "GET" || method == "HEAD" -> null
                        body != null -> body
                        method == "POST" -> ByteArray(0).toRequestBody(null)
                        else -> null
                    })
                    extractorClient.newCall(builder.build()).execute().use { response ->
                        val responseText = response.body?.string().orEmpty()
                        return ExtractorResponse(
                            response.code,
                            response.message,
                            response.headers.toMultimap(),
                            responseText,
                            response.request.url.toString()
                        )
                    }
                }
            })
            extractorInitialized = true
        }
    }

    fun search(query: String): List<AudioTrack> {
        val normalized = query.trim()
        require(normalized.length >= 2) { "Введи не менее двух символов." }

        // A provider timeout must not suppress results from other providers.
        val youtube = runCatching { searchExtractor(ServiceList.YouTube, normalized) }.getOrDefault(emptyList())
        val soundCloud = runCatching { searchExtractor(ServiceList.SoundCloud, normalized) }.getOrDefault(emptyList())
        val audius = runCatching { searchAudius(normalized) }.getOrDefault(emptyList())

        val merged = (youtube + soundCloud + audius)
            .distinctBy { it.uri.toString().lowercase() }
            .take(45)
        if (merged.isEmpty()) {
            error("Музыкальные источники временно не вернули результаты. Проверь интернет и повтори поиск.")
        }
        return merged
    }

    private fun searchExtractor(service: StreamingService, query: String): List<AudioTrack> {
        ensureExtractor()
        val info = SearchInfo.getInfo(service.getSearchExtractor(query))
        return info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .asSequence()
            .filter { it.url.isNotBlank() && it.name.isNotBlank() && it.duration > 0 }
            .take(15)
            .map { item ->
                val url = item.url
                AudioTrack(
                    id = (url.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L),
                    uri = Uri.parse(url),
                    title = item.name,
                    artist = item.uploaderName?.takeIf { it.isNotBlank() } ?: "Неизвестный исполнитель",
                    album = service.serviceInfo.name,
                    duration = item.duration * 1000L,
                    artworkUrl = item.thumbnails.firstOrNull()?.url,
                    source = service.serviceInfo.name,
                    isPreview = false
                )
            }.toList()
    }

    private fun searchAudius(query: String): List<AudioTrack> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val serverResult = runCatching {
            parseAudiusTracks(request("$API_BASE/v1/music/search?q=$encodedQuery&limit=25"))
        }.getOrNull().orEmpty()
        return if (serverResult.isNotEmpty()) serverResult else {
            parseAudiusTracks(request(
                "$AUDIUS_API_BASE/tracks/search?query=$encodedQuery&limit=25&app_name=$AUDIUS_APP_NAME"
            ))
        }
    }

    private fun parseAudiusTracks(json: String): List<AudioTrack> {
        val root = JSONObject(json)
        val data = root.optJSONArray("tracks") ?: root.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isBlank() || title.isBlank()) return@mapNotNull null
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
            val durationSeconds = item.optDouble("duration_seconds", item.optDouble("duration", 0.0))
                .takeIf { it.isFinite() } ?: 0.0
            AudioTrack(
                id = (id.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L),
                uri = Uri.parse("$API_BASE/v1/music/stream/${Uri.encode(id)}"),
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

    fun trending(): List<AudioTrack> {
        val audius = runCatching {
            parseAudiusTracks(request(
                "$AUDIUS_API_BASE/tracks/trending?time=week&limit=20&app_name=$AUDIUS_APP_NAME"
            ))
        }.getOrDefault(emptyList())
        val youtube = runCatching { searchExtractor(ServiceList.YouTube, "popular music official audio") }
            .getOrDefault(emptyList())
        val merged = (youtube + audius).distinctBy { it.uri.toString().lowercase() }.take(30)
        if (merged.isEmpty()) error("Не удалось загрузить популярную музыку. Проверь интернет и повтори попытку.")
        return merged
    }

    fun resolve(track: AudioTrack): String {
        // Extractor stream URLs expire. Re-resolve immediately before playback rather
        // than trying to play the original webpage URL or a stale media URL.
        if (track.source != "Audius") {
            ensureExtractor()
            val info = StreamInfo.getInfo(track.uri.toString())
            val audio = info.audioStreams
                .filter { it.content.isNotBlank() }
                .maxByOrNull { it.averageBitrate }
                ?: error("Источник не предоставил доступный аудиопоток для этого трека.")
            return audio.content
        }

        val trackId = track.uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: error("Не удалось определить ID трека. Повтори поиск.")
        val encodedId = URLEncoder.encode(trackId, "UTF-8")
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
            setRequestProperty("User-Agent", "VYNIX Android Music Player/0.2")
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
