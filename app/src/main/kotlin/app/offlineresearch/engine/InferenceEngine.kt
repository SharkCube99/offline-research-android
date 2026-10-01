package app.offlineresearch.engine

import kotlinx.coroutines.flow.Flow

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
    /** 0 lets the engine choose from the number of cores. */
    val threads: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    /** Let llama.cpp keep a second, repacked copy of the weights in RAM. */
    val repack: Boolean,
    /** "auto" uses the template embedded in the model file. */
    val chatTemplate: String,
)

data class GenerationRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val maxTokens: Int,
    val temperature: Float,
    val topK: Int,
    val topP: Float,
    val presencePenalty: Float,
    val seed: Int,
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
) {
    val tokensPerSecond: Double
        get() = if (generationMs > 0) generatedTokens * 1000.0 / generationMs else 0.0

    val promptTokensPerSecond: Double
        get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
}

class EngineException(message: String) : Exception(message)
