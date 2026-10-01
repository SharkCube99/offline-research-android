package app.offlineresearch.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the Kotlin retriever on a real index and compares its top 5 with what
 * the Python reference returned for the same questions.
 *
 * Skipped unless OFFLINE_INDEX_DIR points at a directory of corpus index files
 * and OFFLINE_EVAL_JSON at a result file written by
 * `data-pipeline/eval_retrieval.py --json` on that same index.
 */
class RetrieverParityTest {

    @Test
    fun kotlinTopFiveEqualsPythonTopFive() {
        val indexDir = System.getenv("OFFLINE_INDEX_DIR")
        val evalJson = System.getenv("OFFLINE_EVAL_JSON")
        assumeTrue("OFFLINE_INDEX_DIR and OFFLINE_EVAL_JSON not set", indexDir != null && evalJson != null)

        val files = File(indexDir!!).listFiles { f -> f.name.endsWith(".db") }!!.sortedBy { it.name }
        val indexes = files.associateTo(LinkedHashMap()) { it.name.removeSuffix(".db") to JdbcSqlDatabase(it) as SqlDatabase }
        try {
            val retriever = Retriever(indexes)
            val results = Json.parseToJsonElement(File(evalJson!!).readText()).jsonObject.getValue("results").jsonArray
            val differences = mutableListOf<String>()
            for (result in results) {
                val question = result.jsonObject.getValue("question").jsonPrimitive.content
                val expected = result.jsonObject.getValue("top").jsonArray.map { it.jsonPrimitive.content }
                val actual = retriever.search(question, k = 5).map { "${it.corpus}: ${it.title} #${it.seq}" }
                if (actual != expected) differences += "$question\n  python: $expected\n  kotlin: $actual"
            }
            println("parity: ${results.size - differences.size}/${results.size} questions identical")
            assertEquals(differences.joinToString("\n"), 0, differences.size)
        } finally {
            indexes.values.forEach { it.close() }
        }
    }
}
