package app.offlineresearch.rag

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Checks the arithmetic a model writes. The answerer is asked to show each
 * calculation as an equation ("235.215 / 7.5 = 31.36"); this recomputes the
 * left side exactly and, where the stated result is wrong, puts the right one
 * next to it. A small model gets such sums wrong often; the check costs no
 * model time.
 *
 * It is deliberately cautious: an equation it cannot read, or one that may be
 * a unit conversion ("90 min / 2 = 0.75 h"), is left alone.
 */
object Calculator {
    private const val NUMBER = """\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"""

    // A unit after a number: "km", "km/h", "°C", "Wh". Never a lone "x", which is a times sign.
    private const val UNIT = """(?:[ \t]?(?!x\b)[A-Za-z°µ]{1,8}(?:/[A-Za-z]{1,8}){0,2}\b)?"""
    private const val OPERAND = """\(*[ \t]*(?:$NUMBER)$UNIT[ \t]*\)*"""
    // A plain hyphen is a minus sign only after a space: "3-4" is a range or a name.
    private const val OPERATOR = """(?:[ \t]*[+−–*×·÷/]|[ \t]+-|[ \t]*x(?=[ \t]*[\d(]))[ \t]*"""

    private val EQUATION = Regex(
        """(?<![\w.,/])($OPERAND(?:$OPERATOR$OPERAND)+)[ \t]*(=|≈|~|≅)[ \t]*((?:$NUMBER))(?![\d,]*\d)""",
    )

    // What LaTeX a model writes around its sums: $...$, \times, \text{ km}, \approx.
    // Every brace is escaped: Android's regex engine rejects a bare "}" that the desktop JVM accepts.
    private val LATEX_TEXT = Regex("""[ \t]*\\(?:text|mathrm)\{[ \t]*([^\}]*)\}""")
    private val LATEX_WORDS = listOf("\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\approx" to "≈", "\\," to " ", "\\ " to " ")

    // Stated and computed results that differ by one of these are a change of unit, not a mistake.
    private val UNIT_FACTORS = listOf(60.0, 3600.0, 1000.0, 100.0, 10.0, 24.0, 12.0, 7.0, 1_000_000.0)

    private val MATH = MathContext(20, RoundingMode.HALF_UP)

    /** One checked equation: where its stated result stands in the text, and the right value when it is wrong. */
    data class Finding(val expression: String, val stated: String, val correct: String, val range: IntRange)

    /** [answer] with LaTeX arithmetic written plainly, so that it reads on a phone and can be checked. */
    fun plain(answer: String): String {
        if ('\\' !in answer && '$' !in answer) return answer
        var text = LATEX_TEXT.replace(answer) { " " + it.groupValues[1] }
        for ((latex, sign) in LATEX_WORDS) text = text.replace(latex, sign)
        // Dollar signs that fence a formula go; a price ("$67.12") keeps its sign.
        return Regex("""\$(?=[^\n\$]*[=×÷≈][^\n\$]*\$)([^\n\$]+)\$""").replace(text) { it.groupValues[1] }
    }

    /** The equations in [text] whose stated result is wrong. */
    fun mistakes(text: String): List<Finding> = EQUATION.findAll(text).mapNotNull { match ->
        val expression = match.groupValues[1].trim()
        val approximate = match.groupValues[2] != "="
        val stated = match.groupValues[3]
        // An operator just before it means this is the tail of a longer sum that could not be read.
        val before = text.substring(0, match.range.first).trimEnd().lastOrNull()
        if (before != null && before in "*×·÷/+−–-") return@mapNotNull null
        if (mixesUnitsInASum(expression)) return@mapNotNull null
        val value = evaluate(expression) ?: return@mapNotNull null
        val said = stated.replace(",", "").toBigDecimal()
        if (agrees(value, said, approximate) || looksLikeUnitChange(value, said)) return@mapNotNull null
        Finding(expression, stated, format(value, said.scale()), match.groups[3]!!.range)
    }.toList()

    /**
     * [answer] with each wrong result followed by the right one. While the answer
     * is still streaming ([complete] false) only finished lines are checked, since
     * the last number may still be growing.
     */
    fun check(answer: String, complete: Boolean = true): String {
        val text = plain(answer)
        val end = if (complete) text.length else text.lastIndexOf('\n') + 1
        val found = mistakes(text.substring(0, end))
        if (found.isEmpty()) return text
        val out = StringBuilder(text)
        for (finding in found.asReversed()) {
            out.insert(finding.range.last + 1, " (calculator: ${finding.correct})")
        }
        return out.toString()
    }

    private fun agrees(value: BigDecimal, said: BigDecimal, approximate: Boolean): Boolean {
        // Right when it is the true value rounded or cut to the digits shown.
        val step = BigDecimal.ONE.movePointLeft(said.scale())
        if ((value - said).abs() < step) return true
        if (value.signum() == 0) return false
        // A last digit that differs is not worth a remark; "about" allows one percent.
        val relative = ((value - said).abs().divide(value.abs(), MATH)).toDouble()
        return relative <= if (approximate) 0.01 else 0.0001
    }

    private fun looksLikeUnitChange(value: BigDecimal, said: BigDecimal): Boolean {
        if (value.signum() == 0 || said.signum() == 0) return false
        val ratio = abs(value.toDouble() / said.toDouble())
        return UNIT_FACTORS.any { factor -> abs(ratio / factor - 1) < 0.02 || abs(ratio * factor - 1) < 0.02 }
    }

    private val TERM = Regex("""($NUMBER)($UNIT)""")

    /** "3 km + 500 m": adding or subtracting different units needs a conversion this cannot do. */
    private fun mixesUnitsInASum(expression: String): Boolean {
        if (!Regex("""[+\-−–]""").containsMatchIn(expression)) return false
        val units = TERM.findAll(expression).map { it.groupValues[2].trim() }.filter { it.isNotEmpty() }.toSet()
        return units.size > 1
    }

    private fun format(value: BigDecimal, statedDecimals: Int): String {
        val whole = value.setScale(0, RoundingMode.HALF_UP)
        val decimals = when {
            statedDecimals > 0 -> statedDecimals
            (value - whole).abs() < BigDecimal("0.0005") -> 0
            else -> 2
        }
        return value.setScale(decimals, RoundingMode.HALF_UP).toPlainString()
    }

    /** The value of an expression of numbers, + - × ÷ and brackets; null when it is not one. */
    fun evaluate(expression: String): BigDecimal? {
        val tokens = tokens(expression) ?: return null
        return try {
            val parser = Parser(tokens)
            val value = parser.sum()
            if (parser.done()) value else null
        } catch (e: ArithmeticException) {
            null // division by zero
        } catch (e: IllegalStateException) {
            null // brackets that do not match
        }
    }

    private val TOKEN = Regex("""($NUMBER)$UNIT|([+\-−–*×·÷/x()])|[ \t]+""")

    private fun tokens(expression: String): List<String>? {
        val out = mutableListOf<String>()
        var position = 0
        while (position < expression.length) {
            val match = TOKEN.matchAt(expression, position) ?: return null
            when {
                match.groupValues[1].isNotEmpty() -> out += match.groupValues[1].replace(",", "")
                match.groupValues[2].isNotEmpty() -> out += when (match.groupValues[2]) {
                    "−", "–" -> "-"
                    "×", "·", "x" -> "*"
                    "÷" -> "/"
                    else -> match.groupValues[2]
                }
            }
            position = match.range.last + 1
        }
        return out
    }

    private class Parser(private val tokens: List<String>) {
        private var position = 0

        fun done() = position == tokens.size

        private fun peek(): String? = tokens.getOrNull(position)

        fun sum(): BigDecimal {
            var value = product()
            while (peek() == "+" || peek() == "-") {
                val operator = tokens[position++]
                val right = product()
                value = if (operator == "+") value + right else value - right
            }
            return value
        }

        private fun product(): BigDecimal {
            var value = atom()
            while (peek() == "*" || peek() == "/") {
                val operator = tokens[position++]
                val right = atom()
                value = if (operator == "*") value.multiply(right, MATH) else value.divide(right, MATH)
            }
            return value
        }

        private fun atom(): BigDecimal {
            val token = tokens.getOrNull(position++) ?: error("unexpected end")
            if (token == "(") {
                val value = sum()
                if (tokens.getOrNull(position++) != ")") error("missing bracket")
                return value
            }
            return token.toBigDecimalOrNull() ?: error("not a number: $token")
        }
    }
}
