package com.vidhi.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val PREFS = "vidhi_preferences"
private const val HISTORY = "conversation_history"
private const val PROVIDER = "provider"
private const val AUTO_SPEAK = "auto_speak"
private const val LANGUAGE = "language"
private const val DARK_THEME = "dark_theme"

data class Msg(val role: String, val text: String)

enum class Screen { CHAT, SETTINGS }

class VidhiViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val api = AiApi()
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var messages by mutableStateOf(loadMessages())
        private set
    var provider by mutableStateOf(prefs.getString(PROVIDER, "auto") ?: "auto")
        private set
    var autoSpeak by mutableStateOf(prefs.getBoolean(AUTO_SPEAK, false))
        private set
    var language by mutableStateOf(prefs.getString(LANGUAGE, "hinglish") ?: "hinglish")
        private set
    var darkTheme by mutableStateOf(prefs.getBoolean(DARK_THEME, true))
        private set
    var busy by mutableStateOf(false)
        private set
    var lastProvider by mutableStateOf("")
        private set
    var backendOnline by mutableStateOf<Boolean?>(null)
        private set

    init { checkBackend() }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        val clean = text.trim()
        messages = messages + Msg("user", clean)
        persist()
        busy = true
        viewModelScope.launch {
            try {
                val result = api.chat(provider, language, messages.takeLast(30).map(::toApi))
                lastProvider = result.provider
                messages = messages + Msg("assistant", result.reply.trim())
                persist()
            } catch (e: Exception) {
                messages = messages + Msg("assistant", "Sorry jaan, connection mein problem aa gayi. Backend URL, server aur API keys check karo.\n\nError: ${e.message ?: "Unknown error"}")
                persist()
            } finally { busy = false }
        }
    }

    fun setProvider(value: String) { provider = value; prefs.edit().putString(PROVIDER, value).apply() }
    fun setAutoSpeak(value: Boolean) { autoSpeak = value; prefs.edit().putBoolean(AUTO_SPEAK, value).apply() }
    fun setLanguage(value: String) { language = value; prefs.edit().putString(LANGUAGE, value).apply() }
    fun setDarkTheme(value: Boolean) { darkTheme = value; prefs.edit().putBoolean(DARK_THEME, value).apply() }
    fun clearChat() {
        messages = listOf(welcomeMessage())
        persist()
    }

    private fun checkBackend() = viewModelScope.launch { backendOnline = api.health() }

    private fun toApi(m: Msg) = ApiMessage(if (m.role == "assistant") "assistant" else "user", m.text)

    private fun welcomeMessage() = Msg("assistant", "Hi jaan ❤️ Main Vidhi hoon. Hindi, Hinglish ya English—jis mein tum comfortable ho, usmein baat karte hain.")

    private fun loadMessages(): List<Msg> {
        val raw = prefs.getString(HISTORY, null) ?: return listOf(welcomeMessage())
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val role = item.optString("role")
                    val text = item.optString("text")
                    if ((role == "user" || role == "assistant") && text.isNotBlank()) add(Msg(role, text))
                }
            }.takeLast(60).ifEmpty { listOf(welcomeMessage()) }
        }.getOrElse { listOf(welcomeMessage()) }
    }

    private fun persist() {
        val arr = JSONArray()
        messages.takeLast(60).forEach { arr.put(JSONObject().put("role", it.role).put("text", it.text)) }
        prefs.edit().putString(HISTORY, arr.toString()).apply()
    }
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = Locale("hi", "IN")
        }
        setContent { VidhiRoot(onSpeak = ::speak, onStopSpeaking = { tts?.stop() }) }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vidhi")
    }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }
}

@Composable
fun VidhiRoot(onSpeak: (String) -> Unit, onStopSpeaking: () -> Unit, vm: VidhiViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    Theme(dark = vm.darkTheme) {
        when (screen) {
            Screen.CHAT -> VidhiChat(vm, onSpeak, onStopSpeaking) { screen = Screen.SETTINGS }
            Screen.SETTINGS -> VidhiSettings(vm) { screen = Screen.CHAT }
        }
    }
}

@Composable
private fun VidhiChat(vm: VidhiViewModel, onSpeak: (String) -> Unit, onStopSpeaking: () -> Unit, openSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = it }
    }

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) list.animateScrollToItem(vm.messages.lastIndex)
    }
    LaunchedEffect(vm.messages.lastOrNull()?.text, vm.autoSpeak) {
        val last = vm.messages.lastOrNull()
        if (vm.autoSpeak && last?.role == "assistant" && last.text != "Hi jaan ❤️ Main Vidhi hoon. Hindi, Hinglish ya English—jis mein tum comfortable ho, usmein baat karte hain.") {
            onSpeak(last.text)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar()
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Vidhi", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        val status = when (vm.backendOnline) { true -> "Online • ${vm.lastProvider.ifBlank { vm.provider }}"; false -> "Backend offline"; null -> "Checking backend…" }
                        Text(status, color = MaterialTheme.colorScheme.secondary)
                    }
                    IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI brain", color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = {
                        vm.setProvider(when (vm.provider) { "auto" -> "openai"; "openai" -> "gemini"; else -> "auto" })
                    }, label = { Text(vm.provider.uppercase()) })
                }
            }
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Vidhi…") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    enabled = !vm.busy
                )
                IconButton(onClick = {
                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (vm.language == "hindi") "hi-IN" else if (vm.language == "english") "en-IN" else "hi-IN")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Vidhi ko bolo…")
                    })
                }, enabled = !vm.busy) { Icon(Icons.Default.Mic, "Voice input") }
                IconButton(onClick = onStopSpeaking) { Icon(Icons.Default.Stop, "Stop voice") }
                IconButton(onClick = { val x = input; input = ""; vm.send(x) }, enabled = input.isNotBlank() && !vm.busy) {
                    Icon(Icons.Default.Send, "Send")
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            itemsIndexed(vm.messages) { _, m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.role == "assistant") Arrangement.Start else Arrangement.End) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (m.role == "assistant") MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 290.dp))
                            if (m.role == "assistant") {
                                IconButton(onClick = { onSpeak(m.text) }) { Icon(Icons.Default.VolumeUp, "Speak") }
                            }
                        }
                    }
                }
            }
            if (vm.busy) {
                item { Text("Vidhi is thinking…", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun VidhiSettings(vm: VidhiViewModel, goBack: () -> Unit) {
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = goBack) { Icon(Icons.Default.ArrowBack, "Back") }
                Text("Vidhi Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("AI brain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto", "openai", "gemini").forEach { value ->
                    FilterChip(selected = vm.provider == value, onClick = { vm.setProvider(value) }, label = { Text(value.uppercase()) })
                }
            }

            Text("Language / भाषा", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("hinglish" to "Hinglish 🇮🇳", "hindi" to "Hindi 🇮🇳", "english" to "English 🇬🇧").forEach { (key, label) ->
                    FilterChip(selected = vm.language == key, onClick = { vm.setLanguage(key) }, label = { Text(label) })
                }
            }

            SettingSwitch("Auto speak AI replies", vm.autoSpeak) { vm.setAutoSpeak(it) }
            SettingSwitch("Dark appearance", vm.darkTheme) { vm.setDarkTheme(it) }

            Divider()
            Text("Conversation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = vm::clearChat, modifier = Modifier.fillMaxWidth()) { Text("Clear conversation memory") }
            Text("Hinglish mode: Vidhi replies naturally in Roman Hindi + English (for example: 'Aaj weather kaafi nice hai'). Hindi mode can use Devanagari. English mode stays in English.", style = MaterialTheme.typography.bodySmall)
            Text("Vidhi keeps recent conversation locally on this device. Only the recent chat sent to the backend is used for an AI reply.", style = MaterialTheme.typography.bodySmall)

            Divider()
            Text("Backend status: ${if (vm.backendOnline == true) "Online" else if (vm.backendOnline == false) "Offline / not configured" else "Checking…"}")
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Avatar() {
    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
        Text("V", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFFFF72AE), secondary = Color(0xFFFFB7D5), background = Color(0xFF120A13), surface = Color(0xFF241225))
        else lightColorScheme(primary = Color(0xFFB72E69), secondary = Color(0xFF8B3158), background = Color(0xFFFFF8FB), surface = Color(0xFFFFF0F6)),
        content = content
    )
}
