package app.talknlearn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgressTest {
    private val today = LocalDate.of(2026, 11, 19)

    @Test
    fun streakCountsTodayAndEarlierDays() {
        assertEquals(3, streak(setOf(today, today.minusDays(1), today.minusDays(2)), today))
    }

    @Test
    fun streakCountsFromYesterdayWhenTodayIsNotDoneYet() {
        assertEquals(2, streak(setOf(today.minusDays(1), today.minusDays(2)), today))
    }

    @Test
    fun gapBreaksStreak() {
        assertEquals(1, streak(setOf(today, today.minusDays(2)), today))
    }

    @Test
    fun noSpeakingIsZero() {
        assertEquals(0, streak(emptySet(), today))
    }

    @Test
    fun wordsKeepUmlautsLowercaseAndSplitPunctuation() {
        assertEquals(setOf("ich", "muss", "ja", "für", "müßig"), wordsOf("Ich muss, ja! Für Müßig? a"))
    }

    @Test
    fun tipOfDayNeverThrowsForAnyDayOfTheYear() {
        val start = LocalDate.of(2026, 1, 1)
        repeat(365) { assertTrue(tipOfDay(start.plusDays(it.toLong())) in TIPS) }
    }

    @Test
    fun saidCorrectlyMatchesWholeWordAnywhereInTheSentence() {
        assertTrue(saidCorrectly("Küche", listOf("Küche")))
        assertTrue(saidCorrectly("Küche", listOf("ich koche in der Küche")))
    }

    @Test
    fun saidCorrectlyIgnoresCaseAndPunctuation() {
        assertTrue(saidCorrectly("Küche", listOf("küche.")))
        assertTrue(saidCorrectly("küche", listOf("KÜCHE!")))
    }

    @Test
    fun saidCorrectlyRejectsWrongWordAndPartialWord() {
        assertFalse(saidCorrectly("Küche", listOf("Kuchen")))
        assertFalse(saidCorrectly("Küche", listOf("Küchen")))
    }

    @Test
    fun saidCorrectlyMatchesMultiWordPhrase() {
        assertTrue(saidCorrectly("guten Tag", listOf("Guten Tag, Herr Müller")))
        assertFalse(saidCorrectly("guten Tag", listOf("Guten Morgen")))
    }

    @Test
    fun saidCorrectlyChecksEveryAlternative() {
        assertTrue(saidCorrectly("Küche", listOf("Kuchen", "in der Küche", "Kuche")))
        assertFalse(saidCorrectly("Küche", emptyList()))
    }
}
