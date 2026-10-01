package app.offlineresearch.engine

/** Called from native code with one or more complete UTF-8 characters. */
internal fun interface TokenCallback {
    fun onToken(utf8: ByteArray)
}

/** Raw JNI surface of llama_bridge.cpp. Use [LlamaEngine], not this. */
internal object LlamaBridge {
    init {
        System.loadLibrary("llama_bridge")
    }

    // Return codes; keep in step with llama_bridge.cpp.
    const val LOAD_OK = 0
    const val LOAD_ERR_MODEL = -1
    const val LOAD_ERR_CONTEXT = -2
    const val ERR_BUSY = -3

    const val STOP_EOS = 0
    const val STOP_MAX_TOKENS = 1
    const val STOP_CANCELLED = 2
    const val STOP_CONTEXT_FULL = 3
    const val GEN_ERR_NOT_LOADED = -1
    const val GEN_ERR_TEMPLATE = -4
    const val GEN_ERR_TOKENIZE = -5
    const val GEN_ERR_PROMPT_TOO_LONG = -6
    const val GEN_ERR_DECODE = -7

    // Layout of the array returned by nativeMetrics().
    const val M_LOAD_MS = 0
    const val M_PROMPT_TOKENS = 1
    const val M_PROMPT_MS = 2
    const val M_TTFT_MS = 3
    const val M_GEN_TOKENS = 4
    const val M_GEN_MS = 5
    const val M_THREADS = 6

    external fun nativeInit(nativeLibDir: String)

    external fun nativeLoad(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        repack: Boolean,
        chatTemplate: String,
    ): Int

    /** Blocks until generation stops. Returns a STOP_* code, or a negative error code. */
    external fun nativeGenerate(
        systemUtf8: ByteArray,
        userUtf8: ByteArray,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        presencePenalty: Float,
        seed: Int,
        callback: TokenCallback,
    ): Int

    external fun nativeCancel()

    external fun nativeMetrics(): DoubleArray

    external fun nativeSystemInfo(): String

    external fun nativeUnload()
}
