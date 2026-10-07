package app.offlineresearch.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HereTest {
    private val gazetteer = Gazetteer(
        listOf(
            Gazetteer.City("Denver", "United States", 39.7392, -104.9847, 715_522),
            Gazetteer.City("Aurora", "United States", 39.7294, -104.8319, 386_261),
            Gazetteer.City("Boulder", "United States", 40.0150, -105.2705, 108_250),
            Gazetteer.City("Alert", "Canada", 82.5018, -62.3481, 20_000),
        ),
    )
    private val denver = Place("Denver", "United States", 0.5)

    @Test
    fun questionsAboutHereAreRecognised() {
        for (question in listOf(
            "Tell me the best vegan restaurants near me",
            "Is there a pharmacy nearby?",
            "Where is the nearest hospital?",
            "Any good coffee around here?",
            "Where am I?",
            "What hostels are in my area",
            "Where can I find the closest ATM",
            "Tell me the best vegan restaurants in the city I am currently in",
            "What is there to see in the town I'm staying in?",
        )) {
            assertTrue(question, Here.asksAbout(question))
        }
    }

    @Test
    fun questionsThatOnlySoundLikeItAreNot() {
        for (question in listOf(
            "I just got bitten by a snake while hiking, two hours from the nearest road. What do I do right now?",
            "Which planet is nearest to the Sun?",
            "Tell me the best vegan restaurants in Berlin",
            "What is the closest star to Earth?",
            "Why is the sky blue?",
        )) {
            assertFalse(question, Here.asksAbout(question))
        }
    }

    @Test
    fun theCityIsWrittenIntoTheQuestion() {
        assertEquals("Tell me the best vegan restaurants in Denver", Here.inPlace("Tell me the best vegan restaurants near me", denver))
        assertEquals("Is there a pharmacy in Denver?", Here.inPlace("Is there a pharmacy nearby?", denver))
        assertEquals("Where is the nearest hospital in Denver?", Here.inPlace("Where is the nearest hospital?", denver))
        assertEquals("Where am I?", Here.inPlace("Where am I?", denver))
        assertEquals(
            "Tell me the best vegan restaurants in Denver",
            Here.inPlace("Tell me the best vegan restaurants in the city I am currently in", denver),
        )
        assertEquals("Denver, United States", Here.describe(denver))
    }

    @Test
    fun aPositionBelongsToTheLargestCityCloseByOrElseTheNearest() {
        // Downtown Denver: Aurora is within 15 km too, but Denver is larger.
        assertEquals("Denver", gazetteer.nearest(Position(39.7392, -104.9903))!!.city)
        // Halfway to Boulder, more than 15 km from all three: the nearest wins.
        assertEquals("Boulder", gazetteer.nearest(Position(39.93, -105.20))!!.city)
        // The open ocean has no city within 50 km.
        assertNull(gazetteer.nearest(Position(0.0, -140.0)))
        // Near the poles a degree of longitude is short; the search still reaches.
        assertEquals("Alert", gazetteer.nearest(Position(82.45, -60.0))!!.city)
    }

    @Test
    fun positionsAreReadFromText() {
        assertEquals(Position(39.7392, -104.9903), Position.parse("39.7392,-104.9903"))
        assertEquals(Position(39.7392, -104.9903), Position.parse(" 39.7392 , -104.9903 "))
        assertNull(Position.parse("Denver"))
        assertNull(Position.parse("95.0,10.0"))
    }

    @Test
    fun theBundledCitiesFileIsReadAndFindsACity() {
        val file = listOf("src/main/assets/cities.tsv", "app/src/main/assets/cities.tsv").map(::File).first { it.isFile }
        val cities = file.bufferedReader().use(Gazetteer::read)
        assertTrue(cities.size > 25_000)
        assertEquals("Denver", cities.nearest(Position(39.7392, -104.9903))!!.city)
        assertEquals("Nairobi", cities.nearest(Position(-1.2864, 36.8172))!!.city)
    }
}
