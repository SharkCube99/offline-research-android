package app.offlineresearch.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.offlineresearch.engine.BmoeEngine
import app.offlineresearch.engine.EngineConfig
import app.offlineresearch.engine.EngineException
import app.offlineresearch.engine.EngineMetrics
import app.offlineresearch.engine.ExitLog
import app.offlineresearch.engine.InferenceEngine
import app.offlineresearch.engine.LlamaEngine
import app.offlineresearch.engine.MetricsLog
import app.offlineresearch.engine.RagRecord
import app.offlineresearch.engine.RunInfo
import app.offlineresearch.engine.StressTag
import app.offlineresearch.engine.SystemSnapshot
import app.offlineresearch.engine.SystemStats
import app.offlineresearch.engine.ThreadAffinity
import app.offlineresearch.profiles.ModelProfile
import app.offlineresearch.profiles.ProfileChoice
import app.offlineresearch.profiles.ProfileStore
import app.offlineresearch.rag.AnswerSettings
import app.offlineresearch.location.GpsLocator
import app.offlineresearch.rag.Calculator
import app.offlineresearch.rag.Gazetteer
import app.offlineresearch.rag.Place
import app.offlineresearch.rag.Position
import app.offlineresearch.rag.Citations
import app.offlineresearch.rag.ContextBudgeter
import app.offlineresearch.rag.IndexFiles
import app.offlineresearch.rag.KeywordPlanner
import app.offlineresearch.rag.LlmPlanner
import app.offlineresearch.rag.Passage
import app.offlineresearch.rag.QueryPlanner
import app.offlineresearch.rag.RagEvent
import app.offlineresearch.rag.RagPipeline
import app.offlineresearch.rag.RagReport
import app.offlineresearch.rag.RagStage
import app.offlineresearch.rag.Retriever
import app.offlineresearch.rag.SourceCompressor
import app.offlineresearch.rag.SqlDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import kotlin.random.Random

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    /** The numbered sources this answer may cite; `sources[0]` is source 1. */
    val sources: List<Passage> = emptyList(),
    /** What the pipeline is doing; null once the answer is complete. */
    val stage: RagStage? = null,
    /** The best search hit, shortened; shown while the answer is still being prepared. */
    val preview: Passage? = null,
)

sealed interface ModelStatus {
    data object Loading : ModelStatus

    data class Ready(
        val profile: String,
        /** Why this profile is active: chosen automatically, in settings, or pushed over adb. */
        val profileSource: String,
        val modelFile: String,
        val corpora: List<String>,
        /** Planner model file, or null when searching by keywords only. */
        val planner: String?,
    ) : ModelStatus

    /** A file the app needs has not been pushed to the device. */
    data class Missing(val what: String, val searched: List<String>) : ModelStatus

    data class Failed(val reason: String) : ModelStatus
}

data class ChatUiState(
    val status: ModelStatus = ModelStatus.Loading,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val metrics: EngineMetrics? = null,
    /** Memory, page faults and heat after the last answer. */
    val system: SystemSnapshot? = null,
    val profileChoice: ProfileChoice = ProfileChoice.AUTO,
    val totalRamGb: Double = 0.0,
    /** A profile.json pushed over adb is overriding the choice above. */
    val pushedOverride: Boolean = false,
    /** "3 of 20" while a stress test runs. */
    val stressProgress: String? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val nativeLibDir = application.applicationInfo.nativeLibraryDir
    private val llamaAnswerer: InferenceEngine = LlamaEngine(nativeLibDir, "llama-answerer")

    /** Replaced when the profile names a different engine. */
    private var answerer: InferenceEngine = llamaAnswerer
    private val plannerEngine: InferenceEngine = LlamaEngine(nativeLibDir, "llama-planner")
    private val profiles = ProfileStore(application)
    private val gps = GpsLocator(application)

    // About 30,000 cities; read on first use, off the main thread.
    private val gazetteer: Gazetteer by lazy {
        getApplication<Application>().assets.open(CITIES_ASSET).bufferedReader().use(Gazetteer::read)
    }

    /** A position given by a script (--es gps "lat,lon") instead of the receiver, for the next question only; used by the benchmark. */
    @Volatile
    private var scriptedPosition: Position? = null

    fun setScriptedPosition(position: Position?) {
        scriptedPosition = position
    }

    /** True when a question about "here" can be answered only after the user allows location. */
    fun needsLocationPermission(question: String): Boolean =
        scriptedPosition == null && !gps.hasPermission() && app.offlineresearch.rag.Here.asksAbout(question)

    private suspend fun locate(): Place? {
        val position = scriptedPosition ?: gps.current()
        if (position == null) {
            Log.w(MetricsLog.TAG, "no position: permission missing, location off, or no fix in time")
            return null
        }
        return withContext(Dispatchers.Default) { gazetteer.nearest(position) }
    }
    private val logDir = application.getExternalFilesDir(null)?.let { File(it, "logs") }
    private val metricsLog = MetricsLog(logDir)
    private val appVersion: String =
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "unknown"

    private val _state = MutableStateFlow(
        ChatUiState(profileChoice = profiles.choice, totalRamGb = profiles.totalRamBytes() / 1e9),
    )
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var profile: ModelProfile? = null
    private var pipeline: RagPipeline? = null
    private var plannerName = KEYWORDS
    private var indexes: Map<String, SqlDatabase> = emptyMap()
    private var pendingQuestion: String? = null
    private var pendingStress: Int? = null
    private var work: Job? = null
    private var loading: Job? = null

    init {
        // Why did the previous run end? Only Android knows, and only afterwards.
        viewModelScope.launch(Dispatchers.IO) { ExitLog.recordNew(application, logDir) }
        loadModel()
    }

    fun loadModel() {
        if (_state.value.generating) return
        profile = null
        pipeline = null
        _state.update {
            it.copy(status = ModelStatus.Loading, profileChoice = profiles.choice, pushedOverride = profiles.hasPushedOverride())
        }
        // Only one load at a time: a second request (a profile change arriving
        // while the first load runs) replaces the first.
        loading?.cancel()
        loading = viewModelScope.launch {
            val status = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(MetricsLog.TAG, "load failed", e)
                ModelStatus.Failed(e.message ?: e.toString())
            }
            val ready = status is ModelStatus.Ready
            _state.update {
                it.copy(
                    status = status,
                    metrics = if (ready) answerer.metrics() else null,
                    system = if (ready) SystemStats.snapshot(getApplication()) else null,
                )
            }
            if (ready) {
                pendingStress?.let { runStress(it) } ?: pendingQuestion?.let { send(it) }
            }
            pendingQuestion = null
            pendingStress = null
        }
    }

    /** Picks the profile to use from now on and reloads the models with it. */
    fun setProfileChoice(choice: ProfileChoice) {
        if (_state.value.generating || choice == profiles.choice) return
        profiles.choice = choice
        loadModel()
    }

    private suspend fun load(): ModelStatus {
        val loaded = withContext(Dispatchers.IO) { profiles.load() }
        val active = loaded.profile
        Log.i(MetricsLog.TAG, "profile: ${active.name} (${loaded.source})")
        val model = withContext(Dispatchers.IO) { profiles.findModel(active.modelFile) }
            ?: return ModelStatus.Missing(
                "Model file ${active.modelFile} (profile ${active.name}, ${loaded.source})",
                profiles.modelDirs().map { it.absolutePath },
            )
        val indexFiles = withContext(Dispatchers.IO) { IndexFiles.find(profiles.indexDirs()) }
        if (indexFiles.isEmpty()) {
            return ModelStatus.Missing("Knowledge index (*.db)", profiles.indexDirs().map { it.absolutePath })
        }

        fun config(contextSize: Int) = EngineConfig(
            contextSize = contextSize,
            batchSize = active.batchSize,
            threads = active.threads,
            batchThreads = active.batchThreads,
            useMmap = active.useMmap,
            useMlock = active.useMlock,
            repack = active.repack,
            chatTemplate = active.chatTemplate,
            threadAffinity = if (active.threadAffinity == "fastest") ThreadAffinity.FASTEST else ThreadAffinity.NONE,
        )
        // Free the old models first: two answerers do not fit in memory at once.
        plannerEngine.unload()
        answerer.unload()
        answerer = when (active.engine) {
            // Android only lets an app run programs from its native library
            // directory, so the engine is packaged there under a library's name.
            "bmoe" -> BmoeEngine("$nativeLibDir/libbmoe-cli.so", active.engineArgs, active.think)
            else -> llamaAnswerer
        }
        answerer.load(model.absolutePath, config(active.contextSize))
        Log.i(MetricsLog.TAG, "system info: ${answerer.systemInfo()}")

        // The planner is optional: without its model file, search uses the question's keywords.
        val plannerFile = active.planner?.let { planner ->
            withContext(Dispatchers.IO) { profiles.findModel(planner.modelFile) }
        }
        val planner: QueryPlanner = if (active.planner != null && plannerFile != null) {
            plannerEngine.load(plannerFile.absolutePath, config(active.planner.contextSize))
            LlmPlanner(plannerEngine, active.planner.promptSuffix)
        } else {
            if (active.planner != null) Log.w(MetricsLog.TAG, "planner model ${active.planner.modelFile} not found; using keywords")
            KeywordPlanner
        }
        plannerName = plannerFile?.name ?: KEYWORDS

        indexes.values.forEach { it.close() }
        indexes = withContext(Dispatchers.IO) { IndexFiles.open(indexFiles) }
        Log.i(MetricsLog.TAG, "index: ${indexFiles.map { "${it.key} (${it.value.length() / 1_000_000} MB)" }}, planner: $plannerName")

        profile = active
        val compressor = SourceCompressor(active.retrievalBudgetTokens, active.sourcePassages)
        pipeline = RagPipeline(
            planner = planner,
            retriever = Retriever(indexes),
            selector = if (active.compressSources) {
                compressor
            } else {
                ContextBudgeter(active.retrievalBudgetTokens, countTokens = answerer::countTokens)
            },
            answerer = answerer,
            settings = AnswerSettings(
                promptSuffix = active.promptSuffix,
                maxTokens = active.maxTokens,
                temperature = active.temperature,
                topK = active.topK,
                topP = active.topP,
                presencePenalty = active.presencePenalty,
                ownKnowledge = active.ownKnowledge,
                shortRules = active.answerRules == "short",
            ),
            seed = Random::nextInt,
            preview = compressor::preview,
            locate = ::locate,
        )
        return ModelStatus.Ready(active.name, loaded.source, active.modelFile, indexFiles.keys.toList(), plannerFile?.name)
    }

    /** Asks [question] now, or as soon as loading has finished. */
    fun ask(question: String) {
        if (pipeline == null) pendingQuestion = question else send(question)
    }

    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || pipeline == null || _state.value.generating) return
        _state.update { it.copy(generating = true) }
        work = viewModelScope.launch {
            try {
                answer(text, null)
            } finally {
                _state.update { it.copy(generating = false) }
            }
        }
    }

    /**
     * Stress test: asks [count] bundled questions one after another. Each
     * answer is logged with its position in the run, so a run that ends early
     * (crash, low-memory kill) is visible as a missing tail.
     */
    fun runStress(count: Int = DEFAULT_STRESS_COUNT) {
        if (pipeline == null) {
            pendingStress = count
            return
        }
        if (_state.value.generating) return
        val questions = getApplication<Application>().assets.open(STRESS_ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        val total = count.coerceIn(1, questions.size)
        val run = Instant.now().toString()
        Log.i(MetricsLog.TAG, "STRESS start run=$run total=$total")
        _state.update { it.copy(generating = true, stressProgress = "0 of $total") }
        work = viewModelScope.launch {
            var completed = 0
            try {
                for (index in 1..total) {
                    _state.update { it.copy(stressProgress = "$index of $total") }
                    answer(questions[index - 1], StressTag(run, index, total))
                    completed = index
                }
            } finally {
                Log.i(MetricsLog.TAG, "STRESS end run=$run completed=$completed total=$total")
                _state.update { it.copy(generating = false, stressProgress = null) }
            }
        }
    }

    /** Answers one question and logs it. Runs on the caller's coroutine; cancelling it stops the answer. */
    private suspend fun answer(question: String, stress: StressTag?) {
        val active = profile ?: return
        val rag = pipeline ?: return
        _state.update {
            it.copy(messages = it.messages + ChatMessage(true, question) + ChatMessage(false, "", stage = RagStage.SEARCHING))
        }
        val before = SystemStats.snapshot(getApplication())
        val raw = StringBuilder()
        var sources = emptyList<Passage>()
        var report: RagReport? = null
        var failure: String? = null
        try {
            rag.answer(question).collect { event ->
                when (event) {
                    is RagEvent.Stage -> updateReply { it.copy(stage = event.stage) }
                    is RagEvent.Preview -> updateReply { it.copy(preview = event.passage) }
                    is RagEvent.Sources -> {
                        sources = event.sources
                        updateReply { it.copy(sources = event.sources) }
                    }
                    is RagEvent.Token -> {
                        raw.append(event.text)
                        val visible = Calculator.check(Citations.dropStrayNotCovered(stripThinking(raw.toString())), complete = false)
                        updateReply { it.copy(text = visible) }
                    }
                    is RagEvent.Finished -> report = event.report
                }
            }
        } catch (e: EngineException) {
            failure = e.message
        } finally {
            // A script's position is for the one question it came with. Left in
            // place, it would answer the user's next "near me" for the wrong city.
            scriptedPosition = null
            withContext(NonCancellable) {
                val text = Calculator.check(Citations.dropStrayNotCovered(stripThinking(raw.toString())))
                updateReply { it.copy(stage = null, text = if (failure != null) "[error] $failure" else text) }
                val after = SystemStats.snapshot(getApplication())
                val finished = report
                if (failure == null && finished != null) {
                    withContext(Dispatchers.IO) { log(active, question, text, sources, finished, before, after, stress) }
                }
                _state.update { it.copy(metrics = finished?.answerer ?: it.metrics, system = after) }
            }
        }
    }

    private fun log(
        active: ModelProfile,
        question: String,
        answer: String,
        sources: List<Passage>,
        report: RagReport,
        before: SystemSnapshot,
        after: SystemSnapshot,
        stress: StressTag?,
    ) {
        metricsLog.record(
            report.answerer,
            RunInfo(active.name, active.modelFile, active.contextSize, active.repack, appVersion, active.threadAffinity, active.batchSize),
            before,
            after,
            RagRecord(
                question = question,
                answer = answer,
                planner = plannerName,
                plannerFallback = report.plannerFallback,
                queries = report.queries,
                planMs = report.planMs,
                searchMs = report.searchMs,
                totalTimeToFirstTokenMs = report.planMs + report.searchMs + (report.answerer?.timeToFirstTokenMs ?: 0.0),
                retrieved = report.retrieved,
                sources = sources.map { "${it.passageId} ${it.title}" },
                sourceChars = sources.sumOf { it.excerpt.length },
                sourceCharsFull = sources.sumOf { it.text.length },
                cited = Citations.cited(answer, sources.size),
                invalidCitations = Citations.invalid(answer, sources.size),
                notCovered = Citations.isNotCovered(answer),
                unsourced = Citations.hasUnsourced(answer),
                calculatorFixes = Regex.fromLiteral("(calculator: ").findAll(answer).count(),
                location = report.location,
            ),
            stress,
        )
    }

    /** Stops the current answer, and the stress test if one is running. */
    fun stop() {
        work?.cancel()
        plannerEngine.cancel()
        answerer.cancel()
    }

    private fun updateReply(change: (ChatMessage) -> ChatMessage) {
        _state.update { it.copy(messages = it.messages.dropLast(1) + change(it.messages.last())) }
    }

    override fun onCleared() {
        stop()
        indexes.values.forEach { it.close() }
    }

    companion object {
        const val DEFAULT_STRESS_COUNT = 20
        private const val STRESS_ASSET = "stress_questions.txt"
        private const val CITIES_ASSET = "cities.tsv"
        private const val KEYWORDS = "keywords"
    }
}
