package app.vynix

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val VYNIX_API_BASE = "https://vynix-api.onrender.com"

@Composable
internal fun AccountScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("vynix_account", 0) }
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var accountName by remember { mutableStateOf(prefs.getString("username", null)) }
    var badge by remember { mutableStateOf(prefs.getString("badge", "LISTENER") ?: "LISTENER") }
    var registering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(token) {
        val currentToken = token ?: return@LaunchedEffect
        try {
            val result = withContext(Dispatchers.IO) { accountRequest("/v1/auth/me", "GET", null, currentToken) }
            val account = result.optJSONObject("account")
            accountName = account?.optString("username")?.takeIf { it.isNotBlank() } ?: accountName
            badge = account?.optString("badge")?.takeIf { it.isNotBlank() } ?: "LISTENER"
            prefs.edit().putString("username", accountName).putString("badge", badge).apply()
            message = ""
        } catch (_: Exception) {
            message = "Не удалось связаться с сервером. Попробуй ещё раз."
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xCC65418A), Color(0xAA292039), Color(0xAA171521))))
            .padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFE0B5FF), Color(0xFF8B5CC1)))),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, tint = Color(0xFF17111F), modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(accountName ?: "Твой VYNIX", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text(if (token != null) "Аккаунт подключён" else "Музыка. Профиль. Твой стиль.", color = Color(0xFFE1D4EF))
                }
            }
            Spacer(Modifier.height(18.dp))
            Surface(color = Color(0x332FFFFFF), shape = RoundedCornerShape(100.dp), tonalElevation = 2.dp) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Verified, null, tint = Color(0xFFFFD78A), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(badgeLabel(badge), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (token == null) {
            Text("Аккаунт VYNIX", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Создай аккаунт или войди, чтобы привязать профиль и серверные бейджи.", color = Color(0xFFA8A1B7))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Имя пользователя") }, leadingIcon = { Icon(Icons.Rounded.Person, null) },
                supportingText = { Text("3–32 символа: латиница, цифры, . _ -") },
                shape = RoundedCornerShape(18.dp)
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Пароль") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = { Text("Минимум 8 символов") },
                shape = RoundedCornerShape(18.dp)
            )
            Button(
                onClick = {
                    if (username.trim().length < 3 || password.length < 8) {
                        message = "Проверь имя пользователя и пароль (не менее 8 символов)."
                    } else {
                        busy = true
                        message = ""
                        scope.launch {
                            try {
                                val path = if (registering) "/v1/auth/register" else "/v1/auth/login"
                                val result = withContext(Dispatchers.IO) {
                                    accountRequest(path, "POST", JSONObject().put("username", username.trim()).put("password", password).toString(), null)
                                }
                                val newToken = result.optString("token")
                                val account = result.optJSONObject("account")
                                if (newToken.isBlank() || account == null) error("Ответ сервера неполный")
                                token = newToken
                                accountName = account.optString("username", username.trim())
                                badge = account.optString("badge", "LISTENER")
                                prefs.edit().putString("token", newToken).putString("username", accountName).putString("badge", badge).apply()
                                password = ""
                                message = "Успешно! Аккаунт подключён."
                            } catch (e: Exception) {
                                message = when {
                                    e.message?.contains("409") == true -> "Это имя уже занято. Попробуй войти."
                                    e.message?.contains("401") == true -> "Неверное имя или пароль."
                                    e.message?.contains("503") == true -> "Сервер или база данных временно недоступны."
                                    else -> "Не удалось подключиться. Проверь сеть и повтори."
                                }
                            } finally { busy = false }
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCE9BFF), contentColor = Color(0xFF17111F)),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (registering) "Создать аккаунт" else "Войти", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { registering = !registering; message = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (registering) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться", color = Color(0xFFCE9BFF))
            }
        } else {
            Text("Твой профиль", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            AccountFeatureRow("Серверный профиль", "Вход защищён токеном с ограниченным сроком", Icons.Rounded.CloudDone)
            AccountFeatureRow("Бейдж", badgeLabel(badge), Icons.Rounded.Verified)
            AccountFeatureRow("Приватность", "Музыкальные файлы не отправляются на сервер", Icons.Rounded.Shield)
            OutlinedButton(
                onClick = {
                    val oldToken = token
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { accountRequest("/v1/auth/logout", "POST", null, oldToken) } }
                        prefs.edit().remove("token").remove("username").remove("badge").apply()
                        token = null; accountName = null; badge = "LISTENER"; message = "Ты вышел из аккаунта."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Icon(Icons.Rounded.Logout, null); Spacer(Modifier.width(8.dp)); Text("Выйти из аккаунта") }
        }
        if (message.isNotBlank()) {
            Text(message, color = if (message.startsWith("Успешно") || message.startsWith("Ты вышел")) Color(0xFF9BE6BA) else Color(0xFFFFB4AB), style = MaterialTheme.typography.bodyMedium)
        }
        Text("Совет: используй уникальный пароль, который не применяешь в других сервисах.", color = Color(0xFFA8A1B7), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccountFeatureRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Brush.horizontalGradient(listOf(Color(0xAA282235), Color(0x661C1926))))
        .padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFFCE9BFF), modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(12.dp))
        Column { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Color(0xFFA8A1B7), style = MaterialTheme.typography.bodySmall) }
    }
}

private fun badgeLabel(id: String): String = when (id.uppercase()) {
    "EARLY" -> "Ранний пользователь VYNIX"
    "CREATOR" -> "Создатель плейлистов"
    "TEAM" -> "Команда VYNIX"
    else -> "Слушатель VYNIX"
}

private fun accountRequest(path: String, method: String, body: String?, token: String?): JSONObject {
    val connection = (URL(VYNIX_API_BASE + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 12000
        readTimeout = 15000
        setRequestProperty("Accept", "application/json")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
    }
    try {
        if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: $response")
        return JSONObject(response)
    } finally { connection.disconnect() }
}
