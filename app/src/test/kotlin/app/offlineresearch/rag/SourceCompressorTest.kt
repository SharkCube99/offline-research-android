package app.offlineresearch.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class SourceCompressorTest {

    private fun passage(id: Long, title: String, body: String, seq: Int = 0) =
        Passage("wikipedia", id, title, "https://example.org/$title", seq, "$title: $body")

    private val tide = passage(
        1, "Tide",
        "Tides are the rise and fall of sea levels. They were studied by many early astronomers. " +
            "Tides are caused by the gravity of the Moon and the Sun. The Bay of Fundy has a large range. " +
            "Surfing is popular on some coasts.",
    )
    private val moon = passage(
        2, "Moon",
        "The Moon is the only natural satellite of Earth. Its gravity causes the tides in the oceans. It has no atmosphere to speak of.",
    )

    @Test
    fun sentencesSplitOnEndPunctuationButNotInsideNumbersOrAbbreviations() {
        assertEquals(
            listOf("It is 3.5 km long.", "Built in 1900 (rebuilt later).", "\"Why?\" he asked."),
            splitSentences("It is 3.5 km long. Built in 1900 (rebuilt later). \"Why?\" he asked."),
        )
        assertEquals(listOf("The pH of e.g. vinegar is low."), splitSentences("The pH of e.g. vinegar is low."))
    }

    @Test
    fun theSentencesThatBearOnTheQuestionAreKeptAndTheRestDropped() {
        val sources = SourceCompressor(70).select("What causes the tides?", listOf(tide, moon))

        assertEquals(listOf("Tide", "Moon"), sources.map { it.title })
        assertTrue("Tides are caused by the gravity of the Moon and the Sun." in sources[0].excerpt)
        assertTrue("Its gravity causes the tides in the oceans." in sources[1].excerpt)
        assertFalse("Surfing" in sources[0].excerpt)
        assertFalse("atmosphere" in sources[1].excerpt)
    }

    @Test
    fun theArticleTheQuestionNamesIsServedBeforeRemarksElsewhere() {
        val list = passage(
            10, "Vegan restaurants in Berlin",
            "The map lists 3 fully vegan places. Kopps is a fully vegan restaurant; address: Linienstrasse 94. " +
                "Lucky Leek is a fully vegan restaurant; address: Kollwitzstrasse 54. Vaust is a fully vegan pub; address: Pestalozzistrasse 8.",
        ).copy(named = true)
        val remark = passage(11, "Culture in Berlin", "Berlin is one of the cities with the most vegan restaurants in the world.", seq = 4)
        val sources = SourceCompressor(70).select("Tell me the best vegan restaurants in Berlin", listOf(remark, list))
        // The whole list fits and is taken first; the remark gets what is left, which is nothing.
        assertEquals(listOf("Vegan restaurants in Berlin"), sources.map { it.title })
        assertTrue("Vaust is a fully vegan pub" in sources.single().excerpt)
    }

    @Test
    fun theOpeningOfTheArticleTheQuestionNamesIsKeptEvenWithoutTheQuestionsWords() {
        val lead = passage(7, "Burn", "A burn is an injury to skin. Most are caused by heat. Cool the area with running water. Cover it loosely.")
        val history = passage(8, "Burn", "In 1607 a surgeon wrote on how to treat a minor burn with onions.", seq = 9)
        val sources = SourceCompressor(60).select("How do I treat a minor burn?", listOf(history, lead))
        val opening = sources.single { it.seq == 0 }
        assertTrue("Cool the area with running water." in opening.excerpt)
    }

    @Test
    fun weakSentencesFromArticlesAboutSomethingElseAreLeftOutEvenWhenTheyWouldFit() {
        val club = passage(9, "Guinea Pig Club", "Its members had burns to the face or hands.", seq = 3)
        val lead = passage(7, "Burn", "A burn is an injury to skin. To treat a minor burn, cool it with running water.")
        val sources = SourceCompressor(400).select("How do I treat a minor burn?", listOf(lead, club))
        assertEquals(listOf("Burn"), sources.map { it.title })
    }

    @Test
    fun anExcerptStartsWithTheTitleAndMarksGaps() {
        val source = SourceCompressor(30).select("What causes the tides?", listOf(tide)).single()
        assertEquals(
            "Tide: Tides are the rise and fall of sea levels. … Tides are caused by the gravity of the Moon and the Sun.",
            source.excerpt,
        )
        // The full passage stays available for the sources panel.
        assertEquals(tide.text, source.text)
    }

    @Test
    fun everySentenceOfAnExcerptIsCopiedFromThePassage() {
        for (source in SourceCompressor(60).select("What causes the tides?", listOf(tide, moon))) {
            val body = source.excerpt.removePrefix("${source.title}: ")
            for (piece in body.split(" … ")) assertTrue(piece, piece in source.text)
        }
    }

    @Test
    fun editorNotesAreDroppedAndRunOnListsAreSkipped() {
        val list = "Tides see also " + (1..60).joinToString(" ") { "Tide table $it –" } + " end."
        val messy = passage(
            5, "Tide",
            "Tides are caused by the Moon[citation needed] and the Sun. $list Smith, J. Tides and what causes them, pp. 12–30, ISBN 123.",
            seq = 1,
        )
        val source = SourceCompressor(300).select("What causes the tides?", listOf(messy)).single()
        assertEquals("Tide: Tides are caused by the Moon and the Sun.", source.excerpt)
    }

    @Test
    fun wordsCoveredByTheTitlePickTheArticleAndTheOtherWordsPickTheSentence() {
        val airport = passage(
            6, "Charles de Gaulle Airport",
            "It is named after Charles de Gaulle. The airport is connected to central Paris by the RER B line. It opened in 1974.",
            seq = 2,
        )
        val source = SourceCompressor(30).select("How to get from Charles de Gaulle airport to central Paris?", listOf(airport)).single()
        assertEquals("Charles de Gaulle Airport: The airport is connected to central Paris by the RER B line.", source.excerpt)
    }

    @Test
    fun theBudgetIsRespected() {
        for (budget in listOf(15, 30, 60, 120)) {
            val sources = SourceCompressor(budget).select("What causes the tides?", listOf(tide, moon))
            assertTrue(sources.isNotEmpty())
            assertTrue("budget $budget", sources.sumOf { roughTokens(it.excerpt) } <= budget + 4 * sources.size)
        }
    }

    @Test
    fun leftoverBudgetGoesToTheSentenceAfterAChosenOne() {
        val bite = passage(
            3, "Snakebite",
            "A snakebite is an injury caused by a snake. Keep the limb still and below the heart. Remove rings and watches. " +
                "Many snakes are harmless.",
            seq = 2,
        )
        val source = SourceCompressor(200).select("What to do after a snakebite?", listOf(bite)).single()
        assertTrue("Keep the limb still and below the heart." in source.excerpt)
    }

    @Test
    fun aLargeBudgetKeepsWholePassagesWithoutGapMarks() {
        val source = SourceCompressor(500).select("What causes the tides?", listOf(tide)).single()
        assertEquals(tide.text, source.excerpt)
    }

    @Test
    fun atMostTwoPassagesPerArticleAndSixInAll() {
        val many = (1..5).map { passage(it.toLong(), "Tide", "Tides are caused by gravity, part $it.", seq = it) } +
            (10..20).map { passage(it.toLong(), "Tide table $it", "What causes tides is explained in table $it.", seq = 1) }
        val sources = SourceCompressor(2000).select("What causes the tides?", many)
        assertEquals(6, sources.size)
        assertEquals(2, sources.count { it.title == "Tide" })
    }

    @Test
    fun whenNoSentenceMatchesTheBestPassageIsCutToTheBudget() {
        val other = passage(4, "Granite", "Granite is a coarse rock. It forms from magma. It is used in building.", seq = 3)
        val source = SourceCompressor(8).select("zebra stripes", listOf(other)).single()
        assertEquals("Granite: Granite is a coarse rock.", source.excerpt)
    }

    @Test
    fun noPassagesGiveNoSourcesAndNoPreview() {
        assertTrue(SourceCompressor(100).select("anything", emptyList()).isEmpty())
        assertNull(SourceCompressor(100).preview("anything", emptyList()))
    }

    @Test
    fun thePreviewIsAShortExcerptOfTheBestPassage() {
        val preview = SourceCompressor(400).preview("What causes the tides?", listOf(tide, moon), previewTokens = 30)!!
        assertEquals("Tide", preview.title)
        assertTrue("Tides are caused by the gravity of the Moon and the Sun." in preview.excerpt)
        assertTrue(roughTokens(preview.excerpt) <= 34)
    }
}

/**
 * Compresses the sources for the retrieval evaluation questions on a real
 * index and writes what the model would be given, with sizes, to a file.
 *
 * Skipped unless OFFLINE_INDEX_DIR points at a directory of corpus index files
 * and OFFLINE_COMPRESSION_REPORT names the file to write. OFFLINE_QUERIES may
 * name a different question file (JSON lines with a "question" field).
 */
class CompressionReportTest {

    @Test
    fun writeReport() {
        val indexDir = System.getenv("OFFLINE_INDEX_DIR")
        val out = System.getenv("OFFLINE_COMPRESSION_REPORT")
        assumeTrue("OFFLINE_INDEX_DIR and OFFLINE_COMPRESSION_REPORT not set", indexDir != null && out != null)
        val budget = System.getenv("OFFLINE_BUDGET")?.toInt() ?: 450
        // One JSON object per line: "question", and optionally "queries", the
        // searches a planner added, to replay what the app does on the phone.
        val queries = File(System.getenv("OFFLINE_QUERIES") ?: "../data-pipeline/eval/queries.jsonl").readLines()
            .filter { it.isNotBlank() }
            .map { line ->
                val item = Json.parseToJsonElement(line).jsonObject
                item.getValue("question").jsonPrimitive.content to
                    (item["queries"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList())
            }

        val files = File(indexDir!!).listFiles { f -> f.name.endsWith(".db") }!!.sortedBy { it.name }
        val indexes = files.associateTo(LinkedHashMap()) { it.name.removeSuffix(".db") to JdbcSqlDatabase(it) as SqlDatabase }
        try {
            val retriever = Retriever(indexes)
            val compressor = SourceCompressor(budget)
            val whole = ContextBudgeter(800, countTokens = ::roughTokens)
            val compressed = mutableListOf<Int>()
            val full = mutableListOf<Int>()
            val counts = mutableListOf<Int>()
            val report = StringBuilder()
            for ((question, planned) in queries) {
                val first = retriever.withLeads(question, retriever.search(question))
                val ranked = retriever.withLeads(question, interleave(listOf(first) + planned.map { retriever.search(it, context = question) }))
                val sources = compressor.select((listOf(question) + planned).joinToString(" "), ranked)
                val before = whole.select(ranked)
                compressed += sources.sumOf { roughTokens(it.excerpt) }
                full += before.sumOf { roughTokens(it.text) }
                counts += sources.size
                report.append("## ").append(question).append('\n')
                if (planned.isNotEmpty()) report.append("planner: ").append(planned.joinToString("; ")).append('\n')
                report.append("whole passages (800 budget): ${before.size} passages from ${before.map { it.title }.distinct().size} articles, ~${full.last()} tokens; ")
                report.append("compressed ($budget budget): ${sources.size} passages from ${sources.map { it.title }.distinct().size} articles, ~${compressed.last()} tokens\n\n")
                sources.forEachIndexed { i, s -> report.append("[${i + 1}] ").append(s.excerpt).append("\n\n") }
            }
            fun median(values: List<Int>) = values.sorted()[values.size / 2]
            val summary = "questions ${queries.size}; estimated source tokens (chars/4), median: whole passages ${median(full)}, " +
                "compressed ${median(compressed)} (max ${compressed.max()}); passages per question, median: ${median(counts)}"
            println(summary)
            File(out!!).writeText("# Compressed sources, budget $budget\n\n$summary\n\n$report")
        } finally {
            indexes.values.forEach { it.close() }
        }
    }
}
