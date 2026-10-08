package app.offlineresearch.engine

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * [InferenceEngine] backed by llama.cpp through [LlamaBridge]. Each instance
 * holds one model; several instances can be loaded at once.
 */
class LlamaEngine(private val nativeLibDir: String, name: String = "llama-engine") : InferenceEngine {

    // llama.cpp calls block for seconds to minutes; they get one dedicated thread
    // so they never run concurrently and never occupy a shared dispatcher.
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name)
    }.asCoroutineDispatcher()

    /** 0 while no model is loaded. */
    @Volatile
    private var handle = 0L

    @Volatile
    private var lastStop = StopReason.NONE

    private fun requireHandle(): Long = handle.also { if (it == 0L) throw EngineException("No model is loaded") }

    override suspend fun load(modelPath: String, config: EngineConfig) = withContext(dispatcher) {
        LlamaBridge.ensureInitialised(nativeLibDir)
        release()
        lastStop = StopReason.NONE
        val result = LlamaBridge.nativeLoad(
            modelPath,
            config.contextSize,
            config.batchSize,
            config.threads,
            config.batchThreads,
            config.useMmap,
            config.useMlock,
            config.repack,
            when (config.threadAffinity) {
                ThreadAffinity.NONE -> LlamaBridge.AFFINITY_NONE
                ThreadAffinity.FASTEST -> LlamaBridge.AFFINITY_FASTEST
            },
            config.chatTemplate,
        )
        // Anything but the two error codes is a handle. It is a native pointer, and
        // Android tags the top byte of pointers, so a valid handle can be negative.
        when (result) {
            LlamaBridge.LOAD_ERR_MODEL ->
                throw EngineException("llama.cpp could not load the model file: $modelPath")
            LlamaBridge.LOAD_ERR_CONTEXT ->
                throw EngineException("Could not create a context (n_ctx=${config.contextSize}); likely out of memory")
            else -> handle = result
        }
    }

    override fun generate(request: GenerationRequest): Flow<String> = channelFlow {
        val session = requireHandle()
        val code = LlamaBridge.nativeGenerate(
            session,
            request.systemPrompt.toByteArray(Charsets.UTF_8),
            request.userPrompt.toByteArray(Charsets.UTF_8),
            request.assistantPrefix.toByteArray(Charsets.UTF_8),
            request.maxTokens,
            request.temperature,
            request.topK,
            request.topP,
            request.presencePenalty,
            request.seed,
        ) { utf8 ->
            // The collector going away is the only way trySend fails on an
            // unlimited buffer; stop generating for nobody.
            if (trySend(String(utf8, Charsets.UTF_8)).isFailure) LlamaBridge.nativeCancel(session)
        }
        lastStop = when (code) {
            LlamaBridge.STOP_EOS -> StopReason.EOS
            LlamaBridge.STOP_MAX_TOKENS -> StopReason.MAX_TOKENS
            LlamaBridge.STOP_CANCELLED -> StopReason.CANCELLED
            LlamaBridge.STOP_CONTEXT_FULL -> StopReason.CONTEXT_FULL
            else -> StopReason.ERROR
        }
        when (code) {
            LlamaBridge.ERR_BUSY -> throw EngineException("Engine is busy")
            LlamaBridge.GEN_ERR_TEMPLATE -> throw EngineException("The chat template could not be applied")
            LlamaBridge.GEN_ERR_TOKENIZE -> throw EngineException("The prompt could not be tokenised")
            LlamaBridge.GEN_ERR_PROMPT_TOO_LONG -> throw EngineException("The prompt does not fit in the context window")
            LlamaBridge.GEN_ERR_DECODE -> throw EngineException("llama.cpp failed while decoding")
        }
    }.buffer(Channel.UNLIMITED).flowOn(dispatcher)

    // A prompt with these rules and an empty question, and no reply asked for:
    // the bridge keeps what it read, and the next prompt that starts the same
    // way skips that part. Queued on the engine thread like any other call, so
    // a question asked meanwhile waits for it and then benefits from it.
    override suspend fun prime(systemPrompt: String, saved: File?) = withContext(dispatcher) {
        val session = handle
        if (session == 0L) return@withContext
        if (saved != null && saved.isFile) {
            if (LlamaBridge.nativeLoadState(session, saved.absolutePath) > 0) return@withContext
            saved.delete()
        }
        val code = LlamaBridge.nativeGenerate(
            session,
            systemPrompt.toByteArray(Charsets.UTF_8),
            ByteArray(0),
            ByteArray(0),
            0,
            0f,
            1,
            1f,
            0f,
            0,
        ) { }
        // Written under another name first: a file cut short by the app being closed must never be read back.
        if (saved != null && code == LlamaBridge.STOP_MAX_TOKENS) {
            val partial = File(saved.path + ".part")
            if (LlamaBridge.nativeSaveState(session, partial.absolutePath) && partial.renameTo(saved)) {
                // Read back at once: it proves the file, and tells the bridge where to
                // find it for models that must start from it before every prompt.
                if (LlamaBridge.nativeLoadState(session, saved.absolutePath) <= 0) saved.delete()
            } else {
                partial.delete()
            }
        }
    }

    override fun countTokens(text: String): Int {
        val count = LlamaBridge.nativeTokenCount(requireHandle(), text.toByteArray(Charsets.UTF_8))
        if (count < 0) throw EngineException("The text could not be tokenised")
        return count
    }

    override fun cancel() {
        handle.let { if (it != 0L) LlamaBridge.nativeCancel(it) }
    }

    override fun metrics(): EngineMetrics {
        val m = LlamaBridge.nativeMetrics(requireHandle())
        return EngineMetrics(
            loadMs = m[LlamaBridge.M_LOAD_MS],
            promptTokens = m[LlamaBridge.M_PROMPT_TOKENS].toInt(),
            promptMs = m[LlamaBridge.M_PROMPT_MS],
            timeToFirstTokenMs = m[LlamaBridge.M_TTFT_MS],
            generatedTokens = m[LlamaBridge.M_GEN_TOKENS].toInt(),
            generationMs = m[LlamaBridge.M_GEN_MS],
            threads = m[LlamaBridge.M_THREADS].toInt(),
            reusedPromptTokens = m[LlamaBridge.M_PROMPT_REUSED].toInt(),
            batchThreads = m[LlamaBridge.M_THREADS_BATCH].toInt(),
            stopReason = lastStop,
        )
    }

    override fun systemInfo(): String = LlamaBridge.nativeSystemInfo()

    override suspend fun unload() {
        // Cancel from the caller's thread first: the engine thread may be inside generate().
        cancel()
        withContext(dispatcher) { release() }
    }

    /** Engine thread only. */
    private fun release() {
        val old = handle
        if (old != 0L) {
            handle = 0L
            LlamaBridge.nativeUnload(old)
        }
    }
}
