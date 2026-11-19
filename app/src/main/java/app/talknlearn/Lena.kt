package app.talknlearn

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One line in the chat. For Lena, [raw] is the JSON she returned, sent back as-is in the history. */
data class Turn(
    val fromLena: Boolean,
    val german: String,
    val english: String = "",
    val fix: String = "",
    val tip: String = "",
    val hint: String = "",
    val mistake: String = "",
    val sounds: String = "",
    val raw: String = german,
)

val MODELS = listOf(
    "claude-haiku-5-5" to "Haiku 5.5 · cheapest (about \$0.01 per session)",
    "claude-sonnet-5-5" to "Sonnet 5.5 · middle",
    "claude-opus-5-5" to "Opus 5.5 · best, most expensive",
)
const val DEFAULT_MODEL = "claude-opus-5-5"

val TOPICS = listOf(
    "a team meeting at work",
    "a visit to the doctor",
    "calling a landlord about a flat",
    "an appointment at the Bürgeramt",
    "a delayed train at the station",
    "ordering at a restaurant",
    "shopping for clothes",
    "plans for the weekend",
    "hobbies and free time",
    "Indian food and festivals",
    "planning a trip",
    "small talk about the weather",
)

private val SYSTEM = """
You are Lena, a friendly native German speaker who helps Ajit practise speaking through role-play.
Ajit has finished A2 German and is practising speaking towards B1. Ajit is a native Hindi speaker who works in English.
Ajit's messages come from speech recognition, so ignore missing punctuation and capital letters.

Fill these fields:
- reply: your answer in short, natural spoken German at A2-B1 level (1-3 sentences). Keep the conversation going, usually with a question.
- english: an English translation of your reply.
- fix: Ajit's last message rewritten as correct, natural German, or "" if it was already correct.
- tip: one short speaking tip in English about Ajit's last message: the grammar rule behind the fix, a better word, or how to pronounce a word Ajit used. Compare with Hindi only when it really helps. "" if there is nothing useful to say.
- hint: one example of what Ajit could say next in reply to you, in simple German at A2-B1 level (one sentence).
- mistake: a 2-5 word English label for the main mistake in Ajit's last message, reusing the same label for the same kind of mistake (for example "verb position after weil", "der/die/das", "ü sound"), or "" if there was none.
- sounds: German words Ajit probably mispronounced, comma-separated, written correctly. Ajit's text comes from speech recognition, so a wrong word that does not fit the context (for example "Kuchen" when he clearly meant "Küche") usually means he mispronounced the intended word. "" if none.
""".trimIndent()

private val FIELDS = listOf("reply", "english", "fix", "tip", "hint", "mistake", "sounds")

private val SCHEMA = JsonOutputFormat.Schema.builder()
    .putAdditionalProperty("type", JsonValue.from("object"))
    .putAdditionalProperty("properties", JsonValue.from(FIELDS.associateWith { mapOf("type" to "string") }))
    .putAdditionalProperty("required", JsonValue.from(FIELDS))
    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
    .build()

fun systemFor(topic: String): String =
    SYSTEM + "\n\nToday's situation: $topic. Play the person Ajit would meet there (a colleague, doctor, landlord, waiter, friend…), and keep the conversation in this situation unless Ajit changes the topic."

/** Blocking network call: run it off the main thread. History must start with Ajit's turn. */
fun askLena(client: AnthropicClient, history: List<Turn>, topic: String, model: String): Turn {
    val params = MessageCreateParams.builder()
        .model(model)
        .maxTokens(16000L)
        .system(systemFor(topic))
        .outputConfig(
            OutputConfig.builder()
                .effort(OutputConfig.Effort.LOW) // chat replies: fast beats deep
                .format(JsonOutputFormat.builder().schema(SCHEMA).build())
                .build()
        )
        // caches the growing chat so each turn re-reads earlier turns at the cache price
        .cacheControl(CacheControlEphemeral.builder().build())
        .apply { history.forEach { if (it.fromLena) addAssistantMessage(it.raw) else addUserMessage(it.german) } }
        .build()

    val message = client.messages().create(params)
    if (message.stopReason().orElse(null) == StopReason.REFUSAL) error("Lena could not answer that. Try saying it differently.")
    return parseLena(message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(""))
}

fun parseLena(json: String): Turn {
    val o = JSONObject(json)
    return Turn(
        fromLena = true,
        german = o.getString("reply"),
        english = o.optString("english"),
        fix = o.optString("fix"),
        tip = o.optString("tip"),
        hint = o.optString("hint"),
        mistake = o.optString("mistake"),
        sounds = o.optString("sounds"),
        raw = json,
    )
}

/** Blocking network call to any model deployed in Azure AI Foundry (OpenAI-style chat endpoint). */
fun askAzureChat(endpoint: String, key: String, deployment: String, history: List<Turn>, topic: String): Turn {
    val conn = URL(endpoint.trimEnd('/') + "/openai/v1/chat/completions").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 90_000
        conn.doOutput = true
        conn.setRequestProperty("api-key", key)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(azureChatBody(deployment, history, topic).toString().toByteArray()) }
        val code = conn.responseCode
        if (code >= 400) {
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error(
                when (code) {
                    401, 403 -> "Azure rejected the key. Check it in Settings."
                    404 -> "Azure endpoint or deployment \"$deployment\" not found. Check them in Settings."
                    429 -> "Azure rate limit reached. Wait a moment and try again."
                    else -> "Azure error $code: " + body.take(200)
                }
            )
        }
        return parseAzureChat(conn.inputStream.bufferedReader().use { it.readText() })
    } finally {
        conn.disconnect()
    }
}

/** Request body for the chat call. Pure, so the unit test can check it. No max_tokens: some Azure models reject it. */
fun azureChatBody(deployment: String, history: List<Turn>, topic: String): JSONObject {
    val system = systemFor(topic) + "\n\nAnswer only with one JSON object with exactly these string fields: ${FIELDS.joinToString(", ")}."
    val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
    history.forEach {
        messages.put(
            if (it.fromLena) JSONObject().put("role", "assistant").put("content", it.raw)
            else JSONObject().put("role", "user").put("content", it.german)
        )
    }
    return JSONObject()
        .put("model", deployment)
        .put("messages", messages)
        .put("response_format", JSONObject().put("type", "json_object"))
}

/** Takes the first choice's text as Lena's JSON. Some models wrap it in a ```json fence, so strip that first. */
fun parseAzureChat(responseJson: String): Turn = try {
    val content = JSONObject(responseJson).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    parseLena(content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
} catch (e: JSONException) {
    throw IllegalStateException("This model did not answer in the expected format. Try another deployment.", e)
}
