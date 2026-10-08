package app.offlineresearch.engine

import app.offlineresearch.rag.PassageRanker

/**
 * [PassageRanker] backed by a small reranking model run by llama.cpp. The
 * model reads the question and one passage together, which is what lets it
 * tell a passage that answers the question from one that only shares its words.
 */
class LlamaRanker(private val nativeLibDir: String) : PassageRanker {

    /** 0 while no model is loaded. */
    @Volatile
    private var handle = 0L

    val loaded: Boolean get() = handle != 0L

    @Synchronized
    fun load(modelPath: String, contextSize: Int, threads: Int) {
        LlamaBridge.ensureInitialised(nativeLibDir)
        unload()
        val result = LlamaBridge.nativeRankerLoad(modelPath, contextSize, threads)
        if (result == LlamaBridge.LOAD_ERR_MODEL || result == LlamaBridge.LOAD_ERR_CONTEXT) {
            throw EngineException("The reranking model could not be loaded: $modelPath")
        }
        handle = result
    }

    @Synchronized
    override fun score(question: String, passages: List<String>): FloatArray? {
        val session = handle
        if (session == 0L || passages.isEmpty()) return null
        return LlamaBridge.nativeRank(
            session,
            question.toByteArray(Charsets.UTF_8),
            Array(passages.size) { passages[it].toByteArray(Charsets.UTF_8) },
        )
    }

    @Synchronized
    fun unload() {
        val old = handle
        if (old != 0L) {
            handle = 0L
            LlamaBridge.nativeRankerUnload(old)
        }
    }
}
