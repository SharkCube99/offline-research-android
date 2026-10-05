package app.offlineresearch.rag

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Converts the quantities a question mentions ("7.5 liters per 100 km",
 * "104°F", "5,000 mAh at 3.85 V") into their usual counterparts, exactly.
 * The result is given to the answerer as one more numbered source, so that it
 * does not have to recall a conversion factor or decide whether to multiply or
 * divide, which is where a small model goes wrong.
 */
object UnitConverter {
    private const val NUMBER = """(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)"""
    private val MATH = MathContext(12, RoundingMode.HALF_UP)

    /** One kind of quantity: how it is written, and what it is converted into. */
    private class Rule(units: String, val convert: (BigDecimal) -> List<String>) {
        val pattern = Regex("""(?<![\w.,])$NUMBER[ \t]?(?:$units)(?![\w/])""", RegexOption.IGNORE_CASE)
    }

    private fun times(value: BigDecimal, factor: String) = value.multiply(BigDecimal(factor), MATH)

    private fun over(numerator: String, value: BigDecimal) = BigDecimal(numerator).divide(value, MATH)

    // Longer forms first: "liters per 100 km" must be read before "liters" and "km".
    private val RULES = listOf(
        Rule("""(?:l|lit(?:er|re)s?)[ \t]?(?:/|per)[ \t]?100[ \t]?(?:km|kilomet(?:er|re)s?)""") {
            if (it.signum() == 0) emptyList()
            else listOf("${show(it)} L/100 km = ${show(over("235.214583", it))} miles per US gallon = ${show(over("282.480936", it))} miles per imperial gallon = ${show(over("100", it))} km per liter")
        },
        Rule("""(?:mpg|miles per (?:us )?gallon)""") {
            if (it.signum() == 0) emptyList()
            else listOf("${show(it)} miles per US gallon = ${show(over("235.214583", it))} L/100 km")
        },
        Rule("""(?:km/h|kph|kilomet(?:er|re)s per hour)""") { listOf("${show(it)} km/h = ${show(times(it, "0.621371192"))} mph") },
        Rule("""(?:mph|miles per hour)""") { listOf("${show(it)} mph = ${show(times(it, "1.609344"))} km/h") },
        Rule("""(?:°[ \t]?f|degrees?[ \t]f(?:ahrenheit)?|fahrenheit)""") {
            listOf("${show(it)} °F = ${show((it - BigDecimal(32)).multiply(BigDecimal(5), MATH).divide(BigDecimal(9), MATH))} °C")
        },
        Rule("""(?:°[ \t]?c|degrees?[ \t]c(?:elsius)?|celsius|centigrade)""") {
            listOf("${show(it)} °C = ${show(it.multiply(BigDecimal(9), MATH).divide(BigDecimal(5), MATH) + BigDecimal(32))} °F")
        },
        Rule("""(?:km|kilomet(?:er|re)s?)""") { listOf("${show(it)} km = ${show(times(it, "0.621371192"))} miles") },
        Rule("""(?:mi|miles?)""") { listOf("${show(it)} miles = ${show(times(it, "1.609344"))} km") },
        Rule("""(?:m|met(?:er|re)s?)""") { listOf("${show(it)} m = ${show(times(it, "3.2808399"))} feet") },
        Rule("""(?:ft|feet|foot)""") { listOf("${show(it)} feet = ${show(times(it, "0.3048"))} m") },
        Rule("""(?:cm|centimet(?:er|re)s?)""") { listOf("${show(it)} cm = ${show(times(it, "0.393700787"))} inches") },
        Rule("""(?:in|inch(?:es)?)""") { listOf("${show(it)} inches = ${show(times(it, "2.54"))} cm") },
        Rule("""(?:kg|kilos?|kilograms?)""") { listOf("${show(it)} kg = ${show(times(it, "2.20462262"))} pounds") },
        Rule("""(?:lbs?|pounds?)""") { listOf("${show(it)} pounds = ${show(times(it, "0.45359237"))} kg") },
        Rule("""(?:oz|ounces?)""") { listOf("${show(it)} ounces = ${show(times(it, "28.3495231"))} g") },
        Rule("""(?:l|lit(?:er|re)s?)""") {
            listOf("${show(it)} liters = ${show(times(it, "0.264172052"))} US gallons; ${show(it)} liters of water weigh ${show(it)} kg")
        },
        Rule("""(?:us )?gal(?:lons?)?""") { listOf("${show(it)} US gallons = ${show(times(it, "3.78541178"))} liters") },
    )

    private val CAPACITY = Regex("""(?<![\w.,])$NUMBER[ \t]?mah\b""", RegexOption.IGNORE_CASE)
    private val VOLTAGE = Regex("""(?<![\w.,])$NUMBER[ \t]?(?:v|volts?)\b""", RegexOption.IGNORE_CASE)

    /** The conversions for the quantities in [question], one per line; empty when it holds none. */
    fun conversions(question: String): List<String> {
        val out = mutableListOf<String>()
        val capacity = CAPACITY.find(question)
        val voltage = VOLTAGE.find(question)
        if (capacity != null && voltage != null) {
            val mah = number(capacity)
            val volts = number(voltage)
            out += "${show(mah)} mAh at ${show(volts)} V = ${show(mah.multiply(volts, MATH).movePointLeft(3))} Wh (mAh × V / 1000)"
        }
        // A quantity is read once, by the first rule that matches it.
        val taken = mutableListOf<IntRange>()
        for (rule in RULES) {
            for (match in rule.pattern.findAll(question)) {
                if (taken.any { it.first <= match.range.last && match.range.first <= it.last }) continue
                taken += match.range
                out += rule.convert(number(match))
            }
        }
        return out.distinct().take(MAX_LINES)
    }

    /** The conversions as a source the answer can cite, or null when the question holds no quantity. */
    fun source(question: String): Passage? {
        val lines = conversions(question)
        if (lines.isEmpty()) return null
        val text = "$TITLE: " + lines.joinToString(". ") + "."
        return Passage(CORPUS, 0, TITLE, "computed on this phone", 0, text)
    }

    const val CORPUS = "calculator"
    const val TITLE = "Calculator (exact conversions of the numbers in the question)"
    private const val MAX_LINES = 4

    private fun number(match: MatchResult) = match.groupValues[1].replace(",", "").toBigDecimal()

    /** Four significant digits at least, whole numbers in full, no trailing zeros. */
    private fun show(value: BigDecimal): String {
        val digits = maxOf(4, value.precision() - value.scale())
        val rounded = value.round(MathContext(digits, RoundingMode.HALF_UP))
        val plain = rounded.stripTrailingZeros()
        return (if (plain.scale() < 0) plain.setScale(0) else plain).toPlainString()
    }
}
