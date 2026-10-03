package app.offlineresearch.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One line from the engine process's standard output. */
sealed interface BmoeEvent {
    /** Sent once, when the model has loaded. */
    data class Ready(val loadSeconds: Double, val architecture: String, val contextSize: Int, val thinkControl: String) : BmoeEvent

    data class Begin(val id: Int) : BmoeEvent

    /** New answer text. With [reset], [deltaText] replaces everything received so far in this generation. */
    data class Progress(val deltaText: String, val reset: Boolean) : BmoeEvent

    data class Done(
        val id: Int,
        val cancelled: Boolean,
        val tokens: Int,
        val tokensPerSecond: Double,
        val prefillSeconds: Double,
        /** Prompt tokens processed in this turn. */
        val promptTokens: Int,
        val text: String,
    ) : BmoeEvent

    data class Error(val id: Int, val fatal: Boolean, val message: String) : BmoeEvent
}

/**
 * The line protocol of `bmoe-cli --session` (BigMoeOnEdge, docs/telemetry.md):
 * one JSON object per line in, `BMOE_<TAG> {json}` lines out. Kept apart from
 * the process handling so it can be tested on the PC.
 */
object BmoeProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun generate(id: Int, prompt: String, maxTokens: Int, think: Boolean): String = buildJsonObject {
        put("cmd", "generate")
        put("id", id)
        put("prompt", prompt)
        put("n_predict", maxTokens)
        put("think", think)
        // Every question is independent: the sources change each time, so there is no conversation to continue.
        put("clear_kv", true)
    }.toString()

    const val CANCEL = """{"cmd":"cancel"}"""
    const val CLOSE = """{"cmd":"close"}"""

    /** Null for lines that are not part of the protocol, or that this app does not use. */
    fun parse(line: String): BmoeEvent? {
        if (!line.startsWith("BMOE_")) return null
        val space = line.indexOf(' ')
        if (space < 0) return null
        val body = try {
            json.parseToJsonElement(line.substring(space + 1)).jsonObject
        } catch (e: Exception) {
            return null
        }
        return try {
            when (line.substring(0, space)) {
                "BMOE_READY" -> BmoeEvent.Ready(
                    loadSeconds = body.double("load_s"),
                    architecture = body.string("arch"),
                    contextSize = body.int("n_ctx"),
                    thinkControl = body.string("think_ctl"),
                )
                "BMOE_BEGIN" -> BmoeEvent.Begin(body.int("id"))
                "BMOE_PROGRESS" -> {
                    val reset = body["reset"]?.jsonPrimitive?.intOrNull == 1
                    val delta = body.string("delta_text")
                    if (delta.isEmpty() && !reset) null else BmoeEvent.Progress(delta, reset)
                }
                "BMOE_DONE" -> BmoeEvent.Done(
                    id = body.int("id"),
                    cancelled = body["cancelled"]?.jsonPrimitive?.booleanOrNull ?: false,
                    tokens = body.int("tokens"),
                    tokensPerSecond = body.double("tok_s"),
                    prefillSeconds = body.double("prefill_s"),
                    promptTokens = body.int("n_prompt"),
                    text = body.string("text"),
                )
                "BMOE_ERROR" -> BmoeEvent.Error(
                    id = body.int("id"),
                    fatal = body["fatal"]?.jsonPrimitive?.booleanOrNull ?: true,
                    message = body.string("msg"),
                )
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0
    private fun JsonObject.double(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0
}
