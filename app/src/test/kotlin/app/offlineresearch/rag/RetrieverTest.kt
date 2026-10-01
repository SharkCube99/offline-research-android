package app.offlineresearch.rag

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetrieverTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var indexes: Map<String, SqlDatabase>
    private lateinit var retriever: Retriever

    private val wikipedia = listOf(
        FixtureArticle(
            "Myocardial infarction",
            listOf(
                "A myocardial infarction occurs when blood flow to the heart muscle stops. Chest pain is the most common symptom.",
                "Symptoms that help recognise it include chest pain spreading to the arm, shortness of breath and cold sweat.",
                "Treatment includes aspirin and procedures that restore blood flow to the heart.",
            ),
            aliases = listOf("Heart attack", "Heart attacks", "MI"),
            popularity = 8e-6,
        ),
        FixtureArticle(
            "Heart Attack (song)",
            listOf("Heart Attack is a song. The song is about a heart attack of love, a heart attack in the heart, an attack."),
            popularity = 2e-7,
        ),
        FixtureArticle(
            "Vaccine",
            listOf("A vaccine is a preparation that trains the immune system to recognise a pathogen."),
            popularity = 5e-6,
        ),
        FixtureArticle(
            "Polio vaccine",
            listOf("Polio vaccines are vaccines used to prevent poliomyelitis. Vaccines vaccines vaccines."),
            popularity = 9e-7,
        ),
        FixtureArticle(
            "Volcano",
            listOf(
                "A volcano is a rupture in the crust that lets lava escape.",
                "Volcano eruptions can send ash high into the air.",
                "The largest volcano in the solar system is on Mars.",
                "A volcano can be active, dormant or extinct.",
            ),
        ),
        FixtureArticle("Granite", listOf("Granite is a coarse igneous rock formed from cooled magma deep under a volcano.")),
        FixtureArticle("Rome", listOf("Rome is the capital of Italy and was the centre of the Roman Empire."), popularity = 9e-6),
    )

    private val wikivoyage = listOf(
        FixtureArticle("Rome", listOf("Rome has sights such as the Colosseum, the Forum and the Pantheon. Buy tickets early."), popularity = 4e-5),
        FixtureArticle("Naples", listOf("Naples is a city south of Rome, close to the volcano Vesuvius and to Pompeii.")),
    )

    @Before
    fun buildIndexes() {
        indexes = linkedMapOf(
            "wikipedia" to IndexFixture.build(folder.newFile("wikipedia.db"), wikipedia),
            "wikivoyage" to IndexFixture.build(folder.newFile("wikivoyage.db"), wikivoyage),
        )
        retriever = Retriever(indexes)
    }

    @After
    fun closeIndexes() = indexes.values.forEach { it.close() }

    private fun titles(question: String, k: Int = 5) = retriever.search(question, k).map { "${it.corpus}:${it.title}" }

    @Test
    fun redirectNameFindsTheArticleEvenWhenAnotherArticleMatchesTheWordsBetter() {
        val hits = retriever.search("How do I recognise a heart attack?", 5)
        assertEquals("Myocardial infarction", hits.first().title)
    }

    @Test
    fun pluralQuestionWordFindsTheSingularTitle() {
        assertEquals("wikipedia:Vaccine", titles("How do vaccines work?").first())
    }

    @Test
    fun passageTextIsInflatedAndKeepsItsTitlePrefix() {
        val hit = retriever.search("How do vaccines work?", 1).single()
        assertTrue(hit.text.startsWith("Vaccine: A vaccine is a preparation"))
        assertEquals("https://example.org/wiki/Vaccine", hit.url)
        assertEquals(0, hit.seq)
    }

    @Test
    fun atMostTwoPassagesPerArticle() {
        val hits = retriever.search("volcano", 20)
        assertEquals(2, hits.count { it.title == "Volcano" })
        assertTrue(hits.any { it.title == "Granite" })
    }

    @Test
    fun bothCorporaContribute() {
        val found = titles("What are the sights in Rome?")
        assertTrue(found.toString(), "wikivoyage:Rome" in found)
        assertTrue(found.toString(), "wikipedia:Rome" in found)
    }

    @Test
    fun allWordsAreRequiredBeforeAnyAreDropped() {
        // Only Naples has all of "volcano", "pompeii" and "city". "Volcano" is also an
        // article name, so that article leads and Naples follows. Granite has just
        // one of the three words; only one word may be dropped, so it is not returned.
        val found = titles("volcano pompeii city", k = 10)
        assertEquals("wikipedia:Volcano", found.first())
        assertEquals("wikivoyage:Naples", found[1])
        assertFalse("wikipedia:Granite" in found)
    }

    @Test
    fun aMissingWordIsDroppedWhenNothingHasThemAll() {
        // No passage contains "zebra"; relaxing one word still finds the granite passage.
        assertTrue("wikipedia:Granite" in titles("granite magma zebra"))
    }

    @Test
    fun resultsAreLimitedToK() {
        assertEquals(2, retriever.search("volcano rome heart vaccine", 2).size)
    }

    @Test
    fun noPassageIsReturnedTwice() {
        val ids = retriever.search("rome volcano", 20).map { it.passageId }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun unknownWordsFindNothing() {
        assertTrue(retriever.search("xylophone quizzical", 5).isEmpty())
    }

    @Test
    fun questionWithNoWordsFindsNothing() {
        assertTrue(retriever.search("?!", 5).isEmpty())
    }

    @Test
    fun ftsSyntaxInAQuestionIsSearchedAsWords() {
        // Must not throw: quotes, operators and column filters are just text.
        assertFalse(retriever.search("title: \"volcano\" AND NOT (rome) NEAR*", 5).isEmpty())
    }
}
