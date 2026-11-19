package app.talknlearn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private val Bar = Color(0xFF6599FD)
private val GoodGreen = Color(0xFF2E7D32)
private val CardShape = RoundedCornerShape(16.dp)
private val DAYS_DE = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
private val DAYS_EN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun HomeScreen(stats: Stats, hasTurns: Boolean, topic: String, openChat: () -> Unit, startChat: (String) -> Unit) {
    val greeting = when (LocalTime.now().hour) {
        in 0..10 -> "Guten Morgen"
        in 11..17 -> "Guten Tag"
        else -> "Guten Abend"
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$greeting, Ajit", color = Ink, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                Text("A2 → B1 · Speaking track", color = Blue, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Text(
                "🔥 ${stats.streak}",
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color.White, CircleShape)
                    .border(1.dp, Line, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics { contentDescription = "${stats.streak}-day streak" },
            )
        }

        Column(
            Modifier.fillMaxWidth().background(Navy, RoundedCornerShape(24.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Today's speaking goal", color = Border, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${stats.todaySec / 60} / $DAILY_GOAL_MIN min", color = Ground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { (stats.todaySec / (DAILY_GOAL_MIN * 60f)).coerceAtMost(1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Ground,
                trackColor = Blue,
            )
            Text(
                if (hasTurns) "Continue: $topic" else "A random everyday situation",
                color = Ground,
                fontSize = 18.sp,
                lineHeight = 25.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Button(
                onClick = { if (hasTurns) openChat() else startChat(TOPICS.random()) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Ground, contentColor = Navy),
            ) {
                Icon(painterResource(R.drawable.ic_mic), null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Start speaking", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Practice a situation", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            TOPICS.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t ->
                        Text(
                            t.replaceFirstChar { it.uppercase() },
                            color = Ink,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(Color.White, CardShape)
                                .border(1.dp, Line, CardShape)
                                .clickable { startChat(t) }
                                .padding(14.dp),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().background(Color.White, CardShape).border(1.dp, Line, CardShape).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).background(TipBg, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_hint), null, Modifier.size(22.dp), tint = TipInk)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tipp des Tages", color = TipInk, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(tipOfDay(LocalDate.now()), color = Ink, fontSize = 14.sp, lineHeight = 21.sp)
            }
        }
    }
}

@Composable
fun ProgressScreen(stats: Stats, startChat: (String) -> Unit, practise: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Dein Fortschritt", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${stats.weekSec.sum() / 60}", "min spoken this week", Modifier.weight(1f))
            StatTile("${stats.streak}", "day streak", Modifier.weight(1f))
            StatTile("${stats.words}", "words used aloud", Modifier.weight(1f))
        }

        Column(
            Modifier.fillMaxWidth().background(Color.White, CardShape).border(1.dp, Line, CardShape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Minutes spoken", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("Goal $DAILY_GOAL_MIN min / day", color = Navy, fontSize = 12.sp)
            }
            val mins = stats.weekSec.map { it / 60 }
            val maxMin = maxOf(DAILY_GOAL_MIN, mins.max())
            val today = LocalDate.now().dayOfWeek.ordinal // Monday = 0, same order as weekSec
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    contentDescription = "Minutes spoken per day: " + DAYS_EN.zip(mins).joinToString { (d, m) -> "$d $m" }
                },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    mins.forEachIndexed { i, m ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height((120f * m / maxMin).coerceAtLeast(3f).dp)
                                .background(if (i == today) Blue else Bar, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DAYS_DE.forEachIndexed { i, d ->
                        Text(
                            d,
                            Modifier.weight(1f),
                            color = if (i == today) Ink else Navy,
                            fontSize = 11.sp,
                            fontWeight = if (i == today) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Text("Mistakes to work on", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().background(Color.White, CardShape).border(1.dp, Line, CardShape)) {
            if (stats.mistakes.isEmpty()) {
                Text("No mistakes tracked yet. Talk to Lena and they will show up here.", color = Navy, fontSize = 14.sp, modifier = Modifier.padding(14.dp))
            }
            stats.mistakes.forEachIndexed { i, (label, n) ->
                if (i > 0) HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("$n times", color = Navy, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { startChat("practising: $label — build the conversation so Ajit has to use this several times") },
                        shape = CircleShape,
                        border = BorderStroke(1.dp, Border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue),
                    ) { Text("Drill", fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Text("Pronunciation", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Column(
            Modifier.fillMaxWidth().background(Color.White, CardShape).border(1.dp, Line, CardShape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (stats.sounds.isEmpty()) {
                Text("No words yet. When Lena notices a word that may have been mispronounced, it appears here.", color = Navy, fontSize = 14.sp)
            } else {
                val more = stats.sounds.size - 10
                Text(
                    stats.sounds.take(10).joinToString(" · ") + if (more > 0) " · +$more more" else "",
                    color = Ink,
                    fontSize = 14.sp,
                )
                Button(onClick = practise, modifier = Modifier.fillMaxWidth()) { Text(if (stats.sounds.size == 1) "Practise 1 word" else "Practise ${stats.sounds.size} words") }
            }
        }
    }
}

@Composable
fun SoundsScreen(words: List<String>, speak: (String, Boolean) -> Unit, onCorrect: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    val i = index.coerceAtMost(words.lastIndex) // the list shrinks when a word is solved, so clamp
    val current = words.getOrNull(i)
    val currentNow = rememberUpdatedState(current)
    val onCorrectNow = rememberUpdatedState(onCorrect)
    var listening by remember { mutableStateOf(false) }
    var solved by remember { mutableStateOf(false) } // true for the second after a correct answer
    var status by remember { mutableStateOf("") }

    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null }
    val listener = remember {
        object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                listening = false
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val word = currentNow.value ?: return
                if (saidCorrectly(word, heard)) {
                    status = "✓ Richtig!"
                    solved = true
                    scope.launch {
                        delay(1000)
                        onCorrectNow.value(word) // the next word moves into this slot
                        solved = false
                        status = ""
                    }
                } else {
                    status = heard.firstOrNull()?.let { "Heard: \"$it\" – try again" } ?: "Didn't catch that – try again"
                }
            }

            override fun onError(error: Int) {
                listening = false
                status = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that – try again"
                    ERROR_LANGUAGE_UNAVAILABLE -> { offlineSpeech = false; "Switched to online recognition – tap the mic again" }
                    else -> "Speech recognition error ($error)"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
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

    fun startListening() {
        val rec = recognizer
        if (rec == null) {
            status = "Speech recognition is not available on this phone."
            return
        }
        status = ""
        listening = true
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineSpeech)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        )
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else status = "Microphone permission is needed to say the word."
    }
    fun onMicTap() {
        when {
            listening -> {
                recognizer?.cancel()
                listening = false
                status = ""
            }
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startListening()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ground).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back", Modifier.size(22.dp), tint = Navy) }
            Text("Pronunciation", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (current == null) {
            Spacer(Modifier.weight(1f))
            Text("All words practised! 🎉", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Button(onClick = onBack) { Text("Back to progress") }
            Spacer(Modifier.weight(1f))
        } else {
            Text("${i + 1} / ${words.size}", color = Navy, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(current, color = Ink, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            OutlinedButton(
                onClick = { speak(current, false) },
                shape = CircleShape,
                border = BorderStroke(1.dp, Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue),
                modifier = Modifier.semantics { contentDescription = "Listen to the word" },
            ) { Text("🔊 Listen", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            MicButton(listening, enabled = !solved, onClick = { onMicTap() })
            Text("Say it", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                status,
                color = if (solved) GoodGreen else Ink,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    recognizer?.cancel()
                    listening = false
                    status = ""
                    index = (i + 1) % words.size
                },
                enabled = !solved,
            ) { Text("Skip") }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Navy, fontSize = 12.sp)
    }
}
