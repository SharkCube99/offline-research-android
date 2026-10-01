package app.offlineresearch.rag

/**
 * Chooses which retrieved passages go into the prompt: best first, within a
 * token budget, without repeats and without letting one article dominate.
 */
class ContextBudgeter(
    private val budgetTokens: Int,
    private val maxPerSource: Int = 2,
    private val countTokens: (String) -> Int,
) {
    /** [ranked] is best first. The result keeps that order. */
    fun select(ranked: List<Passage>): List<Passage> {
        val chosen = mutableListOf<Passage>()
        val seen = HashSet<String>()
        val perSource = HashMap<String, Int>()
        var used = 0
        for (passage in ranked) {
            if (!seen.add(passage.passageId)) continue
            val fromSource = perSource[passage.sourceKey] ?: 0
            if (fromSource >= maxPerSource) continue
            val cost = countTokens(passage.text)
            // A passage that does not fit is skipped, not truncated; a shorter
            // one further down may still fit.
            if (used + cost > budgetTokens) continue
            chosen += passage
            perSource[passage.sourceKey] = fromSource + 1
            used += cost
        }
        return chosen
    }
}

/** Merges the result lists of several searches: lists take turns, repeats are dropped. */
fun interleave(lists: List<List<Passage>>): List<Passage> {
    val merged = mutableListOf<Passage>()
    val seen = HashSet<String>()
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (position in 0 until longest) {
        for (list in lists) {
            val passage = list.getOrNull(position) ?: continue
            if (seen.add(passage.passageId)) merged += passage
        }
    }
    return merged
}
