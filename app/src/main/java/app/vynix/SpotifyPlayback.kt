package app.vynix

import android.app.Activity
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object SpotifyCatalog {
    private const val API_BASE = "https://vynix-api.onrender.com"

    fun findTrackUri(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val connection = (URL("$API_BASE/v1/spotify/search?q=$encoded").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
                when (code) {
                    503 -> error("Spotify-поиск не настроен на сервере. Добавь SPOTIFY_CLIENT_SECRET в Render.")
                    502, 504 -> error("Spotify временно не отвечает. Повтори попытку.")
                    else -> error(detail.ifBlank { "Spotify-поиск вернул HTTP $code." })
                }
            }
            JSONObject(body).optString("spotify_uri").takeIf { it.startsWith("spotify:track:") }
                ?: error("Spotify не нашёл подходящую полную версию этой песни.")
        } finally {
            connection.disconnect()
        }
    }
}

internal class SpotifyRemoteController(private val activity: Activity) {
    companion object {
        const val CLIENT_ID = "e9907b95f5bf435e8fb260a3eff6a7e8"
        const val REDIRECT_URI = "https://app.vynix/callback"
    }

    private var remote: SpotifyAppRemote? = null
    private var connecting = false
    private val pending = mutableListOf<(SpotifyAppRemote) -> Unit>()

    fun play(uri: String, onError: (String) -> Unit) {
        val connected = remote
        if (connected != null && connected.isConnected) {
            connected.playerApi.play(uri).setResultCallback { }.setErrorCallback { error ->
                onError(error.message ?: "Spotify не смог запустить трек.")
            }
            return
        }
        pending += { appRemote ->
            appRemote.playerApi.play(uri).setResultCallback { }.setErrorCallback { error ->
                onError(error.message ?: "Spotify не смог запустить трек.")
            }
        }
        if (connecting) return
        connecting = true
        val params = ConnectionParams.Builder(CLIENT_ID)
            .setRedirectUri(REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(activity, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                connecting = false
                remote = appRemote
                val actions = pending.toList()
                pending.clear()
                actions.forEach { action -> action(appRemote) }
            }

            override fun onFailure(throwable: Throwable) {
                connecting = false
                pending.clear()
                onError("Не удалось подключиться к Spotify: ${throwable.message ?: "проверь Spotify и настройки Dashboard"}")
            }
        })
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
    }
}
