package app.offlineresearch.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerTidyTest {
    private val label = PromptBuilder.UNSOURCED

    @Test
    fun sentencesThatOnlyRemarkOnTheSourcesAreRemoved() {
        // Taken from answers the 35B model wrote on the phone.
        val answer = "Brazil uses mains voltages of 127 V and 220 V [1][3]. " +
            "Regarding what 220 V is good for, the provided sources do not explicitly state specific uses or appliances for 220 V compared to 127 V. " +
            "They note that many devices support both voltages [3].\n\n" +
            "$label\n220 V is typically used for high-power appliances. " +
            "However, since the sources do not specify this, I cannot confirm if this distinction applies in Brazil."
        val tidy = Citations.dropSourceRemarks(answer)
        assertFalse(tidy, "do not explicitly state" in tidy)
        assertFalse(tidy, "I cannot confirm" in tidy)
        assertTrue(tidy, "Brazil uses mains voltages of 127 V and 220 V [1][3]. The sources note that many devices support both voltages [3]." in tidy)
        assertTrue(tidy, tidy.endsWith("220 V is typically used for high-power appliances."))
        assertTrue(Citations.hasUnsourced(tidy))
    }

    @Test
    fun theSentenceAfterARemovedOneIsMadeToStandAlone() {
        // The openings of two answers from the benchmark run of build 0.8.4.
        val list = "The offline sources do not provide a ranked list of the best vegan restaurants near you. " +
            "However, they list several vegan restaurants in Denver with their details.\n\nFrom Overture Maps: Radish [1]."
        assertEquals(
            "The sources list several vegan restaurants in Denver with their details.\n\nFrom Overture Maps: Radish [1].",
            Citations.dropSourceRemarks(list),
        )
        val traffic = "The sources do not state which side of the road Thailand drives on. " +
            "They only mention that motorbikes often drive on the wrong side of the road [1]."
        assertEquals(
            "The sources only mention that motorbikes often drive on the wrong side of the road [1].",
            Citations.dropSourceRemarks(traffic),
        )
    }

    @Test
    fun aRemarkThatCitesASourceOrIsAllThereIsStays() {
        val cited = "The sources do not give a price, but [2] says entry was free in 2019."
        assertEquals(cited, Citations.dropSourceRemarks(cited))
        val only = "The provided sources do not contain information about this."
        assertEquals(only, Citations.dropSourceRemarks(only))
        val plain = "Tides are caused by the Moon [1]. Open sources of water should be boiled."
        assertEquals(plain, Citations.dropSourceRemarks(plain))
    }

    @Test
    fun aSentenceStillBeingWrittenIsLeftAlone() {
        val streaming = "Tides are caused by the Moon [1]. The sources do not specify the height of"
        assertEquals(streaming, Citations.dropSourceRemarks(streaming))
    }

    @Test
    fun sourceNumbersBelowTheUnsourcedLineAreRemoved() {
        val answer = "Naismith's rule gives 5.6 hours [6].\n\n$label\nIt assumes reasonable fitness [6] and normal conditions [6][7]."
        assertEquals(
            "Naismith's rule gives 5.6 hours [6].\n\n$label\nIt assumes reasonable fitness and normal conditions.",
            Citations.dropCitationsUnderUnsourced(answer),
        )
    }

    @Test
    fun anAnswerThatOpensWithTheLineKeepsItsSourceNumbers() {
        val mislabelled = "$label If someone is choking, ask them to cough.\n\n[1][2]"
        assertEquals(mislabelled, Citations.dropCitationsUnderUnsourced(mislabelled))
    }

    @Test
    fun tidyAppliesAllOfThem() {
        val answer = "Yes [1].\n${PromptBuilder.NOT_COVERED}\nThe sources do not mention prices at all.\n\n$label\nIt is usually cheap [2]."
        assertEquals("Yes [1].\n\n$label\nIt is usually cheap.", Citations.tidy(answer))
    }
}
