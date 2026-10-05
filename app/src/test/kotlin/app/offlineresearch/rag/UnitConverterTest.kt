package app.offlineresearch.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitConverterTest {
    @Test
    fun fuelUseIsReadWholeAndNotAsLitersAndKilometers() {
        val lines = UnitConverter.conversions("My rental car uses 7.5 liters per 100 km. What is that in miles per US gallon?")
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("7.5 L/100 km = 31.36 miles per US gallon = 37.66 miles per imperial gallon"))
    }

    @Test
    fun temperatureBatteryDistanceAndWeight() {
        assertEquals(listOf("104 °F = 40 °C"), UnitConverter.conversions("It's 104°F outside. What is that in Celsius?"))
        assertEquals(listOf("0 °C = 32 °F"), UnitConverter.conversions("Is 0 degrees Celsius freezing?"))
        assertEquals(
            listOf("5000 mAh at 3.85 V = 19.25 Wh (mAh × V / 1000)"),
            UnitConverter.conversions("My power bank is 5,000 mAh at 3.85 V. How many watt-hours is that?"),
        )
        assertEquals(
            listOf("18 km = 11.18 miles", "1200 m = 3937 feet"),
            UnitConverter.conversions("A hike is 18 km with 1,200 m of total climbing."),
        )
        assertEquals(listOf("23 kg = 50.71 pounds"), UnitConverter.conversions("Is a 23 kg suitcase allowed?"))
        assertTrue(UnitConverter.conversions("each person needs 3 liters of water per day").single().endsWith("3 liters of water weigh 3 kg"))
    }

    @Test
    fun wordsThatOnlyLookLikeUnitsAreNotConverted() {
        for (question in listOf(
            "What does EIP-7702 let an account do?",
            "Who won in 1066?",
            "I have 3 friends in Milan",
            "Is 5 minutes enough?",
            "What changed in version 2.5 million years ago?",
        )) {
            assertEquals(question, emptyList<String>(), UnitConverter.conversions(question))
        }
        assertNull(UnitConverter.source("Why is the sky blue?"))
    }

    @Test
    fun theSourceCanBeCitedLikeAnyOther() {
        val source = UnitConverter.source("How far is 10 miles in km?")!!
        assertEquals("calculator", source.corpus)
        assertTrue(source.text, "10 miles = 16.09 km" in source.text)
        assertTrue(PromptBuilder.user("How far is 10 miles in km?", listOf(source)).contains("[1] Calculator"))
    }
}
