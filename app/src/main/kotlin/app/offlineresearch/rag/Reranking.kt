package app.offlineresearch.rag

/** Scores passages against a question by reading both; blocking. */
fun interface PassageRanker {
    /** One score per passage, higher is better; null when it could not be done. */
    fun score(question: String, passages: List<String>): FloatArray?
}

/**
 * Reorders what search found by how well each passage answers the question,
 * and keeps the best few. Search ranks by shared words; this ranks by meaning,
 * so fewer passages need to reach the answering model.
 */
class Reranking(
    private val ranker: PassageRanker,
    /** How many of the top search results are read. Each costs time. */
    private val candidates: Int,
    /** How many are kept. */
    private val keep: Int,
) {
    /**
     * [ranked] is best first by search. Passages of an article the question
     * names are kept whatever their score: the question asked for them. If the
     * ranker fails, search's order stands.
     */
    fun apply(question: String, ranked: List<Passage>): List<Passage> {
        val pool = ranked.take(candidates)
        if (pool.size <= 1) return ranked
        val scores = ranker.score(question, pool.map { it.text }) ?: return ranked
        if (scores.size != pool.size) return ranked
        val named = pool.filter { it.named }
        val best = pool.indices
            .filter { !pool[it].named }
            .sortedByDescending { scores[it] }
            .take((keep - named.size).coerceAtLeast(1))
            .map { pool[it] }
        return named + best
    }
}
