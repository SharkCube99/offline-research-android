package app.offlineresearch.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the calculator over saved benchmark answers and prints what it would
 * change, so false alarms can be seen before a phone run. Set
 * OFFLINE_ANSWERS to one or more answers files (JSON lines with "id" and
 * "draft"), separated by ';'. Skipped otherwise.
 */
class CalculatorReportTest {
    @Test
    fun printWhatTheCalculatorChangesInSavedAnswers() {
        val files = System.getenv("OFFLINE_ANSWERS")?.split(';')?.map(::File)?.filter { it.isFile }.orEmpty()
        assumeTrue("OFFLINE_ANSWERS is not set", files.isNotEmpty())
        for (file in files) {
            var answers = 0
            var changed = 0
            println("== ${file.name}")
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val record = Json.parseToJsonElement(line).jsonObject
                val draft = record["draft"]?.jsonPrimitive?.content ?: return@forEachLine
                answers++
                val found = Calculator.mistakes(Calculator.plain(draft))
                if (found.isNotEmpty()) changed++
                for (finding in found) {
                    println("${record["id"]?.jsonPrimitive?.content}: ${finding.expression} = ${finding.stated} -> calculator: ${finding.correct}")
                }
            }
            println("$answers answers, $changed with a correction")
        }
    }
}
