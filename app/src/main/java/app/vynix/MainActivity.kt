package app.vynix

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val Ink = Color(0xFF100E17)
private val SurfaceA = Color(0xFF1C1926)
private val SurfaceB = Color(0xFF282235)
private val Lilac = Color(0xFFFFD21F)
private val Muted = Color(0xFFA8A1B7)
private val White = Color(0xFFF8F4FF)
private val BackgroundPalettes = listOf(
    Triple("Жёлто-чёрный", 0xFFFFD21F, 0xFF08090B),
    Triple("Бирюзовый", 0xFF087E8B, 0xFF081317),
    Triple("Рубиновый", 0xFF8B2635, 0xFF160B12),
    Triple("Золотой", 0xFFFFC928, 0xFF0D0D0B),
    Triple("Синий лёд", 0xFF315C9B, 0xFF0B101A),
    Triple("Изумрудный", 0xFF28734E, 0xFF09140F),
    Triple("Чёрный", 0xFF484452, 0xFF08080B)
)

data class AudioTrack(val id: Long, val uri: Uri, val title: String, val artist: String, val album: String, val duration: Long, val artworkUrl: String? = null, val source: String = "На устройстве", val isPreview: Boolean = false) {
    val key: String get() = when {
        else -> uri.toString()
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, android.content.ComponentName(this, VynixPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try { controller = controllerFuture?.get() } catch (_: Exception) { }
        }, ContextCompat.getMainExecutor(this))
        setContent { VynixApp(onPlay = { tracks, index -> playQueue(tracks, index) }, player = { controller }) }
    }

    private fun playQueue(tracks: List<AudioTrack>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(tracks.map { t ->
            MediaItem.Builder().setUri(t.uri).setMediaId(t.key).setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                    .setArtworkUri(t.artworkUrl?.let(Uri::parse)).build()
            ).setTag(t).build()
        }, index, 0L)
        c.prepare()
        c.play()
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun VynixApp(onPlay: (List<AudioTrack>, Int) -> Unit, player: () -> MediaController?) {
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var tab by remember { mutableStateOf("Главная") }
    var search by remember { mutableStateOf("") }
    var onlineResults by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var knownTracks by remember { mutableStateOf<Map<String, AudioTrack>>(emptyMap()) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var searchingOnline by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(setOf<String>()) }
    var recent by remember { mutableStateOf(listOf<String>()) }
    var playlists by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var createPlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var showAppearance by remember { mutableStateOf(false) }
    var paletteIndex by remember { mutableIntStateOf(0) }
    var customTopHex by remember { mutableStateOf("") }
    var animationEnabled by remember { mutableStateOf(true) }
    var animationSpeed by remember { mutableFloatStateOf(1f) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("vynix_compose_state", 0) }
    val ambientTransition = rememberInfiniteTransition(label = "ambient-background")
    val ambientAlpha by ambientTransition.animateFloat(initialValue = 0.08f, targetValue = if (animationEnabled) 0.28f else 0.08f, animationSpec = infiniteRepeatable(animation = tween((4200 / animationSpeed.coerceIn(0.5f, 2f)).toInt()), repeatMode = RepeatMode.Reverse), label = "ambient-glow")
    val orbX by ambientTransition.animateFloat(initialValue = 0.18f, targetValue = 0.82f, animationSpec = infiniteRepeatable(animation = tween((10500 / animationSpeed.coerceIn(0.5f, 2f)).toInt()), repeatMode = RepeatMode.Reverse), label = "orb-x")
    val orbY by ambientTransition.animateFloat(initialValue = 0.78f, targetValue = 0.24f, animationSpec = infiniteRepeatable(animation = tween((13200 / animationSpeed.coerceIn(0.5f, 2f)).toInt()), repeatMode = RepeatMode.Reverse), label = "orb-y")
    val selectedPalette = BackgroundPalettes[paletteIndex.coerceIn(0, BackgroundPalettes.lastIndex)]
    val baseTop = remember(paletteIndex, customTopHex) { customTopHex.toColorOrNull() ?: Color(selectedPalette.second) }
    val baseBottom = Color(selectedPalette.third)
    val backgroundColor = baseBottom
    val glowColor = baseTop
    LaunchedEffect(Unit) {
        paletteIndex = prefs.getInt("background_palette", 0).coerceIn(0, BackgroundPalettes.lastIndex)
        customTopHex = prefs.getString("background_custom_hex", "") ?: ""
        animationEnabled = prefs.getBoolean("background_animation", true)
        animationSpeed = prefs.getFloat("background_speed", 1f).coerceIn(0.5f, 2f)
    }
    fun saveAppearance() {
        prefs.edit().putInt("background_palette", paletteIndex)
            .putString("background_custom_hex", customTopHex)
            .putBoolean("background_animation", animationEnabled)
            .putFloat("background_speed", animationSpeed).apply()
    }

    fun saveState() {
        prefs.edit()
            .putString("favorites", JSONArray(favorites.toList()).toString())
            .putString("recent", JSONArray(recent.take(50)).toString())
            .putString("playlists", JSONObject().apply { playlists.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString())
            .putString("known_tracks", JSONArray(knownTracks.values.map { t ->
                JSONObject().put("id", t.id).put("uri", t.uri.toString()).put("title", t.title)
                    .put("artist", t.artist).put("album", t.album).put("duration", t.duration)
                    .put("artwork", t.artworkUrl ?: JSONObject.NULL).put("source", t.source).put("preview", t.isPreview)
            }).toString())
            .apply()
    }

    LaunchedEffect(Unit) {
        fun readArray(key: String): List<String> = try {
            val a = JSONArray(prefs.getString(key, "[]"))
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        } catch (_: Exception) { emptyList() }
        favorites = readArray("favorites").toSet()
        recent = readArray("recent")
        playlists = try {
            val obj = JSONObject(prefs.getString("playlists", "{}"))
            buildMap { val keys = obj.keys(); while (keys.hasNext()) { val k = keys.next(); val a = obj.optJSONArray(k) ?: JSONArray(); put(k, (0 until a.length()).map { a.optString(it) }) } }
        } catch (_: Exception) { emptyMap() }
        knownTracks = try {
            val array = JSONArray(prefs.getString("known_tracks", "[]"))
            buildMap {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val uri = item.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: continue
                    val track = AudioTrack(item.optLong("id"), uri, item.optString("title"), item.optString("artist"),
                        item.optString("album"), item.optLong("duration"), item.optString("artwork").takeIf { it.isNotBlank() && it != "null" },
                        item.optString("source", "Free To Use"), item.optBoolean("preview", false))
                    put(track.key, track)
                }
            }
        } catch (_: Exception) { emptyMap() }
    }

    val currentPlayer = player()
    val playbackScope = rememberCoroutineScope()
    fun playFromLibrary(queue: List<AudioTrack>, index: Int) {
        playbackScope.launch {
            playbackError = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val playable = mutableListOf<Pair<Int, AudioTrack>>()
                    var selectedError: Exception? = null
                    queue.forEachIndexed { originalIndex, track ->
                        try {
                            // Providers can return expired or transiently invalid stream URLs.
                            // Resolve each item independently so one broken queue entry cannot
                            // prevent the currently selected track from starting.
                            var lastError: Exception? = null
                            var stream: String? = null
                            repeat(2) { attempt ->
                                if (stream == null) {
                                    try {
                                        stream = NuclearStyleSearch.resolve(track)
                                    } catch (e: Exception) {
                                        lastError = e
                                        if (attempt == 0) Thread.sleep(350)
                                    }
                                }
                            }
                            val resolvedStream = stream?.takeIf { it.isNotBlank() }
                                ?: throw (lastError ?: IllegalStateException("источник не вернул ссылку на аудио"))
                            playable += originalIndex to track.copy(uri = Uri.parse(resolvedStream), isPreview = false)
                        } catch (e: Exception) {
                            if (originalIndex == index) selectedError = e
                        }
                    }
                    if (selectedError != null) throw IllegalStateException(
                        "Не удалось получить поток для выбранного трека: " +
                            (selectedError?.message ?: "источник временно недоступен"),
                        selectedError
                    )
                    val selectedPosition = playable.indexOfFirst { it.first == index }
                    if (selectedPosition < 0) throw IllegalStateException("Выбранный трек недоступен")
                    playable.map { it.second } to selectedPosition
                }
                onPlay(result.first, result.second)
            } catch (e: Exception) {
                val detail = (e.message ?: e.cause?.message ?: "неизвестная ошибка").take(180)
                playbackError = "Не удалось открыть аудио: $detail"
            }
        }
    }
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("Выбери трек") }
    var currentArtist by remember { mutableStateOf("Твоя музыка начинается здесь") }
    var currentArtwork by remember { mutableStateOf<String?>(null) }
    var currentIsPreview by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var fullPlayerOpen by remember { mutableStateOf(false) }
    DisposableEffect(currentPlayer) {
        val c = currentPlayer
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Выбери трек"
                currentArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Твоя музыка начинается здесь"
                currentArtwork = mediaItem?.mediaMetadata?.artworkUri?.toString()
                currentIsPreview = (mediaItem?.localConfiguration?.tag as? AudioTrack)?.isPreview == true
                position = 0L
                duration = c?.duration?.coerceAtLeast(0L) ?: 0L
                val key = mediaItem?.mediaId
                if (key != null) { recent = (listOf(key) + recent.filterNot { it == key }).take(50); saveState() }
            }
            override fun onPlaybackStateChanged(playbackState: Int) { duration = c?.duration?.coerceAtLeast(0L) ?: 0L }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = "Ошибка плеера: ${error.errorCodeName} — ${(error.message ?: "источник не отдал аудио").take(160)}"
            }
        }
        c?.addListener(listener)
        onDispose { c?.removeListener(listener) }
    }
    LaunchedEffect(currentPlayer, isPlaying) {
        while (true) {
            currentPlayer?.let { position = it.currentPosition.coerceAtLeast(0L); duration = it.duration.coerceAtLeast(0L) }
            kotlinx.coroutines.delay(500)
        }
    }

    val filtered = when {
        tab == "Избранное" -> knownTracks.values.filter { it.key in favorites }
        tab == "Недавние" -> recent.mapNotNull { key -> knownTracks[key] }
        tab == "Плейлисты" && selectedPlaylist != null -> (playlists[selectedPlaylist] ?: emptyList()).mapNotNull { key -> knownTracks[key] }
        tab == "Поиск" -> onlineResults
        else -> tracks
    }

    MaterialTheme(colorScheme = darkColorScheme(background = backgroundColor, surface = SurfaceA, primary = Lilac, onPrimary = Ink, onBackground = White, onSurface = White, secondary = Muted)) {
        Scaffold(containerColor = backgroundColor, bottomBar = {
            Column(Modifier.background(backgroundColor)) {
                if (currentPlayer?.currentMediaItem != null) {
                    MiniPlayer(currentTitle, currentArtist, currentArtwork, isPlaying, position, duration,
                        onOpen = { fullPlayerOpen = true },
                        onToggle = { if (isPlaying) currentPlayer.pause() else currentPlayer?.play() },
                        onNext = { currentPlayer?.seekToNextMediaItem() },
                        onSeek = { currentPlayer?.seekTo(it) })
                }
                NavigationBar(containerColor = SurfaceA, contentColor = White) {
                    listOf("Главная" to Icons.Rounded.Home, "Медиатека" to Icons.Rounded.LibraryMusic, "Поиск" to Icons.Rounded.Search, "Избранное" to Icons.Rounded.Favorite, "Профиль" to Icons.Rounded.Person).forEach { (name, icon) ->
                        NavigationBarItem(selected = tab == name, onClick = { tab = name; selectedPlaylist = null }, icon = { Icon(icon, null) }, label = { Text(name) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Lilac, indicatorColor = SurfaceB, selectedTextColor = Lilac, unselectedTextColor = Muted))
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).drawBehind {
                if (animationEnabled) {
                    val radius = size.minDimension * 0.43f
                    drawCircle(color = glowColor.copy(alpha = 0.22f), radius = radius, center = Offset(size.width * orbX, size.height * orbY))
                    drawCircle(color = Color(0xFFFFD21F).copy(alpha = 0.13f), radius = radius * 0.82f, center = Offset(size.width * (1f - orbX * 0.72f), size.height * (1f - orbY * 0.62f)))
                }
            }.background(Brush.verticalGradient(listOf(glowColor.copy(alpha = ambientAlpha), backgroundColor, backgroundColor.copy(red = (backgroundColor.red * 0.72f), green = (backgroundColor.green * 0.72f), blue = (backgroundColor.blue * 0.72f))))) ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VYNIX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = White)
                        Text("YOUR SOUND. YOUR SPACE.", style = MaterialTheme.typography.labelSmall, color = Lilac, letterSpacing = 2.spCompat())
                        Text(if (animationEnabled) "LIVE AMBIENT • ON" else "LIVE AMBIENT • OFF", style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(SurfaceB).clickable { showAppearance = true }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Palette, "Настроить фон", tint = Lilac, modifier = Modifier.size(25.dp)) }
                }
                playbackError?.let { message ->
                    Text(message, color = Lilac, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp))
                }
                when {
                    tab == "Главная" -> HomeScreen(favorites.size, recent.size, onOpen = { tab = it }, onPlay = { tab = "Поиск" })
                    tab == "Профиль" -> AccountScreen()
                    tab == "Медиатека" && selectedPlaylist == null -> PlaylistLibraryScreen(playlists, onCreate = { createPlaylist = true }, onOpenPlaylist = { selectedPlaylist = it; tab = "Плейлисты" })
                    tab == "Поиск" -> {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                            placeholder = { Text("Название песни или исполнитель…") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Lilac,
                                unfocusedBorderColor = SurfaceB,
                                focusedTextColor = White,
                                unfocusedTextColor = White
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        OpenAudioSearch(
                            query = search,
                            onResultsChange = { found ->
                                onlineResults = found
                                knownTracks = knownTracks + found.associateBy { it.key }
                                saveState()
                            },
                            onPlayTrack = { track ->
                                // Keep the resolved stream URL; looking up the original result here
                                // discarded it and caused the stream resolver to run a second time.
                                knownTracks = knownTracks + (track.key to track)
                                saveState()
                                playFromLibrary(listOf(track), 0)
                            }
                        )
                    }
                    tab == "Избранное" -> TrackList(filtered, favorites, ::playFromLibrary, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = "Избранное")
                    tab == "Недавние" -> TrackList(filtered, favorites, ::playFromLibrary, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = "Недавно слушали")
                    tab == "Плейлисты" && selectedPlaylist != null -> TrackList(filtered, favorites, ::playFromLibrary, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = selectedPlaylist ?: "Плейлист", onBack = { selectedPlaylist = null; tab = "Медиатека" })
                    tab == "Плейлисты" -> PlaylistLibraryScreen(playlists, onCreate = { createPlaylist = true }, onOpenPlaylist = { selectedPlaylist = it })
                    else -> HomeScreen(favorites.size, recent.size, onOpen = { tab = it }, onPlay = { tab = "Поиск" })
                }
            }
        }
    }

    if (showAppearance) {
        AlertDialog(
            onDismissRequest = { showAppearance = false },
            containerColor = SurfaceA,
            title = { Text("Живой фон VYNIX", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Цветовая тема", color = Muted)
                    BackgroundPalettes.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { item ->
                                val index = BackgroundPalettes.indexOf(item)
                                val selected = paletteIndex == index && customTopHex.isBlank()
                                Surface(
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable {
                                        paletteIndex = index
                                        customTopHex = ""
                                        saveAppearance()
                                    },
                                    color = Color(item.second),
                                    shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Lilac else White.copy(alpha = .18f))
                                ) {
                                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(7.dp)).background(Brush.horizontalGradient(listOf(Color(item.second), Color(item.third)))))
                                        Text(item.first, color = White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    OutlinedTextField(
                        value = customTopHex,
                        onValueChange = { value -> customTopHex = value.filter { it.isLetterOrDigit() || it == '#' }.take(7); saveAppearance() },
                        label = { Text("Свой цвет HEX") },
                        placeholder = { Text("#6A3C8B") },
                        singleLine = true,
                        supportingText = { Text("Например, #A46CFF", color = Muted) }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Живая анимация")
                            Text("Плавные световые переливы", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = animationEnabled, onCheckedChange = { animationEnabled = it; saveAppearance() })
                    }
                    Text("Скорость анимации: ${String.format(java.util.Locale.US, "%.1f", animationSpeed)}×", color = Muted)
                    Slider(value = animationSpeed, onValueChange = { animationSpeed = it }, onValueChangeFinished = { saveAppearance() }, valueRange = 0.5f..2f, steps = 2)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(glowColor.copy(alpha = .85f), backgroundColor))).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = White)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Предпросмотр", color = White, fontWeight = FontWeight.Bold)
                            Text("Градиент применяется сразу", color = White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { saveAppearance(); showAppearance = false }) { Text("Готово") } },
            dismissButton = { TextButton(onClick = { showAppearance = false }) { Text("Закрыть") } }
        )
    }

    if (createPlaylist) AlertDialog(onDismissRequest = { createPlaylist = false }, containerColor = SurfaceA, title = { Text("Новый плейлист") }, text = { OutlinedTextField(value = playlistName, onValueChange = { playlistName = it }, placeholder = { Text("Название") }, singleLine = true) }, confirmButton = { TextButton(onClick = { val name = playlistName.trim(); if (name.isNotEmpty()) { playlists = playlists + (name to (playlists[name] ?: emptyList())); saveState(); selectedPlaylist = name; tab = "Плейлисты" }; playlistName = ""; createPlaylist = false }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { createPlaylist = false }) { Text("Отмена") } })
    addTarget?.let { track ->
        AlertDialog(onDismissRequest = { addTarget = null }, containerColor = SurfaceA, title = { Text("Добавить в плейлист") }, text = {
            if (playlists.isEmpty()) Text("Сначала создай плейлист в медиатеке.")
            else Column { playlists.keys.forEach { name -> TextButton(onClick = { playlists = playlists + (name to ((playlists[name] ?: emptyList()) + track.key).distinct()); saveState(); addTarget = null }) { Text(name) } } }
        }, confirmButton = { TextButton(onClick = { addTarget = null }) { Text("Закрыть") } })
    }

    if (fullPlayerOpen && currentPlayer?.currentMediaItem != null) {
        FullPlayer(
            title = currentTitle, artist = currentArtist, artworkUrl = currentArtwork, isPreview = currentIsPreview,
            playing = isPlaying, position = position, duration = duration,
            onClose = { fullPlayerOpen = false },
            onToggle = { if (isPlaying) currentPlayer.pause() else currentPlayer.play() },
            onPrevious = { currentPlayer.seekToPreviousMediaItem() },
            onNext = { currentPlayer.seekToNextMediaItem() },
            onSeek = { currentPlayer.seekTo(it) }
        )
    }
}


@Composable
private fun OpenAudioSearch(query: String, onResultsChange: (List<AudioTrack>) -> Unit, onPlayTrack: (AudioTrack) -> Unit) {
    var results by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(query, retry) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); onResultsChange(emptyList()); error = null; loading = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        loading = true; error = null
        try {
            results = withContext(Dispatchers.IO) { searchMultiSourceTracks(q) }
            onResultsChange(results)
            if (results.isEmpty()) error = "Ничего не найдено в доступных каталогах. Попробуй другое название."
        } catch (e: Exception) {
            error = e.message?.takeIf { it.isNotBlank() } ?: "Не удалось подключиться к музыкальным каталогам. Проверь интернет и повтори поиск."
            results = emptyList()
            onResultsChange(emptyList())
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = Lilac, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Поиск музыки VYNIX", color = White, fontWeight = FontWeight.SemiBold)
                Text("Deezer · Audius · YouTube", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Lilac)
        }
        if (query.trim().length >= 2) {
            TextButton(
                onClick = {
                    val youtubeQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$youtubeQuery"))
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        error = "Не удалось открыть YouTube. Проверь, установлен ли браузер."
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = Lilac)
                Spacer(Modifier.width(8.dp))
                Text("Искать эту песню на YouTube", color = Lilac)
            }
        }
        when {
            query.trim().length < 2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Search, null, tint = Lilac, modifier = Modifier.size(48.dp)); Spacer(Modifier.height(10.dp))
                    Text("Найди музыку", color = White, fontWeight = FontWeight.SemiBold)
                    Text("Введи название песни или исполнителя", color = Muted)
                    Text("Онлайн-поиск; файлы телефона не сканируются", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.key }) { track ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceA).clickable {
                        if (playingId != track.id) scope.launch {
                            playingId = track.id; error = null
                            try {
                                val stream = withContext(Dispatchers.IO) {
                                    NuclearStyleSearch.resolve(track)
                                }
                                onPlayTrack(track.copy(uri = Uri.parse(stream), isPreview = false))
                            } catch (e: Exception) {
                                error = "Не удалось запустить трек: " + (e.message ?: e.javaClass.simpleName).take(220)
                            } catch (e: LinkageError) {
                                error = "Ошибка музыкального модуля: " + (e.message ?: e.javaClass.simpleName).take(180)
                            } finally { playingId = null }
                        }
                    }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = track.artworkUrl, contentDescription = "Обложка " + track.title, modifier = Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(track.artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.source, color = Lilac, style = MaterialTheme.typography.labelSmall)
                            if (track.duration > 0L) Text(formatPlayerTime(track.duration), color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        if (playingId == track.id) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp, color = Lilac)
                        else Icon(Icons.Rounded.PlayCircle, "Воспроизвести трек", tint = Lilac, modifier = Modifier.size(32.dp))
                    }
                }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lilac) }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CloudOff, null, tint = Muted, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(10.dp))
                    Text(error ?: "", color = Muted)
                    TextButton(onClick = { retry++; error = null }) { Text("Повторить поиск", color = Lilac) }
                }
            }
        }
    }

}

private fun searchMultiSourceTracks(query: String): List<AudioTrack> =
    NuclearStyleSearch.search(query)

private fun Int.spCompat() = this.sp

private fun String.toColorOrNull(): Color? = try {
    val value = trim().removePrefix("#")
    if (value.length != 6) null else Color(android.graphics.Color.parseColor("#$value"))
} catch (_: Exception) { null }

@Composable
private fun HomeScreen(favorites: Int, recent: Int, onOpen: (String) -> Unit, onPlay: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF4B2B63), Color(0xFF211A31), Color(0xFF15121D)))).clickable { onPlay() }.padding(22.dp)) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text("LISTEN DIFFERENT", color = Lilac, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.spCompat())
                    Spacer(Modifier.height(10.dp))
                    Text("Твой звук.\nТвои правила.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = White)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PlayArrow, null, tint = Ink); Text("  Слушать музыку", color = White) }
                }
                Icon(Icons.Rounded.GraphicEq, null, tint = Color(0x66E1B8FF), modifier = Modifier.align(Alignment.BottomEnd).size(100.dp))
            }
        }
        item { Text("МУЗЫКА ОНЛАЙН", color = Muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.spCompat()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Поиск", "∞", Icons.Rounded.Search, Modifier.weight(1f)) { onOpen("Поиск") }
            StatCard("Избранное", favorites.toString(), Icons.Rounded.Favorite, Modifier.weight(1f)) { onOpen("Избранное") }
            StatCard("Недавние", recent.toString(), Icons.Rounded.History, Modifier.weight(1f)) { onOpen("Недавние") }
        } }
        item { Text("Открывай музыку", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        
        item { FeatureRow("Плейлисты", "Собери музыку под настроение", Icons.Rounded.QueueMusic) { onOpen("Медиатека") } }
        item { FeatureRow("Онлайн-поиск", "Ищи музыку в музыкальных сервисах", Icons.Rounded.Search) { onOpen("Поиск") } }
    }
}

@Composable
private fun StatCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(SurfaceA).clickable(onClick = onClick).padding(12.dp)) {
        Icon(icon, null, tint = Lilac)
        Spacer(Modifier.height(12.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.labelSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FeatureRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SurfaceA).clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(SurfaceB), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Lilac) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall) }
        Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun PlaylistLibraryScreen(playlists: Map<String, List<String>>, onCreate: () -> Unit, onOpenPlaylist: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 20.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Плейлисты", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Только онлайн-музыка", color = Muted) }
            Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = Ink)) { Icon(Icons.Rounded.Add, null); Text(" Создать") }
        } }
        item { Spacer(Modifier.height(18.dp)) }
        if (playlists.isEmpty()) item { FeatureRow("Создай первый плейлист", "Добавляй найденные онлайн-треки", Icons.Rounded.QueueMusic, onCreate) }
        items(playlists.keys.toList(), key = { it }) { name ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(SurfaceA).clickable { onOpenPlaylist(name) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF7950A5), Color(0xFF292039)))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.QueueMusic, null, tint = White) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text("${playlists[name]?.size ?: 0} треков", color = Muted, style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
    }
}

@Composable
private fun LibraryScreen(tracks: List<AudioTrack>, favorites: Set<String>, playlists: Map<String, List<String>>, onFavorite: (AudioTrack) -> Unit, onPlay: (List<AudioTrack>, Int) -> Unit, onCreate: () -> Unit, onOpenPlaylist: (String) -> Unit, onAdd: (AudioTrack) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, top = 6.dp, end = 18.dp, bottom = 20.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Медиатека", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("${tracks.size} треков на устройстве", color = Muted) }; Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = Ink)) { Icon(Icons.Rounded.Add, null); Text(" Создать") } } }
        item { Spacer(Modifier.height(18.dp)); Text("ПЛЕЙЛИСТЫ", color = Muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.spCompat()); Spacer(Modifier.height(8.dp)) }
        if (playlists.isEmpty()) item { FeatureRow("Создай первый плейлист", "Добавляй треки и собирай подборки", Icons.Rounded.QueueMusic, onCreate) }
        items(playlists.keys.toList(), key = { it }) { name ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(SurfaceA).clickable { onOpenPlaylist(name) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF7950A5), Color(0xFF292039)))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.QueueMusic, null, tint = White) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text("${playlists[name]?.size ?: 0} треков", color = Muted, style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Rounded.ChevronRight, null, tint = Muted)
            }
        }
        item { Spacer(Modifier.height(20.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Text("ВСЕ ТРЕКИ", color = Muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.spCompat()); Spacer(Modifier.weight(1f)); Text("${tracks.size}", color = Lilac) } }
        items(tracks, key = { it.key }) { track -> TrackRow(track, track.key in favorites, onClick = { onPlay(tracks, tracks.indexOf(track)) }, onFavorite = { onFavorite(track) }, onAdd = { onAdd(track) }) }
    }
}

@Composable
private fun TrackList(tracks: List<AudioTrack>, favorites: Set<String>, onPlay: (List<AudioTrack>, Int) -> Unit, onFavorite: (AudioTrack) -> Unit, onAdd: (AudioTrack) -> Unit, title: String = "", onBack: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        if (title.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null) }; Column { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${tracks.size} треков", color = Muted, style = MaterialTheme.typography.bodySmall) } }
        if (tracks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Здесь пока пусто", color = Muted) }
        else LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) { items(tracks, key = { it.key }) { track -> TrackRow(track, track.key in favorites, onClick = { onPlay(tracks, tracks.indexOf(track)) }, onFavorite = { onFavorite(track) }, onAdd = { onAdd(track) }) } }
    }
}

@Composable
private fun TrackRow(track: AudioTrack, favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(SurfaceA).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Color(0xFF543B6B), Color(0xFF282133)))), contentAlignment = Alignment.Center) { if (track.artworkUrl != null) AsyncImage(model = track.artworkUrl, contentDescription = "Обложка ${track.album}", modifier = Modifier.fillMaxSize()) else Icon(Icons.Rounded.Album, null, tint = Lilac, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist + if (track.source != "На устройстве") " · ${track.source}" else "", color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (favorite) Lilac else Muted) }
        IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null, tint = Muted) }
    }
}

@Composable
private fun MiniPlayer(title: String, artist: String, artworkUrl: String?, playing: Boolean, position: Long, duration: Long, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).clip(RoundedCornerShape(20.dp)).background(SurfaceB).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF9D6BC4), Color(0xFF352543)))), contentAlignment = Alignment.Center) { if (artworkUrl != null) AsyncImage(model = artworkUrl, contentDescription = "Обложка трека", modifier = Modifier.fillMaxSize()) else Icon(Icons.Rounded.GraphicEq, null, tint = White) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = onToggle) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = White) }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, null, tint = White) }
        }
        Slider(value = if (dragging >= 0f) dragging else if (duration > 0L) position.toFloat() / duration else 0f, onValueChange = { dragging = it }, onValueChangeFinished = { if (duration > 0) onSeek((dragging * duration).toLong()); dragging = -1f }, modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), colors = SliderDefaults.colors(thumbColor = Lilac, activeTrackColor = Lilac, inactiveTrackColor = Muted.copy(alpha = .25f)))
    }
}

@Composable
private fun FullPlayer(title: String, artist: String, artworkUrl: String?, isPreview: Boolean, playing: Boolean, position: Long, duration: Long, onClose: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            val backdrop = Modifier.fillMaxSize()
            if (artworkUrl != null) AsyncImage(model = artworkUrl, contentDescription = null, modifier = backdrop, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC49305F), Color(0xF2100E17)))))
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер", tint = White, modifier = Modifier.size(32.dp)) }
                Column(Modifier.weight(1f)) { Text("СЕЙЧАС ИГРАЕТ", color = Muted, style = MaterialTheme.typography.labelMedium); if (isPreview) Text("30-секундный фрагмент · источник не отдаёт полный трек", color = Color(0xFFFFD180), style = MaterialTheme.typography.labelSmall, maxLines = 2) }
                Icon(Icons.Rounded.GraphicEq, "VYNIX", tint = Lilac)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF9D6BC4), Color(0xFF352543), Color(0xFF17121F)))), contentAlignment = Alignment.Center) { if (artworkUrl != null) AsyncImage(model = artworkUrl, contentDescription = "Обложка $title", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) else Icon(Icons.Rounded.Album, null, tint = White.copy(alpha = .9f), modifier = Modifier.fillMaxSize(.42f)) }
                Spacer(Modifier.height(30.dp))
                Text(title, color = White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(artist, color = Muted, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxWidth()) {
                Slider(value = if (dragging >= 0f) dragging else if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f, onValueChange = { dragging = it }, onValueChangeFinished = { if (duration > 0) onSeek((dragging * duration).toLong()); dragging = -1f }, colors = SliderDefaults.colors(thumbColor = Lilac, activeTrackColor = Lilac, inactiveTrackColor = Muted.copy(alpha = .35f)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlayerTime(if (dragging >= 0f) (dragging * duration).toLong() else position), color = Muted, style = MaterialTheme.typography.labelSmall)
                    Text(formatPlayerTime(duration), color = Muted, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Предыдущий трек", tint = White, modifier = Modifier.size(34.dp)) }
                    IconButton(onClick = onToggle, modifier = Modifier.size(76.dp).clip(CircleShape).background(Lilac)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Пауза" else "Воспроизвести", tint = Ink, modifier = Modifier.size(46.dp)) }
                    IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Следующий трек", tint = White, modifier = Modifier.size(34.dp)) }
                }
            }
            }
        }
    }
}

private fun formatPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

