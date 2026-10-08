package app.offlineresearch.engine

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The only surface the UI and (later) the retrieval pipeline see. The model,
 * its quantisation and any caching strategy live behind this interface.
 */
interface InferenceEngine {
    /** Loads a model, replacing any model already loaded. Throws [EngineException] on failure. */
    suspend fun load(modelPath: String, config: EngineConfig)

    /**
     * Streams the reply as text fragments. The flow completes when generation
     * stops for any reason; [metrics] then describes that run.
     */
    fun generate(request: GenerationRequest): Flow<String>

    /**
     * Reads the fixed start of every prompt into the model ahead of the first
     * question, so that question only has its own part left to read. Engines
     * that keep nothing between prompts do nothing.
     *
     * With a [saved] file the reading is done once: the engine writes what it
     * read there and restores it from there the next time. The caller names the
     * file after everything the content depends on (model, settings, prompt).
     */
    suspend fun prime(systemPrompt: String, saved: File? = null) {}

    /** Number of tokens [text] occupies in this model's context. Safe to call from any thread. */
    fun countTokens(text: String): Int

    /** Asks a running [generate] to stop. Safe to call from any thread at any time. */
    fun cancel()

    /** Load time of the current model and timings of the most recent [generate]. */
    fun metrics(): EngineMetrics

    /** CPU features and backend the native library is using. */
    fun systemInfo(): String

    suspend fun unload()
}

data class EngineConfig(
    val contextSize: Int,
    val batchSize: Int,
    /** Threads for generating tokens. 0 lets the engine choose from the number of cores. */
    val threads: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    /** Let llama.cpp keep a second, repacked copy of the weights in RAM. */
    val repack: Boolean,
    /** "auto" uses the template embedded in the model file. */
    val chatTemplate: String,
    val threadAffinity: ThreadAffinity = ThreadAffinity.NONE,
    /** Threads for processing prompts; 0 uses [threads]. */
    val batchThreads: Int = 0,
)

enum class ThreadAffinity {
    /** The operating system places the threads. */
    NONE,

    /** The threads are restricted to the fastest cores, as many cores as there are threads. */
    FASTEST,
}

data class GenerationRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val presencePenalty: Float,
    val seed: Int,
    /** Text the reply is made to start with, written after the assistant header; not part of the output. */
    val assistantPrefix: String = "",
)

enum class StopReason { EOS, MAX_TOKENS, CANCELLED, CONTEXT_FULL, ERROR, NONE }

data class EngineMetrics(
    val loadMs: Double,
    val promptTokens: Int,
    val promptMs: Double,
    /** From the generate() call to the first sampled token, so it includes prompt processing. */
    val timeToFirstTokenMs: Double,
    val generatedTokens: Int,
    /** From the end of prompt processing to the last token. */
    val generationMs: Double,
    val threads: Int,
    val stopReason: StopReason,
    /** Prompt tokens that were already in the context and were not processed again. */
    val reusedPromptTokens: Int = 0,
    /** Threads used for processing prompts; [threads] is for generating. */
    val batchThreads: Int = 0,
) {
    val tokensPerSecond: Double
        get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0

    /** Speed over the prompt tokens that were actually processed. */
    val promptTokensPerSecond: Double
        get() = if (promptMs > 0) (promptTokens - reusedPromptTokens) * 1000.0 / promptMs else 0.0
}

class EngineException(message: String) : Exception(message)
