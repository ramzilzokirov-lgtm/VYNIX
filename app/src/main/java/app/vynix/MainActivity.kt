package app.vynix

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0A0B10)
private val Panel = Color(0xFF171923)
private val Accent = Color(0xFFB9A0FF)
private val Muted = Color(0xFFA6A5B4)

private data class Track(val uri: Uri, val title: String, val artist: String = "Локальный файл")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VynixApp() }
    }
}

@Composable
private fun VynixApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableStateOf("Главная") }
    var tracks by remember { mutableStateOf(listOf<Track>()) }
    var current by remember { mutableStateOf<Track?>(null) }
    var query by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var playerOpen by remember { mutableStateOf(false) }
    var likes by remember { mutableStateOf(setOf<String>()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val incoming = uris.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull() ?: "Без названия"
            Track(uri, name.substringBeforeLast('.', name))
        }
        tracks = (tracks + incoming).distinctBy { it.uri.toString() }
        if (current == null) current = incoming.firstOrNull()
    }
    val chooseMusic = { picker.launch(arrayOf("audio/*")) }

    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Accent, onBackground = Color(0xFFF6F3FC), onSurface = Color(0xFFF6F3FC))) {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    "Главная" -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("VYNIX", color = Accent, fontSize = 12.sp, letterSpacing = 5.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Твоя волна", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text("Музыка под твоё настроение", color = Muted)
                        Spacer(Modifier.height(22.dp))
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF2C2543), Color(0xFF151923)))).clickable { if (current != null) { playerOpen = true; playing = true } else chooseMusic() }.padding(22.dp)) {
                            Text("DISCOVER / 01", color = Accent, letterSpacing = 2.sp, fontSize = 10.sp)
                            Spacer(Modifier.height(50.dp))
                            Text("Слушай своё.", fontSize = 25.sp, fontWeight = FontWeight.Medium)
                            Text(if (tracks.isEmpty()) "Добавь музыку с устройства" else "${tracks.size} треков в библиотеке", color = Muted)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { if (current != null) { playerOpen = true; playing = true } else chooseMusic() }, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) { Icon(Icons.Default.PlayArrow, null); Text(" Слушать") }
                        }
                        Spacer(Modifier.height(24.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Недавно добавлено", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { tab = "Медиатека" }) { Text("Все", color = Accent) }
                        }
                        if (tracks.isEmpty()) EmptyState("Здесь пока тихо", "Добавь локальные аудиофайлы, чтобы начать.", "Выбрать музыку", chooseMusic)
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(tracks.takeLast(8).reversed(), key = { it.uri.toString() }) { t -> TrackRow(t, current?.uri == t.uri) { current = t; playing = true; playerOpen = true } } }
                    }
                    "Поиск" -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Text("Поиск", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp), placeholder = { Text("Треки в твоей библиотеке") }, leadingIcon = { Icon(Icons.Default.Search, null) })
                        Spacer(Modifier.height(12.dp))
                        val found = tracks.filter { it.title.contains(query, true) || it.artist.contains(query, true) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(found, key = { it.uri.toString() }) { t -> TrackRow(t, current?.uri == t.uri) { current = t; playing = true; playerOpen = true } } }
                    }
                    "Медиатека" -> Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Медиатека", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = chooseMusic) { Icon(Icons.Default.Add, null, tint = Accent) }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SmallPanel("Любимые", "${likes.size} треков", Modifier.weight(1f))
                            SmallPanel("Локальные", "${tracks.size} треков", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(20.dp))
                        if (tracks.isEmpty()) EmptyState("Библиотека пуста", "Выбери музыку на устройстве.", "Добавить музыку", chooseMusic)
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(tracks, key = { it.uri.toString() }) { t -> TrackRow(t, current?.uri == t.uri) { current = t; playing = true; playerOpen = true } } }
                    }
                    "Обзор" -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Обзор", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text("Источники подключаются поэтапно", color = Muted)
                        listOf("Локальная музыка — доступна", "YouTube Music — требует разрешённой интеграции", "SoundCloud — следующий этап", "VK Музыка — следующий этап", "Яндекс Музыка — следующий этап", "Spotify / Deezer — по возможностям API", "Last.fm — скробблинг в следующем этапе").forEach { label ->
                            Text(label, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp), color = Color(0xFFF6F3FC))
                        }
                    }
                    else -> Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Профиль", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                        Text("VYNIX Listener", fontSize = 22.sp)
                        Text("Локальный профиль • VYNIX Beta 1.2.6", color = Muted)
                        listOf("Статистика", "Внешний вид", "Воспроизведение", "Кэш и данные", "Уведомления", "О приложении").forEach { Text(it, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(16.dp)) }
                    }
                }
            }
            current?.let { track ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(Brush.horizontalGradient(listOf(Color(0xFF28243A), Color(0xFF171923)))).clickable { playerOpen = true }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Album, null, tint = Accent, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(track.artist, color = Muted, fontSize = 11.sp)
                    }
                    IconButton(onClick = { val k = track.uri.toString(); likes = if (k in likes) likes - k else likes + k }) { Icon(if (track.uri.toString() in likes) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = Accent) }
                    IconButton(onClick = { playing = !playing; playerOpen = true }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
                }
            }
            NavigationBar(containerColor = Color(0xFF0E1017), tonalElevation = 0.dp) {
                listOf("Главная" to Icons.Default.Home, "Поиск" to Icons.Default.Search, "Медиатека" to Icons.Default.LibraryMusic, "Обзор" to Icons.Default.Explore, "Профиль" to Icons.Default.Person).forEach { (label, icon) ->
                    NavigationBarItem(selected = tab == label, onClick = { tab = label }, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 10.sp) }, colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Accent.copy(alpha = .12f), unselectedIconColor = Muted))
                }
            }
        }
        if (playerOpen && current != null) AlertDialog(
            onDismissRequest = { playerOpen = false },
            containerColor = Panel,
            title = { Text(current!!.title, maxLines = 2) },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Album, null, tint = Accent, modifier = Modifier.size(100.dp))
                Text(current!!.artist, color = Muted)
                Spacer(Modifier.height(12.dp))
                Text("Экран плеера — начальный каркас. Реальный аудиодвижок будет подключён следующим этапом.", color = Muted)
            } },
            confirmButton = { TextButton(onClick = { playing = !playing }) { Text(if (playing) "Пауза" else "Играть", color = Accent) } },
            dismissButton = { TextButton(onClick = { playerOpen = false }) { Text("Свернуть") } }
        )
    }
}

@Composable private fun TrackRow(track: Track, active: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (active) Accent.copy(alpha = .09f) else Panel).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.MusicNote, null, tint = Accent, modifier = Modifier.size(38.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(track.artist, color = Muted, fontSize = 12.sp)
        }
        Icon(if (active) Icons.Default.GraphicEq else Icons.Default.PlayArrow, null, tint = Accent)
    }
}

@Composable private fun SmallPanel(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF29243B), Panel))).padding(16.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable private fun EmptyState(title: String, description: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(18.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(description, color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onAction) { Text(action, color = Accent) }
    }
}
