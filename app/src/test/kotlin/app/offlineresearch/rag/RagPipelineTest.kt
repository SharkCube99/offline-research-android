package app.offlineresearch.rag

import app.offlineresearch.engine.EngineConfig
import app.offlineresearch.engine.EngineException
import app.offlineresearch.engine.EngineMetrics
import app.offlineresearch.engine.GenerationRequest
import app.offlineresearch.engine.InferenceEngine
import app.offlineresearch.engine.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An engine that replies with fixed fragments and records what it was asked. */
private class FakeEngine(private val reply: List<String>, private val fail: Boolean = false) : InferenceEngine {
    val requests = mutableListOf<GenerationRequest>()

    override suspend fun load(modelPath: String, config: EngineConfig) = Unit

    override fun generate(request: GenerationRequest): Flow<String> = flow {
        requests += request
        if (fail) throw EngineException("boom")
        reply.forEach { emit(it) }
    }

    override fun countTokens(text: String) = text.split(' ').size
    override fun cancel() = Unit
    override fun metrics() = EngineMetrics(0.0, 10, 1.0, 1.0, reply.size, 1.0, 4, StopReason.EOS)
    override fun systemInfo() = "fake"
    override suspend fun unload() = Unit
}

class RagPipelineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var index: SqlDatabase

    private val settings = AnswerSettings("/no_think", 256, 0.7f, 20, 0.8f, 1.5f)

    @Before
    fun buildIndex() {
        index = IndexFixture.build(
            folder.newFile("wikipedia.db"),
            listOf(
                FixtureArticle("Tide", listOf("Tides are caused by the gravity of the Moon and the Sun acting on the oceans.")),
                FixtureArticle("Moon", listOf("The Moon is the only natural satellite of Earth.")),
                FixtureArticle("Polaris", listOf("Polaris is the star closest to the north celestial pole."), aliases = listOf("North Star")),
            ),
        )
    }

    @After
    fun closeIndex() = index.close()

    private fun pipeline(answerer: FakeEngine, planner: QueryPlanner = KeywordPlanner, budget: Int = 200) = RagPipeline(
        planner, Retriever(mapOf("wikipedia" to index)), ContextBudgeter(budget, countTokens = answerer::countTokens),
        answerer, settings, seed = { 7 },
    )

    private fun run(pipeline: RagPipeline, question: String) = runBlocking { pipeline.answer(question).toList() }

    @Test
    fun stagesSourcesAndTokensArriveInOrder() {
        val answerer = FakeEngine(listOf("Tides come from ", "the Moon [1]."))
        val events = run(pipeline(answerer), "What causes the tides?")

        assertEquals(
            listOf(RagStage.PLANNING, RagStage.SEARCHING, RagStage.THINKING, RagStage.ANSWERING),
            events.filterIsInstance<RagEvent.Stage>().map { it.stage },
        )
        val sources = events.filterIsInstance<RagEvent.Sources>().single().sources
        assertEquals("Tide", sources.first().title)
        // Sources are announced before the model starts, so citations can resolve while streaming.
        assertTrue(events.indexOfFirst { it is RagEvent.Sources } < events.indexOf(RagEvent.Stage(RagStage.THINKING)))
        assertEquals("Tides come from the Moon [1].", events.filterIsInstance<RagEvent.Token>().joinToString("") { it.text })
        assertTrue(events.last() is RagEvent.Finished)
    }

    @Test
    fun theAnswererSeesNumberedSourcesTheQuestionAndTheContract() {
        val answerer = FakeEngine(listOf("ok"))
        run(pipeline(answerer), "What causes the tides?")

        val request = answerer.requests.single()
        assertTrue("[1] Tide: Tides are caused" in request.userPrompt)
        assertTrue(request.userPrompt.trimEnd().endsWith("Question: What causes the tides?"))
        assertTrue(PromptBuilder.NOT_COVERED in request.systemPrompt)
        assertTrue(request.systemPrompt.endsWith("/no_think"))
        assertEquals(256, request.maxTokens)
        assertEquals(7, request.seed)
    }

    @Test
    fun nothingRetrievedGivesNotCoveredWithoutRunningTheModel() {
        val answerer = FakeEngine(listOf("should not be used"))
        val events = run(pipeline(answerer), "xylophone quizzical")

        assertTrue(answerer.requests.isEmpty())
        assertEquals(PromptBuilder.NOT_COVERED, events.filterIsInstance<RagEvent.Token>().single().text)
        val report = (events.last() as RagEvent.Finished).report
        assertEquals(0, report.sources)
        assertNull(report.answerer)
    }

    @Test
    fun plannerQueriesAreSearchedAsWellAsTheQuestion() {
        val planner = object : QueryPlanner {
            override suspend fun plan(question: String) = Plan(listOf("north star"), usedFallback = false)
        }
        val events = run(pipeline(FakeEngine(listOf("ok")), planner), "Which star shows where north is?")

        val report = (events.last() as RagEvent.Finished).report
        assertEquals(listOf("Which star shows where north is?", "north star"), report.queries)
        assertFalse(report.plannerFallback)
        assertTrue(events.filterIsInstance<RagEvent.Sources>().single().sources.any { it.title == "Polaris" })
    }

    @Test
    fun aPlannerQueryEqualToTheQuestionIsSearchedOnce() {
        val planner = object : QueryPlanner {
            override suspend fun plan(question: String) = Plan(listOf("  WHAT CAUSES THE TIDES? "), usedFallback = false)
        }
        val events = run(pipeline(FakeEngine(listOf("ok")), planner), "What causes the tides?")
        assertEquals(1, (events.last() as RagEvent.Finished).report.queries.size)
    }

    @Test
    fun sourcesStayWithinTheTokenBudget() {
        // Each fixture passage is about 10 to 16 words; a 20-word budget fits one.
        val events = run(pipeline(FakeEngine(listOf("ok")), budget = 20), "moon tides")
        assertEquals(1, events.filterIsInstance<RagEvent.Sources>().single().sources.size)
    }

    @Test
    fun llmPlannerUsesModelOutput() = runBlocking {
        val plan = LlmPlanner(FakeEngine(listOf("Polaris\n", "Celestial navigation")), "/no_think").plan("How do I find north?")
        assertEquals(Plan(listOf("Polaris", "Celestial navigation"), usedFallback = false), plan)
    }

    @Test
    fun llmPlannerFallsBackToTheQuestionOnUnusableOutput() = runBlocking {
        val plan = LlmPlanner(FakeEngine(listOf("<think>hmm")), "").plan("How do I find north?")
        assertEquals(Plan(listOf("How do I find north?"), usedFallback = true), plan)
    }

    @Test
    fun llmPlannerFallsBackWhenTheEngineFails() = runBlocking {
        val plan = LlmPlanner(FakeEngine(emptyList(), fail = true), "").plan("How do I find north?")
        assertTrue(plan.usedFallback)
    }

    @Test
    fun llmPlannerAsksGreedilyForAShortReply() = runBlocking {
        val engine = FakeEngine(listOf("Polaris"))
        LlmPlanner(engine, "/no_think").plan("How do I find north?")
        val request = engine.requests.single()
        assertEquals(0f, request.temperature)
        assertEquals(LlmPlanner.MAX_TOKENS, request.maxTokens)
        assertTrue(request.userPrompt.endsWith("How do I find north?"))
    }
}
