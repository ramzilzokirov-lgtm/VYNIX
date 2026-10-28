package app.vynix

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

@UnstableApi
class VynixPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var mediaCache: SimpleCache? = null

    override fun onCreate() {
        super.onCreate()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setSmallIcon(R.drawable.ic_vynix_logo)
                .build()
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Keep a bounded cache for online tracks, so revisiting or replaying a track
        // can reuse downloaded segments without filling the user's storage.
        val cache = SimpleCache(
            File(cacheDir, "vynix_audio_cache"),
            LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
            StandaloneDatabaseProvider(this)
        )
        mediaCache = cache

        val upstreamFactory = DefaultDataSource.Factory(this)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true // Request/release audio focus automatically.
                )
                setHandleAudioBecomingNoisy(true) // Pause when headphones disconnect.
                setWakeMode(C.WAKE_MODE_NETWORK) // Keep streaming alive while the screen is off.
            }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        mediaCache?.release()
        mediaCache = null
        super.onDestroy()
    }

    private companion object {
        const val CACHE_SIZE_BYTES = 150L * 1024L * 1024L
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 60_000
        const val BUFFER_FOR_PLAYBACK_MS = 1_000
        const val BUFFER_AFTER_REBUFFER_MS = 3_000
    }
}
