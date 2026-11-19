package app.talknlearn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureTest {
    private val lenaJson = """{"reply":"Hallo!","english":"Hi!","fix":"","tip":"","hint":"Gut, danke.","mistake":"der/die/das","sounds":"Küche"}"""

    private fun response(content: String) = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content))))
        .toString()

    @Test
    fun bodyUsesDeploymentTopicHistoryAndJsonMode() {
        val history = listOf(Turn(fromLena = true, german = "Hallo!", raw = lenaJson), Turn(fromLena = false, german = "Hallo Lena"))
        val body = azureChatBody("gpt-4o-mini", history, "a visit to the doctor")
        assertEquals("gpt-4o-mini", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("a visit to the doctor"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals(lenaJson, messages.getJSONObject(1).getString("content"))
        assertEquals("user", messages.getJSONObject(2).getString("role"))
        assertEquals("Hallo Lena", messages.getJSONObject(2).getString("content"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun parsesOpenAiStyleResponse() {
        val turn = parseAzureChat(response(lenaJson))
        assertEquals("Hallo!", turn.german)
        assertEquals("Gut, danke.", turn.hint)
        assertEquals("der/die/das", turn.mistake)
        assertEquals("Küche", turn.sounds)
    }

    @Test
    fun parsesResponseWrappedInJsonFence() {
        val turn = parseAzureChat(response("```json\n$lenaJson\n```"))
        assertEquals("Hallo!", turn.german)
        assertEquals("der/die/das", turn.mistake)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsNonJsonContent() {
        parseAzureChat(response("Sorry, I can't do that."))
    }
}
