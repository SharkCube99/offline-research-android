package app.offlineresearch

import app.offlineresearch.engine.BmoeEvent
import app.offlineresearch.engine.BmoeProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BmoeProtocolTest {

    @Test
    fun aRequestIsOneLineOfJsonWithThePromptEscaped() {
        val line = BmoeProtocol.generate(7, "Sources:\n[1] He said \"tides\" — 潮\n\nQuestion: why?", 256, think = false)
        assertFalse('\n' in line)
        val request = Json.parseToJsonElement(line).jsonObject
        assertEquals("generate", request.getValue("cmd").jsonPrimitive.content)
        assertEquals(7, request.getValue("id").jsonPrimitive.int)
        assertEquals("Sources:\n[1] He said \"tides\" — 潮\n\nQuestion: why?", request.getValue("prompt").jsonPrimitive.content)
        assertEquals(256, request.getValue("n_predict").jsonPrimitive.int)
        assertFalse(request.getValue("think").jsonPrimitive.boolean)
        assertTrue(request.getValue("clear_kv").jsonPrimitive.boolean)
    }

    @Test
    fun readyCarriesLoadTimeAndModelFacts() {
        val event = BmoeProtocol.parse("""BMOE_READY {"load_s":12.5,"arch":"qwen35moe","n_ctx":4096,"think_ctl":"template","n_expert_used":8}""")
        assertEquals(BmoeEvent.Ready(12.5, "qwen35moe", 4096, "template"), event)
    }

    @Test
    fun progressCarriesNewAnswerTextAndLinesWithoutTextAreSkipped() {
        assertEquals(
            BmoeEvent.Progress("Tides \"rise\"\n", reset = false),
            BmoeProtocol.parse("""BMOE_PROGRESS {"tok":3,"wall_ms":210.5,"delta_text":"Tides \"rise\"\n","delta_reasoning":""}"""),
        )
        assertNull(BmoeProtocol.parse("""BMOE_PROGRESS {"tok":1,"delta_text":"","delta_reasoning":"thinking"}"""))
        assertEquals(
            BmoeEvent.Progress("whole answer", reset = true),
            BmoeProtocol.parse("""BMOE_PROGRESS {"tok":9,"reset":1,"delta_text":"whole answer"}"""),
        )
    }

    @Test
    fun doneCarriesTheTimingsTheMetricsLogNeeds() {
        val event = BmoeProtocol.parse(
            """BMOE_DONE {"id":7,"cancelled":false,"tokens":120,"tok_s":4.8,"prefill_s":14.2,"prefill_tps":35.1,""" +
                """"load_s":12.5,"n_prompt":498,"n_past":618,"reasoning":"","text":"Tides [1]."}""",
        )
        assertEquals(BmoeEvent.Done(7, false, 120, 4.8, 14.2, 498, "Tides [1]."), event)
    }

    @Test
    fun errorsAndUnknownLinesAreToldApart() {
        assertEquals(
            BmoeEvent.Error(3, fatal = false, message = "prompt exceeds n_ctx"),
            BmoeProtocol.parse("""BMOE_ERROR {"id":3,"fatal":false,"msg":"prompt exceeds n_ctx"}"""),
        )
        assertEquals(BmoeEvent.Begin(3), BmoeProtocol.parse("""BMOE_BEGIN {"id":3}"""))
        assertNull(BmoeProtocol.parse("=== answer ==="))
        assertNull(BmoeProtocol.parse("BMOE_LOAD {\"frac\":0.5}"))
        assertNull(BmoeProtocol.parse("BMOE_DONE {not json"))
    }
}
