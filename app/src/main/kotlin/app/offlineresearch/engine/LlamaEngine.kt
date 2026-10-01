package app.offlineresearch.engine

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** [InferenceEngine] backed by llama.cpp through [LlamaBridge]. */
class LlamaEngine(private val nativeLibDir: String) : InferenceEngine {

    // llama.cpp calls block for seconds to minutes; they get one dedicated thread
    // so they never run concurrently and never occupy a shared dispatcher.
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama-engine")
    }.asCoroutineDispatcher()

    private var initialised = false

    @Volatile
    private var lastStop = StopReason.NONE

    private fun ensureInitialised() {
        if (!initialised) {
            LlamaBridge.nativeInit(nativeLibDir)
            initialised = true
        }
    }

    override suspend fun load(modelPath: String, config: EngineConfig) = withContext(dispatcher) {
        ensureInitialised()
        lastStop = StopReason.NONE
        val code = LlamaBridge.nativeLoad(
            modelPath,
            config.contextSize,
            config.batchSize,
            config.threads,
            config.useMmap,
            config.useMlock,
            config.repack,
            config.chatTemplate,
        )
        when (code) {
            LlamaBridge.LOAD_OK -> Unit
            LlamaBridge.LOAD_ERR_MODEL ->
                throw EngineException("llama.cpp could not load the model file: $modelPath")
            LlamaBridge.LOAD_ERR_CONTEXT ->
                throw EngineException("Could not create a context (n_ctx=${config.contextSize}); likely out of memory")
            LlamaBridge.ERR_BUSY -> throw EngineException("Engine is busy")
            else -> throw EngineException("Model load failed with code $code")
        }
    }

    override fun generate(request: GenerationRequest): Flow<String> = channelFlow {
        val code = LlamaBridge.nativeGenerate(
            request.systemPrompt.toByteArray(Charsets.UTF_8),
            request.userPrompt.toByteArray(Charsets.UTF_8),
            request.maxTokens,
            request.temperature,
            request.topK,
            request.topP,
            request.presencePenalty,
            request.seed,
        ) { utf8 ->
            // The collector going away is the only way trySend fails on an
            // unlimited buffer; stop generating for nobody.
            if (trySend(String(utf8, Charsets.UTF_8)).isFailure) LlamaBridge.nativeCancel()
        }
        lastStop = when (code) {
            LlamaBridge.STOP_EOS -> StopReason.EOS
            LlamaBridge.STOP_MAX_TOKENS -> StopReason.MAX_TOKENS
            LlamaBridge.STOP_CANCELLED -> StopReason.CANCELLED
            LlamaBridge.STOP_CONTEXT_FULL -> StopReason.CONTEXT_FULL
            else -> StopReason.ERROR
        }
        when (code) {
            LlamaBridge.GEN_ERR_NOT_LOADED -> throw EngineException("No model is loaded")
            LlamaBridge.ERR_BUSY -> throw EngineException("Engine is busy")
            LlamaBridge.GEN_ERR_TEMPLATE -> throw EngineException("The chat template could not be applied")
            LlamaBridge.GEN_ERR_TOKENIZE -> throw EngineException("The prompt could not be tokenised")
            LlamaBridge.GEN_ERR_PROMPT_TOO_LONG -> throw EngineException("The prompt does not fit in the context window")
            LlamaBridge.GEN_ERR_DECODE -> throw EngineException("llama.cpp failed while decoding")
        }
    }.buffer(Channel.UNLIMITED).flowOn(dispatcher)

    override fun cancel() = LlamaBridge.nativeCancel()

    override fun metrics(): EngineMetrics {
        val m = LlamaBridge.nativeMetrics()
        return EngineMetrics(
            loadMs = m[LlamaBridge.M_LOAD_MS],
            promptTokens = m[LlamaBridge.M_PROMPT_TOKENS].toInt(),
            promptMs = m[LlamaBridge.M_PROMPT_MS],
            timeToFirstTokenMs = m[LlamaBridge.M_TTFT_MS],
            generatedTokens = m[LlamaBridge.M_GEN_TOKENS].toInt(),
            generationMs = m[LlamaBridge.M_GEN_MS],
            threads = m[LlamaBridge.M_THREADS].toInt(),
            stopReason = lastStop,
        )
    }

    override fun systemInfo(): String = LlamaBridge.nativeSystemInfo()

    override suspend fun unload() {
        // Cancel from the caller's thread first: the engine thread may be inside generate().
        LlamaBridge.nativeCancel()
        withContext(dispatcher) { LlamaBridge.nativeUnload() }
    }
}
