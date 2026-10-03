package app.offlineresearch.rag

import app.offlineresearch.engine.EngineMetrics
import app.offlineresearch.engine.GenerationRequest
import app.offlineresearch.engine.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** What the pipeline is doing right now; shown to the user. */
enum class RagStage { PLANNING, SEARCHING, THINKING, ANSWERING }

sealed interface RagEvent {
    data class Stage(val stage: RagStage) : RagEvent

    /**
     * The best match of the first search, shortened. It is shown at once, as
     * source text and not as the answer, while the model is still working.
     */
    data class Preview(val passage: Passage) : RagEvent

    /** The numbered sources the answer may cite; `sources[0]` is source 1. */
    data class Sources(val sources: List<Passage>) : RagEvent

    /** A fragment of the answer. */
    data class Token(val text: String) : RagEvent

    data class Finished(val report: RagReport) : RagEvent
}

/** What happened while answering one question; goes into the metrics log. */
data class RagReport(
    val queries: List<String>,
    val plannerFallback: Boolean,
    val planMs: Long,
    val searchMs: Long,
    val retrieved: Int,
    val sources: Int,
    /** Null when no model ran because nothing was retrieved. */
    val answerer: EngineMetrics?,
)

/** How the answerer samples; comes from the model profile. */
data class AnswerSettings(
    val promptSuffix: String,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val presencePenalty: Float,
)

/**
 * question -> first search (shown at once) -> planner -> more searches ->
 * source selection -> answerer.
 * Knows nothing about llama.cpp or SQLite; it works through the interfaces.
 */
class RagPipeline(
    private val planner: QueryPlanner,
    private val retriever: Retriever,
    private val selector: SourceSelector,
    private val answerer: InferenceEngine,
    private val settings: AnswerSettings,
    private val seed: () -> Int,
    /** Makes the excerpt shown as soon as the first search returns; null shows none. */
    private val preview: ((question: String, ranked: List<Passage>) -> Passage?)? = null,
) {
    fun answer(question: String): Flow<RagEvent> = flow {
        // Search with the question itself first: it needs no model, so something
        // useful can be on screen within about a second.
        emit(RagEvent.Stage(RagStage.SEARCHING))
        val firstStart = System.currentTimeMillis()
        val first = withContext(Dispatchers.IO) { retriever.withLeads(question, retriever.search(question)) }
        var searchMs = System.currentTimeMillis() - firstStart
        preview?.invoke(question, first)?.let { emit(RagEvent.Preview(it)) }

        emit(RagEvent.Stage(RagStage.PLANNING))
        val planStart = System.currentTimeMillis()
        val plan = planner.plan(question)
        val planMs = System.currentTimeMillis() - planStart

        // The planner's queries add to the question's own search; they cannot replace it.
        val queries = (listOf(question) + plan.queries).distinctBy { it.trim().lowercase() }
        val extra = queries.drop(1)
        if (extra.isNotEmpty()) emit(RagEvent.Stage(RagStage.SEARCHING))
        val secondStart = System.currentTimeMillis()
        val (retrieved, sources) = withContext(Dispatchers.IO) {
            val ranked = retriever.withLeads(question, interleave(listOf(first) + extra.map { retriever.search(it) }))
            ranked.size to selector.select(question, ranked)
        }
        searchMs += System.currentTimeMillis() - secondStart
        emit(RagEvent.Sources(sources))

        if (sources.isEmpty()) {
            // Nothing to ground an answer on: say so without asking the model.
            emit(RagEvent.Token(PromptBuilder.NOT_COVERED))
            emit(RagEvent.Finished(RagReport(queries, plan.usedFallback, planMs, searchMs, retrieved, 0, null)))
            return@flow
        }

        emit(RagEvent.Stage(RagStage.THINKING))
        var answering = false
        answerer.generate(
            GenerationRequest(
                systemPrompt = PromptBuilder.system(settings.promptSuffix),
                userPrompt = PromptBuilder.user(question, sources),
                maxTokens = settings.maxTokens,
                temperature = settings.temperature,
                topK = settings.topK,
                topP = settings.topP,
                presencePenalty = settings.presencePenalty,
                seed = seed(),
            ),
        ).collect { fragment ->
            if (!answering) {
                answering = true
                emit(RagEvent.Stage(RagStage.ANSWERING))
            }
            emit(RagEvent.Token(fragment))
        }
        emit(
            RagEvent.Finished(
                RagReport(queries, plan.usedFallback, planMs, searchMs, retrieved, sources.size, answerer.metrics()),
            ),
        )
    }
}
