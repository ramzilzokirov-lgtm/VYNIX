package app.vynix

import android.net.Uri
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    val key: String get() = uri.toString()
}

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
    }

    val currentPlayer = player()
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
        tab == "Избранное" -> onlineResults.filter { it.key in favorites }
        tab == "Недавние" -> recent.mapNotNull { key -> onlineResults.find { it.key == key } }
        tab == "Плейлисты" && selectedPlaylist != null -> onlineResults.filter { it.key in (playlists[selectedPlaylist] ?: emptyList()) }
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
                            onPlayTrack = { track -> onPlay(listOf(track), 0) }
                        )
                    }
                    tab == "Избранное" -> TrackList(filtered, favorites, onPlay, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = "Избранное")
                    tab == "Недавние" -> TrackList(filtered, favorites, onPlay, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = "Недавно слушали")
                    tab == "Плейлисты" && selectedPlaylist != null -> TrackList(filtered, favorites, onPlay, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = selectedPlaylist ?: "Плейлист", onBack = { selectedPlaylist = null; tab = "Медиатека" })
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
private fun OpenAudioSearch(query: String, onPlayTrack: (AudioTrack) -> Unit) {
    var results by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryCounter by remember { mutableIntStateOf(0) }
    LaunchedEffect(query, retryCounter) {
        val normalized = query.trim()
        if (normalized.length < 2) { results = emptyList(); error = null; loading = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        loading = true; error = null
        try {
            results = withContext(Dispatchers.IO) { searchAudiusTracks(normalized) }
            if (results.isEmpty()) error = "Audius не нашёл доступных для потокового воспроизведения треков. Попробуй другое название или проверь соединение."
        } catch (e: Exception) {
            error = if (e.message?.contains("timeout", true) == true || e.message?.contains("timed out", true) == true)
                "Audius отвечает слишком долго. Проверь соединение и повтори поиск."
            else "Не удалось подключиться к Audius. Проверь соединение и повтори поиск."
            results = emptyList()
        } finally { loading = false }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GraphicEq, null, tint = Lilac, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Поиск музыки", color = White, fontWeight = FontWeight.SemiBold)
                Text("Audius · потоковое воспроизведение · без аккаунта", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Lilac)
        }
        when {
            query.trim().length < 2 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Search, null, tint = Lilac, modifier = Modifier.size(48.dp)); Spacer(Modifier.height(10.dp))
                    Text("Найди музыку", color = White, fontWeight = FontWeight.SemiBold)
                    Text("Введи название песни или исполнителя", color = Muted)
                }
            }
            results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.key }) { track ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceA).clickable { onPlayTrack(track) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = track.artworkUrl, contentDescription = "Обложка ${track.title}", modifier = Modifier.size(62.dp).clip(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, color = White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(track.artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.source, color = Lilac, style = MaterialTheme.typography.labelSmall)
                            if (track.duration > 0L) Text(formatPlayerTime(track.duration), color = Muted, style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(Icons.Rounded.PlayCircle, contentDescription = "Воспроизвести трек", tint = Lilac, modifier = Modifier.size(32.dp))
                    }
                }
            }
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lilac) }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CloudOff, null, tint = Muted, modifier = Modifier.size(42.dp)); Spacer(Modifier.height(10.dp))
                    Text(error ?: "", color = Muted)
                    TextButton(onClick = { results = emptyList(); error = null; retryCounter++ }) { Text("Повторить поиск", color = Lilac) }
                }
            }
        }
    }
}
private fun searchAudiusTracks(query: String): List<AudioTrack> {
    val encoded = URLEncoder.encode(query.trim(), "UTF-8")
    val url = URL("https://api.audius.co/v1/tracks/search?query=$encoded&limit=25&app_name=VYNIX")
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 9000; readTimeout = 15000; requestMethod = "GET"
        setRequestProperty("User-Agent", "VYNIX-Android/2.2"); setRequestProperty("Accept", "application/json")
    }
    return try {
        val status = connection.responseCode
        if (status !in 200..299) throw IllegalStateException("Audius HTTP $status")
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val data = root.optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            if (!item.optBoolean("is_streamable", true)) return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val artist = item.optJSONObject("user")?.optString("name")?.takeIf { it.isNotBlank() && it != "null" } ?: "Неизвестный исполнитель"
            val artwork = item.optJSONObject("artwork")
            val artworkUrl = artwork?.optString("480x480")?.takeIf { it.startsWith("http") } ?: artwork?.optString("150x150")?.takeIf { it.startsWith("http") }
            AudioTrack(id = id.hashCode().toLong(), uri = Uri.parse("https://api.audius.co/v1/tracks/$id/stream?app_name=VYNIX"),
                title = title, artist = artist, album = item.optString("genre").takeIf { it.isNotBlank() && it != "null" } ?: "",
                duration = item.optLong("duration", 0L).coerceAtLeast(0L) * 1000L, artworkUrl = artworkUrl,
                source = "Audius · полный трек", isPreview = false)
        }.distinctBy { it.key }
    } finally { connection.disconnect() }
}

private fun searchMusicServices(query: String): List<AudioTrack> {
    val cleaned = query.trim().replace(Regex("[\\\\/:()\\[\\]{}]"), " ").replace(Regex("\\s+"), " ")
    if (cleaned.length < 2) return emptyList()

    val lucene = "mediatype:audio AND (title:\"${cleaned}\" OR creator:\"${cleaned}\") AND (licenseurl:*creativecommons.org* OR licenseurl:*publicdomain*)"
    val encodedQuery = URLEncoder.encode(lucene, "UTF-8")
    val searchUrl = "https://archive.org/advancedsearch.php?q=$encodedQuery&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year&fl[]=licenseurl&rows=18&page=1&output=json"
    val connection = (URL(searchUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000
        readTimeout = 12000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "VYNIX-Android/2.1 (open audio search)")
        setRequestProperty("Accept", "application/json")
    }
    val docs = try {
        if (connection.responseCode !in 200..299) return emptyList()
        val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        root.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
    } catch (_: Exception) {
        return emptyList()
    } finally {
        connection.disconnect()
    }

    val executor = java.util.concurrent.Executors.newFixedThreadPool(5)
    return try {
        val tasks = (0 until docs.length()).mapNotNull { index ->
            val item = docs.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("identifier").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            executor.submit<AudioTrack?> {
                var metadataConnection: HttpURLConnection? = null
                try {
                    metadataConnection = (URL("https://archive.org/metadata/$id").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 8000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "VYNIX-Android/2.1")
                        setRequestProperty("Accept", "application/json")
                    }
                    if (metadataConnection.responseCode !in 200..299) return@submit null
                    val metadataRoot = JSONObject(metadataConnection.inputStream.bufferedReader().use { it.readText() })
                    val files = metadataRoot.optJSONArray("files") ?: JSONArray()
                    val candidates = (0 until files.length()).mapNotNull { fileIndex ->
                        files.optJSONObject(fileIndex)
                    }.filter { file ->
                        val name = file.optString("name").lowercase()
                        val format = file.optString("format").lowercase()
                        val audioExtension = name.endsWith(".mp3") || name.endsWith(".m4a") ||
                            name.endsWith(".ogg") || name.endsWith(".oga") || name.endsWith(".flac") ||
                            name.endsWith(".wav") || name.endsWith(".opus")
                        val notDerivative = !name.contains("sample") && !name.contains("spectrogram") &&
                            !name.endsWith(".torrent") && !name.endsWith(".xml") && !name.endsWith(".sqlite")
                        audioExtension && notDerivative && !format.contains("metadata")
                    }
                    val file = candidates.sortedBy { candidate ->
                        val name = candidate.optString("name").lowercase()
                        when {
                            name.endsWith(".mp3") -> 0
                            name.endsWith(".m4a") -> 1
                            name.endsWith(".ogg") || name.endsWith(".oga") -> 2
                            name.endsWith(".flac") -> 3
                            else -> 4
                        }
                    }.firstOrNull() ?: return@submit null
                    val fileName = file.optString("name")
                    val escapedFile = fileName.split("/").joinToString("/") {
                        URLEncoder.encode(it, "UTF-8").replace("+", "%20")
                    }
                    val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" }
                        ?: metadataRoot.optJSONObject("metadata")?.optString("title")?.takeIf { !it.isNullOrBlank() }
                        ?: id
                    val creator = item.optString("creator").takeIf { it.isNotBlank() && it != "null" }
                        ?: metadataRoot.optJSONObject("metadata")?.optString("creator")?.takeIf { !it.isNullOrBlank() }
                        ?: "Не указан"
                    val seconds = file.optString("length").toDoubleOrNull()
                        ?: metadataRoot.optJSONObject("metadata")?.optString("length")?.toDoubleOrNull()
                        ?: 0.0
                    AudioTrack(
                        id = id.hashCode().toLong(),
                        uri = Uri.parse("https://archive.org/download/$id/$escapedFile"),
                        title = title,
                        artist = creator,
                        album = item.optString("year").takeIf { it.isNotBlank() && it != "null" } ?: "Открытая аудиозапись",
                        duration = (seconds * 1000.0).toLong().coerceAtLeast(0L),
                        artworkUrl = "https://archive.org/services/img/$id",
                        source = if (item.optString("licenseurl").contains("publicdomain", true)) "Internet Archive · общественное достояние" else "Internet Archive · Creative Commons",
                        isPreview = false
                    )
                } catch (_: Exception) {
                    null
                } finally {
                    metadataConnection?.disconnect()
                }
            }
        }
        tasks.mapNotNull { task -> try { task.get(12, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { task.cancel(true); null } }
            .distinctBy { it.key }
    } finally {
        executor.shutdownNow()
    }
}
