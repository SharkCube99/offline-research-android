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
 * question -> planner -> retriever -> context budgeter -> answerer.
 * Knows nothing about llama.cpp or SQLite; it works through the interfaces.
 */
class RagPipeline(
    private val planner: QueryPlanner,
    private val retriever: Retriever,
    private val budgeter: ContextBudgeter,
    private val answerer: InferenceEngine,
    private val settings: AnswerSettings,
    private val seed: () -> Int,
) {
    fun answer(question: String): Flow<RagEvent> = flow {
        emit(RagEvent.Stage(RagStage.PLANNING))
        val planStart = System.currentTimeMillis()
        val plan = planner.plan(question)
        val planMs = System.currentTimeMillis() - planStart

        // The question itself is always searched; the planner's queries add to it.
        val queries = (listOf(question) + plan.queries).distinctBy { it.trim().lowercase() }

        emit(RagEvent.Stage(RagStage.SEARCHING))
        val searchStart = System.currentTimeMillis()
        val (retrieved, sources) = withContext(Dispatchers.IO) {
            val ranked = interleave(queries.map { retriever.search(it) })
            ranked.size to budgeter.select(ranked)
        }
        val searchMs = System.currentTimeMillis() - searchStart
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
