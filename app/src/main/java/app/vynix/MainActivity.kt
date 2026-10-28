package app.vynix

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
private val Lilac = Color(0xFFCE9BFF)
private val Muted = Color(0xFFA8A1B7)
private val White = Color(0xFFF8F4FF)

data class AudioTrack(val id: Long, val uri: Uri, val title: String, val artist: String, val album: String, val duration: Long) {
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
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album).build()
            ).build()
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
    var permissionGranted by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("Главная") }
    var search by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(setOf<String>()) }
    var recent by remember { mutableStateOf(listOf<String>()) }
    var playlists by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var createPlaylist by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    var addTarget by remember { mutableStateOf<AudioTrack?>(null) }
    var selectedPlaylist by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("vynix_compose_state", 0) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) refresh++
    }

    fun saveState() {
        prefs.edit()
            .putString("favorites", JSONArray(favorites.toList()).toString())
            .putString("recent", JSONArray(recent.take(50)).toString())
            .putString("playlists", JSONObject().apply { playlists.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString())
            .apply()
    }

    LaunchedEffect(Unit) {
        permissionGranted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
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

    LaunchedEffect(permissionGranted, refresh) {
        if (permissionGranted) tracks = withContext(Dispatchers.IO) {
            val list = mutableListOf<AudioTrack>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION)
            context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    list += AudioTrack(id, ContentUris.withAppendedId(collection, id), cursor.getString(titleCol) ?: "Без названия", cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Неизвестный исполнитель", cursor.getString(albumCol) ?: "", cursor.getLong(durationCol))
                }
            }
            list
        }
    }

    val currentPlayer = player()
    var isPlaying by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("Выбери трек") }
    var currentArtist by remember { mutableStateOf("Твоя музыка начинается здесь") }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    DisposableEffect(currentPlayer) {
        val c = currentPlayer
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "Выбери трек"
                currentArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Твоя музыка начинается здесь"
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
        tab == "Избранное" -> tracks.filter { it.key in favorites }
        tab == "Недавние" -> recent.mapNotNull { key -> tracks.find { it.key == key } }
        tab == "Плейлисты" && selectedPlaylist != null -> tracks.filter { it.key in (playlists[selectedPlaylist] ?: emptyList()) }
        tab == "Поиск" -> tracks.filter { "${it.title} ${it.artist} ${it.album}".contains(search, true) }
        else -> tracks
    }

    MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = SurfaceA, primary = Lilac, onPrimary = Ink, onBackground = White, onSurface = White, secondary = Muted)) {
        Scaffold(containerColor = Ink, bottomBar = {
            Column(Modifier.background(Ink)) {
                if (currentPlayer?.currentMediaItem != null) {
                    MiniPlayer(currentTitle, currentArtist, isPlaying, position, duration,
                        onToggle = { if (isPlaying) currentPlayer.pause() else currentPlayer?.play() },
                        onNext = { currentPlayer?.seekToNextMediaItem() },
                        onSeek = { currentPlayer?.seekTo(it) })
                }
                NavigationBar(containerColor = SurfaceA, contentColor = White) {
                    listOf("Главная" to Icons.Rounded.Home, "Медиатека" to Icons.Rounded.LibraryMusic, "Поиск" to Icons.Rounded.Search, "Избранное" to Icons.Rounded.Favorite).forEach { (name, icon) ->
                        NavigationBarItem(selected = tab == name, onClick = { tab = name; selectedPlaylist = null }, icon = { Icon(icon, null) }, label = { Text(name) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Lilac, indicatorColor = SurfaceB, selectedTextColor = Lilac, unselectedTextColor = Muted))
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(Ink, Color(0xFF17121F)))) ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("VYNIX", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = White)
                        Text("YOUR SOUND. YOUR SPACE.", style = MaterialTheme.typography.labelSmall, color = Lilac, letterSpacing = 2.spCompat())
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).background(SurfaceB), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = Lilac, modifier = Modifier.size(25.dp)) }
                }
                when {
                    !permissionGranted -> PermissionScreen { permissionLauncher.launch(permission) }
                    tab == "Главная" -> HomeScreen(tracks.size, favorites.size, recent.size, onOpen = { tab = it }, onScan = { refresh++ }, onPlay = { if (tracks.isNotEmpty()) onPlay(tracks, 0) })
                    tab == "Медиатека" && selectedPlaylist == null -> LibraryScreen(tracks, favorites, playlists, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onPlay = onPlay, onCreate = { createPlaylist = true }, onOpenPlaylist = { selectedPlaylist = it; tab = "Плейлисты" }, onAdd = { addTarget = it })
                    tab == "Поиск" -> {
                        OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp), placeholder = { Text("Трек, исполнитель, альбом…") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Lilac, unfocusedBorderColor = SurfaceB, focusedTextColor = White, unfocusedTextColor = White))
                        TrackList(filtered, favorites, onPlay, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it })
                    }
                    tab == "Избранное" -> TrackList(filtered, favorites, onPlay, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = "Избранное")
                    tab == "Недавние" -> TrackList(filtered, favorites, onPlay, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = "Недавно слушали")
                    tab == "Плейлисты" && selectedPlaylist != null -> TrackList(filtered, favorites, onPlay, onFavorite = { t -> favorites = if (t.key in favorites) favorites - t.key else favorites + t.key; saveState() }, onAdd = { addTarget = it }, title = selectedPlaylist ?: "Плейлист", onBack = { selectedPlaylist = null; tab = "Медиатека" })
                    tab == "Плейлисты" -> LibraryScreen(tracks, favorites, playlists, onFavorite = {}, onPlay = onPlay, onCreate = { createPlaylist = true }, onOpenPlaylist = { selectedPlaylist = it }, onAdd = { addTarget = it })
                    else -> HomeScreen(tracks.size, favorites.size, recent.size, onOpen = { tab = it }, onScan = { refresh++ }, onPlay = { if (tracks.isNotEmpty()) onPlay(tracks, 0) })
                }
            }
        }
    }

    if (createPlaylist) AlertDialog(onDismissRequest = { createPlaylist = false }, containerColor = SurfaceA, title = { Text("Новый плейлист") }, text = { OutlinedTextField(value = playlistName, onValueChange = { playlistName = it }, placeholder = { Text("Название") }, singleLine = true) }, confirmButton = { TextButton(onClick = { val name = playlistName.trim(); if (name.isNotEmpty()) { playlists = playlists + (name to (playlists[name] ?: emptyList())); saveState(); selectedPlaylist = name; tab = "Плейлисты" }; playlistName = ""; createPlaylist = false }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { createPlaylist = false }) { Text("Отмена") } })
    addTarget?.let { track ->
        AlertDialog(onDismissRequest = { addTarget = null }, containerColor = SurfaceA, title = { Text("Добавить в плейлист") }, text = {
            if (playlists.isEmpty()) Text("Сначала создай плейлист в медиатеке.")
            else Column { playlists.keys.forEach { name -> TextButton(onClick = { playlists = playlists + (name to ((playlists[name] ?: emptyList()) + track.key).distinct()); saveState(); addTarget = null }) { Text(name) } } }
        }, confirmButton = { TextButton(onClick = { addTarget = null }) { Text("Закрыть") } })
    }
}

private fun Int.spCompat() = this.sp

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.LibraryMusic, null, tint = Lilac, modifier = Modifier.size(66.dp))
            Spacer(Modifier.height(18.dp))
            Text("Твоя музыка — в одном месте", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Разреши доступ к аудиофайлам, чтобы VYNIX собрал медиатеку, избранное и плейлисты.", color = Muted)
            Spacer(Modifier.height(22.dp))
            Button(onClick = onRequest, colors = ButtonDefaults.buttonColors(containerColor = Lilac, contentColor = Ink)) { Text("Разрешить доступ") }
        }
    }
}

@Composable
private fun HomeScreen(count: Int, favorites: Int, recent: Int, onOpen: (String) -> Unit, onScan: () -> Unit, onPlay: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF4B2B63), Color(0xFF211A31), Color(0xFF15121D)))).clickable { onPlay() }.padding(22.dp)) {
                Column(Modifier.align(Alignment.CenterStart)) {
                    Text("LISTEN DIFFERENT", color = Lilac, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.spCompat())
                    Spacer(Modifier.height(10.dp))
                    Text("Твой звук.
Твои правила.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = White)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PlayArrow, null, tint = Ink); Text("  Слушать музыку", color = White) }
                }
                Icon(Icons.Rounded.GraphicEq, null, tint = Color(0x66E1B8FF), modifier = Modifier.align(Alignment.BottomEnd).size(100.dp))
            }
        }
        item { Text("ТВОЯ КОЛЛЕКЦИЯ", color = Muted, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.spCompat()) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Треки", count.toString(), Icons.Rounded.MusicNote, Modifier.weight(1f)) { onOpen("Медиатека") }
            StatCard("Избранное", favorites.toString(), Icons.Rounded.Favorite, Modifier.weight(1f)) { onOpen("Избранное") }
            StatCard("Недавние", recent.toString(), Icons.Rounded.History, Modifier.weight(1f)) { onOpen("Недавние") }
        } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Библиотека", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); TextButton(onClick = onScan) { Icon(Icons.Rounded.Refresh, null); Text(" Обновить") } } }
        item { FeatureRow("Все треки", "Вся музыка на устройстве", Icons.Rounded.LibraryMusic) { onOpen("Медиатека") } }
        item { FeatureRow("Плейлисты", "Собери музыку под настроение", Icons.Rounded.QueueMusic) { onOpen("Медиатека") } }
        item { FeatureRow("Поиск", "Найди трек в своей медиатеке", Icons.Rounded.Search) { onOpen("Поиск") } }
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
private fun LibraryScreen(tracks: List<AudioTrack>, favorites: Set<String>, playlists: Map<String, List<String>>, onFavorite: (AudioTrack) -> Unit, onPlay: (List<AudioTrack>, Int) -> Unit, onCreate: () -> Unit, onOpenPlaylist: (String) -> Unit, onAdd: (AudioTrack) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp, bottom = 20.dp)) {
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
        Box(Modifier.size(54.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(Color(0xFF543B6B), Color(0xFF282133)))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Album, null, tint = Lilac, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        IconButton(onClick = onFavorite) { Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (favorite) Lilac else Muted) }
        IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null, tint = Muted) }
    }
}

@Composable
private fun MiniPlayer(title: String, artist: String, playing: Boolean, position: Long, duration: Long, onToggle: () -> Unit, onNext: () -> Unit, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).clip(RoundedCornerShape(20.dp)).background(SurfaceB).animateContentSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF9D6BC4), Color(0xFF352543)))), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.GraphicEq, null, tint = White) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = onToggle) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = White) }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, null, tint = White) }
        }
        Slider(value = if (dragging >= 0f) dragging else if (duration > 0L) position.toFloat() / duration else 0f, onValueChange = { dragging = it }, onValueChangeFinished = { if (duration > 0) onSeek((dragging * duration).toLong()); dragging = -1f }, modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), colors = SliderDefaults.colors(thumbColor = Lilac, activeTrackColor = Lilac, inactiveTrackColor = Muted.copy(alpha = .25f)))
    }
}
