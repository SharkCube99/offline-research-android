package app.offlineresearch.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {
    @Test
    fun evaluatesWithPrecedenceBracketsAndThousands() {
        assertEquals("40", Calculator.evaluate("(104 − 32) × 5/9")!!.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString())
        assertEquals(0, Calculator.evaluate("2,450 / 36.5")!!.compareTo(java.math.BigDecimal("67.123287671232876712")))
        assertEquals(0, Calculator.evaluate("3 x 2 x 3")!!.compareTo(java.math.BigDecimal(18)))
        assertEquals(0, Calculator.evaluate("18 km / 5 km/h")!!.compareTo(java.math.BigDecimal("3.6")))
        assertNull(Calculator.evaluate("5 / 0"))
        assertNull(Calculator.evaluate("(5 + 2"))
    }

    @Test
    fun rightSumsAreLeftAlone() {
        val answers = listOf(
            "235.215 / 7.5 = 31.362",
            "Calculation: 2,450 / 36.5 ≈ 67.123 USD",
            "5 Ah × 3.85 V = 19.25 Wh [3].",
            "Total time: 3.6 hours + 2 hours = 5.6 hours.",
            "C = 72 × 5/9\nC = 40",
            "2,450 / 36.5 = 67.12",   // rounded to the digits shown
            "2,450 / 36.5 = 67",
            "10 / 3 ≈ 3.3",
            "the total is 3 liters/person/day * 3 people * 2 days = 18 liters",
            "a rate of 3 per head, § * 3 people * 2 days = 18",   // the tail of a sum that cannot be read
            "7.5 * 235.214583 = 1764.109375",                     // a last digit apart
        )
        for (answer in answers) assertEquals(answer, answer, Calculator.check(answer))
    }

    @Test
    fun aWrongResultGetsTheRightOneBesideIt() {
        assertEquals("235.215 / 7.5 = 32.5 (calculator: 31.4) mpg", Calculator.check("235.215 / 7.5 = 32.5 mpg"))
        assertEquals("3 × 2 × 3 = 16 (calculator: 18) liters [2]", Calculator.check("3 × 2 × 3 = 16 liters [2]"))
        assertEquals("67.12 + 6.71 = 74.83 (calculator: 73.83)", Calculator.check("67.12 + 6.71 = 74.83"))
        assertEquals("2,450 / 36.5 = 71 (calculator: 67.12)", Calculator.check("2,450 / 36.5 = 71"))
    }

    @Test
    fun whatMayBeAUnitChangeIsLeftAlone() {
        for (answer in listOf("90 min / 2 = 0.75 h", "3 km + 500 m = 3.5 km", "2 × 30 min = 1 hour", "1,200 m × 3 = 3.6 km")) {
            assertEquals(answer, Calculator.check(answer))
        }
    }

    @Test
    fun textThatIsNotASumIsLeftAlone() {
        for (answer in listOf("1 USD = 36.5 THB", "In 2020-2021 = a hard year", "Call 191 or 1669.", "EIP-7702 = 3", "version 1.2.3 - 4 = x")) {
            assertEquals(answer, Calculator.check(answer))
        }
    }

    @Test
    fun latexIsWrittenPlainlyAndPricesKeepTheirSign() {
        val plain = Calculator.plain("""Time: $18 \text{ km} / 5 \text{ km/h} = 3.6$ hours, then $4 \times 0.5 = 2$. It costs $67.12.""")
        assertEquals("Time: 18 km / 5 km/h = 3.6 hours, then 4 × 0.5 = 2. It costs \$67.12.", plain)
        assertEquals("So 4 × 0.5 = 3 (calculator: 2) hours", Calculator.check("""So $4 \times 0.5 = 3$ hours"""))
    }

    @Test
    fun whileStreamingOnlyFinishedLinesAreChecked() {
        val partial = "3 × 2 = 7\n235.215 / 7.5 = 3"
        assertEquals("3 × 2 = 7 (calculator: 6)\n235.215 / 7.5 = 3", Calculator.check(partial, complete = false))
        assertTrue("(calculator: 31.36)" in Calculator.check(partial, complete = true))
    }
}
