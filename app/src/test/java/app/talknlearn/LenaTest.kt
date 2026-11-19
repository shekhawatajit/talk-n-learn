package app.talknlearn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LenaTest {
    @Test
    fun parsesReplyAndKeepsRawJsonForHistory() {
        val json = """{"reply":"Klar, Donnerstag passt.","english":"Sure, Thursday works.","fix":"Können wir das Meeting verschieben?","tip":"wir können, ich kann.","hint":"Um zehn Uhr, wenn das okay ist.","mistake":"modal verb position","sounds":"Küche, Bürgeramt"}"""
        val turn = parseLena(json)
        assertTrue(turn.fromLena)
        assertEquals("Klar, Donnerstag passt.", turn.german)
        assertEquals("Können wir das Meeting verschieben?", turn.fix)
        assertEquals("Um zehn Uhr, wenn das okay ist.", turn.hint)
        assertEquals("modal verb position", turn.mistake)
        assertEquals("Küche, Bürgeramt", turn.sounds)
        assertEquals(json, turn.raw)
    }

    @Test(expected = Exception::class)
    fun rejectsReplyWithoutGermanText() {
        parseLena("""{"english":"x","fix":"","tip":""}""")
    }

    @Test
    fun topicsAreNonEmptyAndUnique() {
        assertFalse(TOPICS.isEmpty())
        assertEquals(TOPICS.size, TOPICS.toSet().size)
    }

    @Test
    fun recognisesStopCommand() {
        assertTrue(isStopCommand("Stopp"))
        assertTrue(isStopCommand(" stop! "))
        assertFalse(isStopCommand("Stopp bitte nicht"))
        assertFalse(isStopCommand("Hallo"))
    }
}
