package app.offlineresearch.rag

import app.offlineresearch.engine.GenerationRequest
import app.offlineresearch.engine.InferenceEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList

/** Turns a question into search queries. */
interface QueryPlanner {
    /** One to [MAX_QUERIES] queries. Never empty. */
    suspend fun plan(question: String): Plan

    companion object {
        const val MAX_QUERIES = 3
    }
}

data class Plan(val queries: List<String>, val usedFallback: Boolean)

/**
 * The fallback: search with the question itself. The retriever already does
 * keyword extraction (function words and question filler are dropped).
 */
object KeywordPlanner : QueryPlanner {
    override suspend fun plan(question: String) = Plan(listOf(question), usedFallback = true)
}

/** Asks a small language model for search queries; falls back to [KeywordPlanner] on unusable output. */
class LlmPlanner(
    private val engine: InferenceEngine,
    private val promptSuffix: String,
    /** Text the model's reply is made to start with; see ModelProfile.assistantPrefix. */
    private val assistantPrefix: String = "",
) : QueryPlanner {

    override suspend fun plan(question: String): Plan {
        val raw = try {
            engine.generate(
                GenerationRequest(
                    systemPrompt = if (promptSuffix.isBlank()) SYSTEM else "$SYSTEM\n\n${promptSuffix.trim()}",
                    userPrompt = "Question: ${question.trim()}",
                    maxTokens = MAX_TOKENS,
                    temperature = 0f, // greedy: the same question always gets the same searches
                    topK = 1,
                    topP = 1f,
                    presencePenalty = 0f,
                    seed = 0,
                    assistantPrefix = assistantPrefix,
                ),
            ).toList().joinToString("")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return KeywordPlanner.plan(question)
        }
        val queries = PlannerOutput.parse(raw)
        return if (queries.isEmpty()) KeywordPlanner.plan(question) else Plan(queries, usedFallback = false)
    }

    companion object {
        const val MAX_TOKENS = 64

        val SYSTEM = """
            You choose encyclopedia articles to look up. The encyclopedia is Wikipedia plus the Wikivoyage travel guide.
            Given a question, reply with the titles of 1 to 3 articles most likely to contain the answer, one per line.
            Use the names an encyclopedia would use for the topic, not the words of the question.
            No numbering, no quotes, no explanations.

            Example
            Question: How do I recognise a heart attack?
            Myocardial infarction
            Chest pain

            Example
            Question: How can I keep food cold without electricity?
            Icebox
            Evaporative cooler
            Food preservation
        """.trimIndent()
    }
}

object PlannerOutput {
    private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val LEADING_MARK = Regex("^\\s*(?:[-*\\u2022]+|\\d+[.)]|(?i:query\\s*\\d*\\s*:))\\s*")
    private const val MAX_WORDS = 12

    /**
     * Search queries found in the planner's raw output, at most
     * [QueryPlanner.MAX_QUERIES]. Empty when the output is unusable: nothing
     * but reasoning, prose instead of queries, or no searchable words.
     */
    fun parse(raw: String): List<String> {
        val text = THINK.replace(raw, "").substringBefore("<think>")
        return text.lineSequence()
            .map { LEADING_MARK.replace(it, "").trim().trim('"', '\'', '`').trim() }
            .filter { it.isNotEmpty() }
            // A line that long is an explanation, not a query.
            .filter { tokenize(it).size <= MAX_WORDS }
            .filter { QueryBuilder.queryTerms(it).isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(QueryPlanner.MAX_QUERIES)
            .toList()
    }
}
