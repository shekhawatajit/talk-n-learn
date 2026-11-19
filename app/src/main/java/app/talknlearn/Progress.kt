package app.talknlearn

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.time.DayOfWeek
import java.time.LocalDate

const val DAILY_GOAL_MIN = 10

/** Everything the home and progress screens show. [weekSec] is Mon..Sun of the current week. */
data class Stats(
    val todaySec: Int,
    val weekSec: List<Int>,
    val streak: Int,
    val words: Int,
    val mistakes: List<Pair<String, Int>>, // top 3 by count
    val sounds: List<String>, // words to practise, sorted
)

val EMPTY_STATS = Stats(0, List(7) { 0 }, 0, 0, emptyList(), emptyList())

private val WORDS = stringSetPreferencesKey("words")
private val SOUNDS = stringSetPreferencesKey("sounds")
private val NON_LETTERS = Regex("\\P{L}+") // same letters as Character.isLetter, so ä ö ü ß stay

val TIPS = listOf(
    "German w sounds like Hindi व (v): say Wasser as “वासर”, not “water”.",
    "ch after i, e, ä, ö, ü is a soft hiss, as in ich and nicht. After a, o, u it is a rougher sound from the throat, as in Bach.",
    "ü: hold the tongue for “ee” but round your lips as for “oo”. Practise für and müssen slowly.",
    "Verb second in main clauses: “Heute gehe ich ins Büro.” The verb is the second idea, even after Heute.",
    "After weil and dass the conjugated verb goes to the end: “Ich bleibe zu Hause, weil ich krank bin.”",
    "Learn every noun with its article: die Besprechung, der Chef, das Büro. The article is part of the word.",
    "German r is made at the back of the throat, not rolled with the tip of the tongue like Hindi र. Try Rad and rot.",
    "Final -d and -g are spoken as t and k: Hund sounds like “hunt”, Tag like “tak”.",
    "Sie (formal you) is like आप, du (informal) is like तुम. Use Sie with a doctor, a landlord or a boss at first.",
    "German z is ts, as in Zeit (“tsait”). German s before a vowel is z, as in Sonne.",
)

fun tipOfDay(today: LocalDate) = TIPS[today.dayOfYear % TIPS.size]

/** Days in a row with speaking, ending today; if today has none yet, ending yesterday. */
fun streak(spokenDays: Set<LocalDate>, today: LocalDate): Int {
    var day = if (today in spokenDays) today else today.minusDays(1)
    var count = 0
    while (day in spokenDays) {
        count++
        day = day.minusDays(1)
    }
    return count
}

fun wordsOf(text: String): Set<String> =
    text.lowercase().split(NON_LETTERS).filter { it.length >= 2 }.toSet()

suspend fun Context.addSpeaking(seconds: Int) {
    if (seconds <= 0) return
    val key = intPreferencesKey("sec_" + LocalDate.now())
    settings.edit { it[key] = (it[key] ?: 0) + seconds }
}

suspend fun Context.addWords(text: String) {
    val words = wordsOf(text)
    if (words.isEmpty()) return
    settings.edit { it[WORDS] = (it[WORDS] ?: emptySet()) + words }
}

suspend fun Context.addMistake(label: String) {
    val l = label.trim().lowercase()
    if (l.isEmpty()) return
    val key = intPreferencesKey("mistake:$l")
    settings.edit { it[key] = (it[key] ?: 0) + 1 }
}

suspend fun Context.addSounds(csv: String) {
    val words = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (words.isEmpty()) return
    settings.edit { it[SOUNDS] = (it[SOUNDS] ?: emptySet()) + words }
}

suspend fun Context.removeSound(word: String) {
    settings.edit { it[SOUNDS] = (it[SOUNDS] ?: emptySet()) - word }
}

/** True if any recognizer alternative has [target] as whole words, ignoring case and punctuation. */
fun saidCorrectly(target: String, heard: List<String>): Boolean {
    val t = normaliseWords(target)
    if (t.isEmpty()) return false
    return heard.any { " ${normaliseWords(it)} ".contains(" $t ") }
}

private fun normaliseWords(s: String) = s.lowercase().split(NON_LETTERS).filter { it.isNotEmpty() }.joinToString(" ")

fun Preferences.stats(today: LocalDate): Stats {
    val secByDay = asMap().mapNotNull { (k, v) ->
        if (k.name.startsWith("sec_") && v is Int && v > 0) LocalDate.parse(k.name.removePrefix("sec_")) to v else null
    }.toMap()
    val monday = today.with(DayOfWeek.MONDAY)
    return Stats(
        todaySec = secByDay[today] ?: 0,
        weekSec = List(7) { secByDay[monday.plusDays(it.toLong())] ?: 0 },
        streak = streak(secByDay.keys, today),
        words = (this[WORDS] ?: emptySet()).size,
        mistakes = asMap()
            .mapNotNull { (k, v) -> if (k.name.startsWith("mistake:") && v is Int) k.name.removePrefix("mistake:") to v else null }
            .sortedByDescending { it.second }
            .take(3),
        sounds = (this[SOUNDS] ?: emptySet()).sorted(),
    )
}
