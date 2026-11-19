package app.talknlearn

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.time.LocalDate
import java.util.Locale

// shortcut: key is stored unencrypted in app-private storage, move to the Android Keystore before sharing the app
internal val Context.settings by preferencesDataStore("settings")
private val API_KEY = stringPreferencesKey("api_key")
private val MODEL = stringPreferencesKey("model")
private val PROVIDER = stringPreferencesKey("provider") // "anthropic" (default) or "azure"
private val AZURE_ENDPOINT = stringPreferencesKey("azure_endpoint")
private val AZURE_KEY = stringPreferencesKey("azure_key")
private val AZURE_DEPLOYMENT = stringPreferencesKey("azure_deployment")
private val PROVIDERS = listOf(
    "anthropic" to "Anthropic (Claude API key)",
    "azure" to "Azure AI Foundry (any deployed model)",
)

private data class Prefs(
    val provider: String,
    val apiKey: String,
    val model: String,
    val endpoint: String,
    val azureKey: String,
    val deployment: String,
)

// Picks the API for the saved provider. Azure deployments named claude* go through Foundry's Claude endpoint.
private fun askerFor(s: Prefs): (List<Turn>, String) -> Turn {
    if (s.provider == "azure" && s.deployment.startsWith("claude", ignoreCase = true)) {
        // Foundry's Claude endpoint accepts the Azure key as x-api-key
        val client = AnthropicOkHttpClient.builder().baseUrl(s.endpoint.trimEnd('/') + "/anthropic").apiKey(s.azureKey).build()
        return { h, t -> askLena(client, h, t, s.deployment) }
    }
    if (s.provider == "azure") return { h, t -> askAzureChat(s.endpoint, s.azureKey, s.deployment, h, t) }
    val client = AnthropicOkHttpClient.builder().apiKey(s.apiKey).build()
    return { h, t -> askLena(client, h, t, s.model) }
}

internal val Ink = Color(0xFF011D41)
internal val Navy = Color(0xFF03346B)
internal val Blue = Color(0xFF0C64C5)
internal val Ground = Color(0xFFF0F3FF)
internal val Line = Color(0xFFE4EBFF)
internal val Border = Color(0xFFC9D7FE)
private val Dash = Color(0xFF8EAFFE)
internal val TipBg = Color(0xFFFFF4E5)
internal val TipInk = Color(0xFF9A3412)

private val TABS = listOf(
    Triple("home", "Home", R.drawable.ic_home),
    Triple("chat", "Speak", R.drawable.ic_mic),
    Triple("progress", "Progress", R.drawable.ic_progress),
    Triple("settings", "Settings", R.drawable.ic_settings),
)

private const val NO_SPEECH = "This phone has no speech recognition. Type instead, or install Google's speech services."
private const val DEFAULT_HINT = "Hallo Lena! Ich bin Ajit. Wie geht es dir?"

// Prefer on-device speech recognition; switched off for this run once the engine says the German offline pack is missing.
internal var offlineSpeech = true
internal const val ERROR_LANGUAGE_UNAVAILABLE = 13 // SpeechRecognizer constant from API 31; minSdk is 26

// internal (not private) so the unit test can call it
internal fun isStopCommand(text: String) = text.trim().trimEnd('.', '!').lowercase() in setOf("stopp", "stop")

private fun Bundle?.firstResult(): String = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private val replyDone = mutableIntStateOf(0) // bumped when Lena's reply has finished speaking

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.apply { language = Locale.GERMANY; setSpeechRate(0.85f) }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "reply") runOnUiThread { replyDone.intValue++ }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Ground, onBackground = Ink)) {
                App(
                    speak = { text, isReply -> tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, if (isReply) "reply" else "replay") },
                    replyDone = replyDone.intValue,
                )
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun App(speak: (String, Boolean) -> Unit, replyDone: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by remember {
        context.settings.data.map { p ->
            Prefs(
                provider = p[PROVIDER] ?: "anthropic",
                apiKey = p[API_KEY] ?: "",
                model = p[MODEL] ?: DEFAULT_MODEL,
                endpoint = p[AZURE_ENDPOINT] ?: "",
                azureKey = p[AZURE_KEY] ?: "",
                deployment = p[AZURE_DEPLOYMENT] ?: "",
            )
        }
    }.collectAsState(initial = null)
    var screen by remember { mutableStateOf("home") } // home, chat, progress, settings
    val turns = remember { mutableStateListOf<Turn>() } // kept here so the chat survives switching screens
    var topic by remember { mutableStateOf(TOPICS.random()) }

    val stats by remember { context.settings.data.map { it.stats(LocalDate.now()) } }.collectAsState(initial = EMPTY_STATS)

    val saved = prefs ?: return // still loading
    val saveSettings: (Prefs) -> Unit = { s ->
        scope.launch {
            context.settings.edit {
                it[PROVIDER] = s.provider
                if (s.provider == "azure") {
                    it[AZURE_ENDPOINT] = s.endpoint
                    it[AZURE_KEY] = s.azureKey
                    it[AZURE_DEPLOYMENT] = s.deployment
                } else {
                    it[API_KEY] = s.apiKey
                }
            }
        }
        screen = "home"
    }
    val setModel: (String) -> Unit = { id -> scope.launch { context.settings.edit { it[MODEL] = id } } }
    val configured = if (saved.provider == "azure") {
        saved.endpoint.isNotBlank() && saved.azureKey.isNotBlank() && saved.deployment.isNotBlank()
    } else {
        saved.apiKey.isNotBlank()
    }
    if (!configured) {
        // no bottom bar until the selected provider is set up
        Box(Modifier.fillMaxSize().background(Ground).safeDrawingPadding()) { SettingsScreen(saved, saveSettings, setModel) }
        return
    }

    fun startChat(t: String) {
        topic = t
        turns.clear()
        screen = "chat"
    }
    BackHandler(enabled = screen != "home") { screen = if (screen == "sounds") "progress" else "home" }
    val ask = remember(saved) { askerFor(saved) }
    if (screen == "chat") {
        ChatScreen(ask, turns, topic, newTopic = { topic = TOPICS.random(); turns.clear() }, speak, replyDone, back = { screen = "home" })
    } else if (screen == "sounds") {
        SoundsScreen(stats.sounds, speak, onCorrect = { w -> scope.launch { context.removeSound(w) } }, onBack = { screen = "progress" })
    } else {
        // Pages above the bottom bar pad only top and sides: the bar pads the navigation-bar inset itself.
        val pageInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        Column(Modifier.fillMaxSize().background(Ground)) {
            Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(pageInsets)) {
                when (screen) {
                    "progress" -> ProgressScreen(stats, ::startChat, practise = { screen = "sounds" })
                    "settings" -> SettingsScreen(saved, saveSettings, setModel)
                    else -> HomeScreen(stats, turns.isNotEmpty(), topic, openChat = { screen = "chat" }, startChat = ::startChat)
                }
            }
            NavigationBar(containerColor = Color.White) {
                TABS.forEach { (id, label, icon) ->
                    NavigationBarItem(
                        selected = screen == id,
                        onClick = { screen = id },
                        icon = { Icon(painterResource(icon), null, Modifier.size(22.dp)) },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Line,
                            selectedIconColor = Blue,
                            selectedTextColor = Blue,
                            unselectedIconColor = Navy,
                            unselectedTextColor = Navy,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(ask: (List<Turn>, String) -> Turn, turns: SnapshotStateList<Turn>, topic: String, newTopic: () -> Unit, speak: (String, Boolean) -> Unit, replyDone: Int, back: () -> Unit) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var handsFree by remember { mutableStateOf(false) }
    var pocket by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") } // live recognizer text, not yet committed to input
    var showType by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val appScope = (context as ComponentActivity).lifecycleScope // outlives this screen, so tracking still saves on leave

    fun send(text: String) {
        if (text.isBlank() || busy) return
        val mine = Turn(fromLena = false, german = text.trim())
        turns += mine
        appScope.launch { context.addWords(text) }
        input = ""
        busy = true
        error = null
        val history = turns.toList()
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { ask(history, topic) }
                turns += reply
                if (reply.mistake.isNotBlank()) appScope.launch { context.addMistake(reply.mistake) }
                if (reply.sounds.isNotBlank()) appScope.launch { context.addSounds(reply.sounds) }
                speak(reply.german, true)
            } catch (e: Exception) {
                // Take the unanswered turn back out so the history stays user/assistant, and keep the text.
                turns.removeAt(turns.lastIndex)
                input = mine.german
                error = when (e) {
                    is UnauthorizedException -> "Your API key was rejected. Check it in Settings."
                    is RateLimitException -> "Too many requests. Wait a moment and try again."
                    is AnthropicIoException -> "No connection to Claude. Check your internet."
                    is java.io.IOException -> "No connection to Azure. Check your internet and endpoint."
                    else -> e.message ?: "Something went wrong."
                }
            } finally {
                busy = false
            }
        }
    }
    // The recognizer callbacks outlive this composition, so they call send through this to get the current topic and client.
    val sendNow = rememberUpdatedState(::send)

    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null }

    fun heard() = (input + " " + partial).trim()

    fun startListening() {
        val rec = recognizer
        if (rec == null) {
            error = NO_SPEECH
            handsFree = false
            return
        }
        partial = ""
        error = null
        listening = true
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineSpeech)
                // Hints only: many engines ignore them, so onResults restarts listening after a pause.
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening()
        else {
            error = "Microphone permission is needed to speak. You can still type."
            handsFree = false
        }
    }

    fun listenWithPermission() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onPartialResults(results: Bundle?) {
                partial = results.firstResult()
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstResult()
                partial = ""
                if (isStopCommand(text)) {
                    listening = false
                    handsFree = false
                    return
                }
                input = (input + " " + text).trim()
                if (listening) startListening() // a pause is not the end of the turn
            }

            override fun onError(code: Int) {
                if (code == ERROR_LANGUAGE_UNAVAILABLE && offlineSpeech) {
                    offlineSpeech = false
                    startListening() // German offline pack not downloaded: retry with online recognition
                    return
                }
                input = heard() // keep whatever was heard before the error
                partial = ""
                listening = false
                when (code) {
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> when {
                        !handsFree -> Unit // keep the text in input so the user can tap send
                        input.isNotBlank() -> sendNow.value(input)
                        else -> handsFree = false // silence in hands-free ends the chat
                    }
                    else -> {
                        error = "Speech recognition error ($code). Try again or type."
                        handsFree = false
                    }
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(listener)
        onDispose { recognizer?.destroy() }
    }

    // Mic time is saved when listening stops or the screen leaves while listening.
    DisposableEffect(listening) {
        val start = System.currentTimeMillis()
        val wasListening = listening
        onDispose {
            if (wasListening) appScope.launch { context.addSpeaking(((System.currentTimeMillis() - start) / 1000).toInt()) }
        }
    }

    // Lena's reply finished speaking: listen again if hands-free is on
    LaunchedEffect(replyDone) { if (handsFree && !busy) startListening() }

    LaunchedEffect(handsFree) { if (!handsFree) pocket = false }
    BackHandler(enabled = pocket) { pocket = false }
    val activity = context as Activity
    DisposableEffect(pocket) {
        if (pocket) activity.window.attributes = activity.window.attributes.apply { screenBrightness = 0.01f }
        onDispose { activity.window.attributes = activity.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
    }

    LaunchedEffect(turns.size) {
        showHint = false
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().background(Ground).safeDrawingPadding().then(if (handsFree) Modifier.keepScreenOn() else Modifier)) {
            Row(
                Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_back), "Back", Modifier.size(22.dp), tint = Navy) }
                Box(Modifier.size(40.dp).background(Navy, CircleShape), contentAlignment = Alignment.Center) {
                    Text("L", color = Ground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("Lena", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Today: $topic",
                        color = Blue,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = newTopic, enabled = !busy) {
                    Icon(painterResource(R.drawable.ic_refresh), "New topic", Modifier.size(22.dp), tint = Navy)
                }
            }
            HorizontalDivider(color = Line)

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (turns.isEmpty()) item { EmptyState(topic) }
                items(turns) { turn -> if (turn.fromLena) LenaTurn(turn, speak) else MyTurn(turn) }
            }

            if (showHint) {
                val hint = turns.lastOrNull { it.fromLena }?.hint?.takeIf { it.isNotBlank() } ?: DEFAULT_HINT
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .drawBehind {
                            drawRoundRect(
                                color = Dash,
                                cornerRadius = CornerRadius(14.dp.toPx()),
                                style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                            )
                        }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Stuck? Try:", color = Ink, fontSize = 13.sp)
                    Text(
                        "„$hint“",
                        color = Ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        modifier = Modifier.clickable { speak(hint, false) },
                    )
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            Column(Modifier.fillMaxWidth().background(Color.White)) {
                HorizontalDivider(color = Line)
                Column(
                    Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (showType) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                label = { Text("Or type in German") },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { send(input) }, enabled = input.isNotBlank() && !busy && !listening) { Text("Senden") }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            when {
                                busy -> "Lena is thinking…"
                                listening -> "Listening… pauses are OK. Tap to send."
                                else -> "Tap the mic and speak German"
                            },
                            color = Blue,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (listening && heard().isNotBlank()) {
                        Text(heard(), color = Ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = handsFree,
                            onClick = { handsFree = !handsFree; if (handsFree && !busy) listenWithPermission() },
                            label = { Text("🎧 Hands-free") },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Blue, selectedLabelColor = Color.White),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = handsFree, borderColor = Border),
                        )
                        if (handsFree) AssistChip(onClick = { pocket = true }, label = { Text("🌙 Pocket mode") })
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedIconButton(
                            onClick = { showType = !showType },
                            modifier = Modifier.size(52.dp),
                            border = BorderStroke(1.dp, Border),
                            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Ground, contentColor = Navy),
                        ) { Icon(painterResource(R.drawable.ic_keyboard), "Type instead", Modifier.size(22.dp)) }

                        MicButton(listening, enabled = !busy, onClick = {
                            if (listening) {
                                recognizer?.cancel() // cancel, not stop: no late onResults can append the same words again
                                listening = false
                                val text = heard()
                                partial = ""
                                send(text)
                            } else {
                                listenWithPermission()
                            }
                        })

                        OutlinedIconButton(
                            onClick = { showHint = !showHint },
                            modifier = Modifier.size(52.dp),
                            border = BorderStroke(1.dp, Border),
                            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Ground, contentColor = Navy),
                        ) { Icon(painterResource(R.drawable.ic_hint), "Get a hint", Modifier.size(22.dp)) }
                    }
                }
            }
        }
        if (pocket) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures(onDoubleTap = { pocket = false }) },
                contentAlignment = Alignment.Center,
            ) {
                Text("Pocket mode · double-tap to wake", color = Color(0xFF333333), fontSize = 14.sp)
            }
        }
    }
}

@Composable
internal fun MicButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "mic")
    val scale by pulse.animateFloat(1f, 1.25f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "scale")
    val ringAlpha by pulse.animateFloat(0.6f, 0f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "ringAlpha")
    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        if (listening) Box(Modifier.size(76.dp).scale(scale).alpha(ringAlpha).border(2.dp, Blue, CircleShape))
        OutlinedIconButton(
            onClick = onClick,
            modifier = Modifier.size(76.dp),
            enabled = enabled,
            border = BorderStroke(6.dp, Border),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = Blue, contentColor = Color.White),
        ) {
            Icon(painterResource(R.drawable.ic_mic), if (listening) "Stop and send" else "Speak", Modifier.size(30.dp))
        }
    }
}

@Composable
private fun EmptyState(topic: String) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().background(Color.White, shape).border(1.dp, Line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Today's situation: $topic.", color = Ink, fontWeight = FontWeight.SemiBold)
        Text("Tap the mic and speak German. Pauses are fine, tap again to send.", color = Ink)
        Text("Stuck? Tap 💡 for an example sentence.", color = Ink)
        Text("Hands-free sends after a long silence. Say \"Stopp\" to end.", color = Ink)
    }
}

@Composable
private fun MyTurn(turn: Turn) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            turn.german,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(Blue, RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                .padding(12.dp),
        )
    }
}

@Composable
private fun LenaTurn(turn: Turn, speak: (String, Boolean) -> Unit) {
    val shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Lena's feedback is about Ajit's previous message, so it sits above her reply.
        if (turn.fix.isNotBlank() || turn.tip.isNotBlank()) {
            Column(
                Modifier.fillMaxWidth().background(TipBg, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("SPEAKING TIP", color = TipInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                if (turn.fix.isNotBlank()) Text("Better: ${turn.fix}", color = Ink, fontWeight = FontWeight.SemiBold)
                if (turn.tip.isNotBlank()) Text(turn.tip, color = Ink)
            }
        }
        Column(
            Modifier.widthIn(max = 300.dp).background(Color.White, shape).border(1.dp, Line, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(turn.german, color = Ink, fontSize = 16.sp)
            if (turn.english.isNotBlank()) Text(turn.english, color = Navy, fontStyle = FontStyle.Italic, fontSize = 13.sp)
        }
        OutlinedButton(
            onClick = { speak(turn.german, false) },
            modifier = Modifier.height(32.dp),
            shape = CircleShape,
            border = BorderStroke(1.dp, Border),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        ) {
            Icon(painterResource(R.drawable.ic_replay), null, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Replay", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettingsScreen(current: Prefs, onSave: (Prefs) -> Unit, onModel: (String) -> Unit) {
    var provider by remember { mutableStateOf(current.provider) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var endpoint by remember { mutableStateOf(current.endpoint) }
    var azureKey by remember { mutableStateOf(current.azureKey) }
    var deployment by remember { mutableStateOf(current.deployment) }
    val azure = provider == "azure"
    val ready = if (azure) endpoint.isNotBlank() && azureKey.isNotBlank() && deployment.isNotBlank() else apiKey.isNotBlank()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Ground).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Ink)
        Text("Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
        PROVIDERS.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = id == provider, role = Role.RadioButton, onClick = { provider = id }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = id == provider, onClick = null)
                Text(label, color = Ink)
            }
        }
        if (azure) {
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Azure endpoint") },
                placeholder = { Text("https://YOUR-RESOURCE.services.ai.azure.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = azureKey,
                onValueChange = { azureKey = it },
                label = { Text("Azure key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = deployment,
                onValueChange = { deployment = it },
                label = { Text("Deployment name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Use the name of any model you deployed in Azure AI Foundry. Names starting with claude use Azure's Claude endpoint.",
                color = Ink,
                fontSize = 13.sp,
            )
        } else {
            Text(
                "Talk-n-Learn needs an Anthropic API key (from console.anthropic.com). It is billed per use, separately from a Claude.ai subscription. The key stays on this phone.",
                color = Ink,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("Anthropic API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
            MODELS.forEach { (id, label) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = id == current.model, role = Role.RadioButton, onClick = { onModel(id) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = id == current.model, onClick = null)
                    Text(label, color = Ink)
                }
            }
            Text("Haiku is about 40× cheaper than Opus and good enough for A2–B1 practice.", color = Ink, fontSize = 13.sp)
        }
        Button(
            onClick = { onSave(Prefs(provider, apiKey.trim(), current.model, endpoint.trim(), azureKey.trim(), deployment.trim())) },
            enabled = ready,
        ) { Text("Save") }
    }
}
