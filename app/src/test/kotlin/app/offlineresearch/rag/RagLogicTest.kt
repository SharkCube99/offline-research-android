package app.offlineresearch.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun passage(id: Long, title: String, text: String = "$title: text", corpus: String = "wikipedia") =
    Passage(corpus, id, title, "https://example.org/$title", 0, text)

class QueryVectorsTest {

    // Written by the Python reference (data-pipeline/retrieval.py). Unit tests
    // run with the module directory (app/) as the working directory.
    private val vectors = Json.parseToJsonElement(File("../data-pipeline/eval/query_vectors.json").readText()).jsonArray

    @Test
    fun kotlinQueryHandlingMatchesThePythonReference() {
        assertTrue(vectors.size >= 10)
        for (vector in vectors) {
            val v = vector.jsonObject
            val question = v.getValue("question").jsonPrimitive.content
            fun strings(key: String) = v.getValue(key).jsonArray.map { it.jsonPrimitive.content }

            assertEquals(question, strings("tokens"), tokenize(question))
            assertEquals(question, strings("terms"), QueryBuilder.queryTerms(question))
            assertEquals(question, v.getValue("and_query").jsonPrimitive.content,
                QueryBuilder.match(QueryBuilder.queryTerms(question), "AND"))
            assertEquals(question, v.getValue("index_text").jsonPrimitive.content, indexText(question))
            val expectedGrams = v.getValue("name_grams").jsonArray.map {
                val gram = it.jsonArray
                QueryBuilder.NameGram(gram[0].jsonPrimitive.int, gram[1].jsonPrimitive.int, gram[2].jsonPrimitive.content)
            }
            assertEquals(question, expectedGrams, QueryBuilder.nameGrams(question))
        }
    }
}

class ContextBudgeterTest {

    private val words: (String) -> Int = { it.split(' ').size }

    private fun budgeter(budget: Int, maxPerSource: Int = 2) = ContextBudgeter(budget, maxPerSource, words)

    @Test
    fun keepsRankOrderAndStopsAtTheBudget() {
        val ranked = listOf(
            passage(1, "A", "one two three four"),
            passage(2, "B", "one two three four"),
            passage(3, "C", "one two three four"),
        )
        assertEquals(listOf(1L, 2L), budgeter(8).select(ranked).map { it.id })
    }

    @Test
    fun aPassageThatDoesNotFitIsSkippedAndASmallerOneStillUsed() {
        val ranked = listOf(
            passage(1, "A", "one two three"),
            passage(2, "B", "one two three four five six"),
            passage(3, "C", "one two"),
        )
        assertEquals(listOf(1L, 3L), budgeter(5).select(ranked).map { it.id })
    }

    @Test
    fun theSamePassageIsNotUsedTwice() {
        val ranked = listOf(passage(1, "A", "x"), passage(1, "A", "x"), passage(2, "B", "x"))
        assertEquals(listOf(1L, 2L), budgeter(100).select(ranked).map { it.id })
    }

    @Test
    fun oneSourceCannotTakeMoreThanItsShare() {
        val ranked = listOf(passage(1, "A"), passage(2, "A"), passage(3, "A"), passage(4, "B"))
        assertEquals(listOf(1L, 2L, 4L), budgeter(100).select(ranked).map { it.id })
    }

    @Test
    fun sameTitleInDifferentCorporaAreDifferentSources() {
        val ranked = listOf(
            passage(1, "Rome"), passage(2, "Rome"),
            passage(1, "Rome", corpus = "wikivoyage"), passage(2, "Rome", corpus = "wikivoyage"),
        )
        assertEquals(4, budgeter(100).select(ranked).size)
    }

    @Test
    fun nothingFitsInAZeroBudget() {
        assertTrue(budgeter(0).select(listOf(passage(1, "A"))).isEmpty())
    }

    @Test
    fun interleaveTakesTurnsAndDropsRepeats() {
        val first = listOf(passage(1, "A"), passage(2, "B"), passage(3, "C"))
        val second = listOf(passage(2, "B"), passage(9, "Z"))
        assertEquals(listOf(1L, 2L, 9L, 3L), interleave(listOf(first, second)).map { it.id })
    }

    @Test
    fun interleaveOfNothingIsEmpty() {
        assertTrue(interleave(emptyList()).isEmpty())
    }
}

class CitationsTest {

    private fun numbers(answer: String, sources: Int) = Citations.cited(answer, sources)

    @Test
    fun textWithoutCitationsIsOnePart() {
        assertEquals(listOf(AnswerPart.Text("Plain answer.")), Citations.parse("Plain answer.", 3))
    }

    @Test
    fun citationsSplitTheText() {
        assertEquals(
            listOf(
                AnswerPart.Text("Water boils at 100 C "),
                AnswerPart.Citation(1),
                AnswerPart.Text(" at sea level "),
                AnswerPart.Citation(2),
                AnswerPart.Text("."),
            ),
            Citations.parse("Water boils at 100 C [1] at sea level [2].", 2),
        )
    }

    @Test
    fun adjacentAndGroupedCitationsAreRead() {
        assertEquals(listOf(1, 2), numbers("Fact [1][2].", 3))
        assertEquals(listOf(1, 3), numbers("Fact [1, 3].", 3))
        assertEquals(listOf(2, 1), numbers("Fact [2,1].", 3))
    }

    @Test
    fun citedListsEachSourceOnceInOrderOfFirstUse() {
        assertEquals(listOf(2, 1), numbers("A [2]. B [1]. C [2].", 3))
    }

    @Test
    fun aNumberWithNoSourceIsDroppedAndReported() {
        val answer = "Real [1]. Invented [7]. Zero [0]."
        assertEquals(listOf(1), numbers(answer, 3))
        assertEquals(listOf(7, 0), Citations.invalid(answer, 3))
        assertFalse(Citations.parse(answer, 3).contains(AnswerPart.Citation(7)))
    }

    @Test
    fun aGroupKeepsItsValidNumbersOnly() {
        assertEquals(listOf(AnswerPart.Text("x "), AnswerPart.Citation(2)), Citations.parse("x [2, 9]", 3))
    }

    @Test
    fun otherBracketsAreOrdinaryText() {
        val answer = "See [note] and [a1] and [2024-01] and array[i]."
        assertEquals(listOf(AnswerPart.Text(answer)), Citations.parse(answer, 5))
    }

    @Test
    fun anUnfinishedCitationWhileStreamingIsText() {
        assertEquals(listOf(AnswerPart.Text("Fact [1")), Citations.parse("Fact [1", 3))
    }

    @Test
    fun notCoveredReplyIsRecognised() {
        assertTrue(Citations.isNotCovered("Not covered by the offline sources."))
        assertTrue(Citations.isNotCovered("  not covered by the offline sources"))
        assertTrue(Citations.isNotCovered("The capital is Paris [1]. The population is not covered by the offline sources."))
        assertFalse(Citations.isNotCovered("The capital is Paris [1]."))
    }
}

class PromptBuilderTest {

    @Test
    fun sourcesAreNumberedFromOneInOrder() {
        val prompt = PromptBuilder.user("Why?", listOf(passage(5, "Alpha", "Alpha: a"), passage(9, "Beta", "Beta: b")))
        assertTrue(prompt.indexOf("[1] Alpha: a") in 0 until prompt.indexOf("[2] Beta: b"))
        assertTrue(prompt.trimEnd().endsWith("Question: Why?"))
    }

    @Test
    fun systemPromptStatesTheContractAndTheExactRefusal() {
        val system = PromptBuilder.system("/no_think")
        assertTrue(PromptBuilder.NOT_COVERED in system)
        assertTrue("square brackets" in system)
        assertTrue(system.endsWith("/no_think"))
    }

    @Test
    fun ownKnowledgeIsAllowedOnlyUnderItsLabelAndCanBeSwitchedOff() {
        val open = PromptBuilder.system()
        assertTrue(PromptBuilder.UNSOURCED in open)
        assertTrue(PromptBuilder.NOT_COVERED in open)
        val strict = PromptBuilder.system(ownKnowledge = false)
        assertTrue(PromptBuilder.UNSOURCED !in strict)
        assertTrue("state as a fact only what a source supports" in strict)

        assertTrue(Citations.hasUnsourced("Boil it [1].\n\n${PromptBuilder.UNSOURCED}\nWater weighs 1 kg per litre."))
        assertTrue(Citations.hasUnsourced("**From my general knowledge (not from the offline sources):** yes."))
        assertTrue(!Citations.hasUnsourced("The tide is caused by the Moon [1]."))
    }

    @Test
    fun noSuffixAddsNothing() {
        assertFalse(PromptBuilder.system().endsWith("\n"))
    }
}

class PlannerOutputTest {

    @Test
    fun oneQueryPerLine() {
        assertEquals(listOf("fire making", "bow drill"), PlannerOutput.parse("fire making\nbow drill\n"))
    }

    @Test
    fun numberingBulletsAndQuotesAreStripped() {
        assertEquals(
            listOf("Neil Armstrong", "Apollo 11", "Moon landing"),
            PlannerOutput.parse("1. Neil Armstrong\n2) \"Apollo 11\"\n- Moon landing"),
        )
    }

    @Test
    fun atMostThreeQueries() {
        assertEquals(3, PlannerOutput.parse("a1\nb2\nc3\nd4\ne5").size)
    }

    @Test
    fun reasoningBlocksAreIgnored() {
        assertEquals(listOf("Polaris"), PlannerOutput.parse("<think>\nthe user wants stars\n</think>\n\nPolaris"))
    }

    @Test
    fun unfinishedReasoningGivesNothing() {
        assertTrue(PlannerOutput.parse("<think>\nI should search for").isEmpty())
    }

    @Test
    fun proseIsNotAQuery() {
        val prose = "Sure! Here are some search queries that you could use to find the answer to your question about stars:"
        assertTrue(PlannerOutput.parse(prose).isEmpty())
    }

    @Test
    fun emptyAndPunctuationOnlyOutputGivesNothing() {
        assertTrue(PlannerOutput.parse("").isEmpty())
        assertTrue(PlannerOutput.parse("\n\n ... \n---\n").isEmpty())
    }

    @Test
    fun repeatedQueriesAreMerged() {
        assertEquals(listOf("Tide"), PlannerOutput.parse("Tide\ntide\nTIDE"))
    }

    @Test
    fun queryLabelsAreStripped() {
        assertEquals(listOf("jet engine"), PlannerOutput.parse("Query 1: jet engine"))
    }
}
