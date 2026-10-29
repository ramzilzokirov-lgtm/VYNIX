package app.vynix

import android.content.Context
import android.net.Uri
import android.os.Environment
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Saves a resolved, publicly accessible audio stream for offline playback.
 * It never uses Spotify/Deezer preview URLs; catalogue entries must resolve to a full
 * stream from a provider before a download is started.
 */
internal object VynixOfflineDownloads {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun download(context: Context, track: AudioTrack): AudioTrack {
        val playable = NuclearStyleSearch.resolveTrack(track)
        val streamUrl = playable.streamUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            ?: error("Источник не предоставил ссылку на полный аудиопоток.")

        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/129.0.0.0 Mobile Safari/537.36")
            .header("Referer", when (playable.source) {
                "YouTube" -> "https://music.youtube.com/"
                "SoundCloud" -> "https://soundcloud.com/"
                else -> "https://vynix.app/"
            })
            .build()

        val musicDirectory = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "music")
        if (!musicDirectory.exists() && !musicDirectory.mkdirs()) {
            error("Не удалось создать папку для офлайн-музыки.")
        }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Источник отказал в загрузке: HTTP ${response.code}.")
            val body = response.body ?: error("Источник вернул пустой ответ.")
            val mime = body.contentType()?.subtype?.lowercase().orEmpty()
            val urlPath = Uri.parse(streamUrl).lastPathSegment.orEmpty().lowercase()
            if (mime.contains("mpegurl") || urlPath.endsWith(".m3u8")) {
                error("Этот источник отдаёт HLS-поток; офлайн-загрузка этого формата пока недоступна.")
            }
            val extension = when {
                mime.contains("mpeg") -> "mp3"
                mime.contains("mp4") || mime.contains("m4a") -> "m4a"
                mime.contains("ogg") || mime.contains("opus") -> "ogg"
                mime.contains("webm") -> "webm"
                mime.contains("flac") -> "flac"
                mime.contains("aac") -> "aac"
                urlPath.endsWith(".mp3") -> "mp3"
                urlPath.endsWith(".m4a") -> "m4a"
                urlPath.endsWith(".ogg") -> "ogg"
                urlPath.endsWith(".flac") -> "flac"
                else -> "audio"
            }
            val baseName = "${track.artist} - ${track.title}"
                .replace(Regex("[^\\p{L}\\p{N}._ -]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(110)
                .ifBlank { "VYNIX track" }
            val destination = File(musicDirectory, "$baseName.$extension")
            val temporary = File(musicDirectory, ".$baseName.$extension.part")
            temporary.outputStream().buffered().use { output ->
                body.byteStream().use { input -> input.copyTo(output) }
            }
            if (!temporary.exists() || temporary.length() == 0L) {
                temporary.delete()
                error("Загруженный файл пустой.")
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                error("Не удалось заменить существующий офлайн-файл.")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            return track.copy(
                uri = Uri.fromFile(destination),
                source = "Загружено",
                isPreview = false,
                streamUrl = null
            )
        }
    }
}
