package app.offlineresearch.rag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnswerFormatTest {

    private val covered = PromptBuilder.NOT_COVERED

    @Test
    fun aStrayNotCoveredLineAfterACitedAnswerIsRemoved() {
        val answer = "Tides are caused by the Moon [1].\n\n$covered"
        assertEquals("Tides are caused by the Moon [1].", Citations.dropStrayNotCovered(answer))
        assertFalse(Citations.isNotCovered(answer))
    }

    @Test
    fun theNotCoveredReplyOnItsOwnIsKept() {
        assertEquals(covered, Citations.dropStrayNotCovered(covered))
        assertTrue(Citations.isNotCovered(covered))
        assertTrue(Citations.isNotCovered("**$covered**"))
    }

    @Test
    fun anAnswerWithoutCitationsKeepsTheSentence() {
        val answer = "I think it is the Moon.\n$covered"
        assertEquals(answer, Citations.dropStrayNotCovered(answer))
        assertTrue(Citations.isNotCovered(answer))
    }

    @Test
    fun theSentenceInsideALongerLineIsNotTouched() {
        val answer = "The Moon causes tides [1]. The Sun's part is not covered by the offline sources."
        assertEquals(answer, Citations.dropStrayNotCovered(answer))
    }

    @Test
    fun listMarkersBecomeBulletsAndDoubleAsterisksBecomeBold() {
        val parts = Citations.parse("Causes:\n*   **The Moon**: gravity [1].\n- The Sun [2]", 2)
        assertEquals(
            listOf(
                AnswerPart.Text("Causes:\n• "),
                AnswerPart.Text("The Moon", bold = true),
                AnswerPart.Text(": gravity "),
                AnswerPart.Citation(1),
                AnswerPart.Text(".\n• The Sun "),
                AnswerPart.Citation(2),
            ),
            parts,
        )
    }

    @Test
    fun headingsBecomeBoldLinesAndAnUnfinishedBoldShowsNoAsterisks() {
        assertEquals(
            listOf(AnswerPart.Text("Summary", bold = true), AnswerPart.Text("\nText")),
            Citations.parse("## Summary\nText", 0),
        )
        // While streaming, the closing ** has not arrived yet.
        assertEquals(
            listOf(AnswerPart.Text("The "), AnswerPart.Text("Moo", bold = true)),
            Citations.parse("The **Moo", 0),
        )
    }

    @Test
    fun aSingleAsteriskOrHyphenInsideALineIsLeftAlone() {
        assertEquals(listOf(AnswerPart.Text("5 * 3 - 2 is 13")), Citations.parse("5 * 3 - 2 is 13", 0))
    }
}

class LeadPassageTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var index: SqlDatabase
    private lateinit var retriever: Retriever

    @Before
    fun buildIndex() {
        index = IndexFixture.build(
            folder.newFile("wikipedia.db"),
            listOf(
                FixtureArticle(
                    "Tide",
                    listOf(
                        "Tides are the rise and fall of sea level resulting from the gravity of the Moon and the Sun.",
                        "Gauges measure the height of the water at a port over many years.",
                        "Kepler suggested that the Moon causes the tides. Galileo thought the tides were caused by the motion of the Earth.",
                    ),
                ),
                FixtureArticle("Sea kayaking", listOf("Currents change with the tides and can cause danger.")),
                FixtureArticle(
                    "Diffuse sky radiation",
                    listOf(
                        "Diffuse sky radiation is sunlight scattered by molecules in the atmosphere.",
                        "Blue light is scattered more than red light, so the sky looks blue.",
                    ),
                    aliases = listOf("Why is the sky blue", "Skylight"),
                ),
                FixtureArticle("Sky blue", listOf("Sky blue is a shade of light blue. The colour sky blue is used in web design.")),
            ),
        )
        retriever = Retriever(mapOf("wikipedia" to index))
    }

    @After
    fun closeIndex() = index.close()

    @Test
    fun theOpeningOfTheNamedArticleIsPutFirst() {
        val question = "What causes the tides?"
        val ranked = retriever.search(question)
        val withLeads = retriever.withLeads(question, ranked)

        assertEquals("Tide", withLeads.first().title)
        assertEquals(0, withLeads.first().seq)
        // Nothing is lost and nothing is repeated.
        assertEquals(withLeads.size, withLeads.map { it.passageId }.distinct().size)
        assertTrue(withLeads.map { it.passageId }.containsAll(ranked.map { it.passageId }))
    }

    @Test
    fun aQuestionThatIsItselfARedirectFindsTheArticleThatAnswersIt() {
        val question = "Why is the sky blue?"
        val ranked = retriever.search(question)
        assertEquals("Diffuse sky radiation", ranked.first().title)
        assertTrue(ranked.first().named)
        // The article named by the words inside the question is still found.
        assertTrue(ranked.any { it.title == "Sky blue" })

        val sources = SourceCompressor(60).select(question, retriever.withLeads(question, ranked))
        assertEquals("Diffuse sky radiation", sources.first().title)
        assertTrue("sunlight scattered by molecules" in sources.first().excerpt)
    }

    @Test
    fun theWholeQuestionIsLookedUpOnlyWhenItIsNotAlreadyAPlainName() {
        assertEquals("why is the sky blue", QueryBuilder.nameGrams("Why is the sky blue?").first().key)
        assertEquals(listOf("heart attack", "heart", "attack"), QueryBuilder.nameGrams("heart attack").map { it.key })
    }

    @Test
    fun articlesTheQuestionDoesNotNameGetNoOpeningAdded() {
        val question = "kayaking danger"
        val ranked = retriever.search(question)
        assertEquals(ranked.map { it.passageId }, retriever.withLeads(question, ranked).map { it.passageId })
    }
}
