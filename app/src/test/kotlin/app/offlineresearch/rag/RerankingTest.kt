package app.offlineresearch.rag

import org.junit.Assert.assertEquals
import org.junit.Test

class RerankingTest {
    private fun passage(id: Long, title: String, named: Boolean = false) =
        Passage("wikipedia", id, title, "", 0, "$title: text $id", named = named)

    private val found = listOf(passage(1, "A"), passage(2, "B"), passage(3, "C"), passage(4, "D"), passage(5, "E"))

    @Test
    fun theBestScoredPassagesAreKeptBestFirst() {
        val ranker = PassageRanker { _, passages -> FloatArray(passages.size) { it.toFloat() } } // the last is best
        val kept = Reranking(ranker, candidates = 4, keep = 2).apply("q", found)
        // Only the first four were read; of those, the two with the highest scores.
        assertEquals(listOf("D", "C"), kept.map { it.title })
    }

    @Test
    fun aPassageTheQuestionNamesIsKeptWhateverItsScore() {
        val named = listOf(passage(1, "A", named = true)) + found.drop(1)
        val ranker = PassageRanker { _, passages -> FloatArray(passages.size) { it.toFloat() } }
        val kept = Reranking(ranker, candidates = 5, keep = 3).apply("q", named)
        assertEquals(listOf("A", "E", "D"), kept.map { it.title })
    }

    @Test
    fun searchOrderStandsWhenTheRankerFails() {
        assertEquals(found, Reranking({ _, _ -> null }, 5, 2).apply("q", found))
        assertEquals(found, Reranking({ _, _ -> FloatArray(1) }, 5, 2).apply("q", found))
    }

    @Test
    fun theRankerReadsWholePassages() {
        var read = emptyList<String>()
        Reranking({ _, passages -> read = passages; FloatArray(passages.size) }, 2, 1).apply("q", found)
        assertEquals(listOf("A: text 1", "B: text 2"), read)
    }
}
