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

        // Both named articles are offered; the one the whole question points at comes first.
        val sources = SourceCompressor(120).select(question, retriever.withLeads(question, ranked))
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

class StrictPackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var general: SqlDatabase
    private lateinit var pack: SqlDatabase
    private lateinit var retriever: Retriever

    @Before
    fun buildIndexes() {
        general = IndexFixture.build(
            folder.newFile("wikipedia.db"),
            listOf(FixtureArticle("Tide", listOf("Tides are caused by the gravity of the Moon and the Sun."))),
        )
        pack = IndexFixture.build(
            folder.newFile("ethereum.db"),
            listOf(
                FixtureArticle(
                    "EIP-7702: Set Code for EOAs",
                    listOf("EIP-7702 lets an externally owned account set code. A bug in a wallet causes loss of funds."),
                    aliases = listOf("EIP-7702", "EIP 7702"),
                ),
                FixtureArticle("Vegan restaurants in Berlin", listOf("Kopps is a fully vegan restaurant."), aliases = listOf("vegan restaurants in Berlin")),
            ),
            meta = mapOf("match" to "strict"),
        )
        retriever = Retriever(linkedMapOf("ethereum" to pack, "wikipedia" to general))
    }

    @After
    fun closeIndexes() {
        general.close()
        pack.close()
    }

    @Test
    fun aStrictPackStaysOutWhenOnlySomeOfTheQuestionsWordsMatch() {
        // "causes" appears in the pack, "tides" does not: a general index would relax to either word.
        val results = retriever.search("What causes the tides?")
        assertEquals(listOf("wikipedia"), results.map { it.corpus }.distinct())
    }

    @Test
    fun aStrictPackIsFoundByTwoPhrasesOfTheQuestionWithWordsBetween() {
        assertTrue("vegan berlin" in QueryBuilder.splitNames("Where can I find good vegan food when I am in Berlin?").map { it.second })
        val places = IndexFixture.build(
            folder.newFile("cityplaces.db"),
            listOf(
                FixtureArticle(
                    "Pharmacies in Nairobi",
                    listOf("Goodlife is a pharmacy; address: Moi Avenue."),
                    aliases = listOf("pharmacy nairobi", "pharmacies in nairobi"),
                ),
            ),
            meta = mapOf("match" to "strict"),
        )
        try {
            val found = Retriever(linkedMapOf("cityplaces" to places, "wikipedia" to general))
                .search("Is there a pharmacy open late in central Nairobi?")
            assertEquals("Pharmacies in Nairobi", found.first().title)
            assertTrue(found.first().named)
        } finally {
            places.close()
        }
    }

    @Test
    fun aStrictPackAnswersWhenItsArticleIsNamed() {
        assertEquals("EIP-7702: Set Code for EOAs", retriever.search("What does EIP-7702 let an account do?").first().title)
        val places = retriever.search("Tell me the best vegan restaurants in Berlin")
        assertEquals("Vegan restaurants in Berlin", places.first().title)
        assertTrue(places.first().named)
    }
}

class PlannerSearchTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var index: SqlDatabase
    private lateinit var retriever: Retriever
    private val question = "My hiking partner is shivering, confused and slurring words in the cold. What should I do?"

    @Before
    fun buildIndex() {
        index = IndexFixture.build(
            folder.newFile("wikipedia.db"),
            listOf(
                FixtureArticle(
                    "Hypothermia",
                    listOf(
                        "Hypothermia is a body core temperature below 35 degrees.",
                        "In mild hypothermia there is shivering and mental confusion. Move the person to shelter and warm them slowly.",
                    ),
                ),
                FixtureArticle("Hiking", listOf("Hiking is a long walk in the countryside. A partner makes hiking safer.")),
                FixtureArticle("Doppler ultrasonography", listOf("Doppler ultrasonography images the movement of blood.")),
            ),
        )
        retriever = Retriever(mapOf("wikipedia" to index))
    }

    @After
    fun closeIndex() = index.close()

    @Test
    fun aSingleWordOfALongQuestionDoesNotMakeItsArticleTheSubject() {
        val terms = QueryBuilder.queryTerms(question)
        assertFalse(titleIsNamed("Hiking", terms))
        assertTrue(titleIsNamed("Tide", QueryBuilder.queryTerms("What causes the tides?")))
        assertFalse(retriever.search(question).first { it.title == "Hiking" }.named)
    }

    @Test
    fun formsOfAWordAreRecognisedAndDifferentWordsAreNot() {
        for ((a, b) in listOf("emergency" to "emergencies", "confused" to "confusion", "tide" to "tides", "causes" to "caused", "burn" to "burns")) {
            assertTrue("$a / $b", sameWord(a, b))
        }
        for ((a, b) in listOf("plug" to "plum", "state" to "station", "hotel" to "hot", "cold" to "colder".take(3))) {
            assertFalse("$a / $b", sameWord(a, b))
        }
    }

    @Test
    fun anArticleThePlannerNamesIsShownByItsPassagesThatMatchTheQuestion() {
        val found = retriever.search("Hypothermia", context = question)
        assertEquals("Hypothermia", found.first().title)
        assertTrue(found.first().named)
        // The passage about shivering and confusion, not the definition that the query alone would rank first.
        assertTrue("shivering" in found.first().text)
    }

    @Test
    fun anArticleThePlannerMadeUpThatSharesNothingWithTheQuestionIsLeftOut() {
        assertTrue(retriever.search("Doppler ultrasonography", context = question).none { it.named })
        // Searched on its own, with no question behind it, the same title is found.
        assertEquals("Doppler ultrasonography", retriever.search("Doppler ultrasonography").first().title)
    }
}

class ImpliedTermsTest {
    @Test
    fun aQuestionAboutGettingSomewhereImpliesTransport() {
        assertTrue("metro" in QueryBuilder.impliedTerms("How do I get from Lisbon airport to the city center?"))
        assertTrue("taxi" in QueryBuilder.impliedTerms("What is the best way to get to the old town?"))
        assertTrue(QueryBuilder.impliedTerms("What causes the tides?").isEmpty())
        assertTrue(QueryBuilder.impliedTerms("How do I get a visa for Japan?").isEmpty())
    }

    @Test
    fun bothSpellingsOfAWordAreSearched() {
        assertEquals("\"airport\" AND (\"center\" OR \"centre\")", QueryBuilder.match(listOf("airport", "center"), "AND"))
        assertEquals("(\"colour\" OR \"color\") OR \"sky\"", QueryBuilder.match(listOf("colour", "sky"), "OR"))
    }
}
