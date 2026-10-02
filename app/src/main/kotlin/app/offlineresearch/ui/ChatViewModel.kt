package app.offlineresearch.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.offlineresearch.engine.EngineConfig
import app.offlineresearch.engine.EngineException
import app.offlineresearch.engine.EngineMetrics
import app.offlineresearch.engine.InferenceEngine
import app.offlineresearch.engine.LlamaEngine
import app.offlineresearch.engine.MetricsLog
import app.offlineresearch.engine.RagRecord
import app.offlineresearch.engine.RunInfo
import app.offlineresearch.engine.ThreadAffinity
import app.offlineresearch.profiles.ModelProfile
import app.offlineresearch.profiles.ProfileStore
import app.offlineresearch.rag.AnswerSettings
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
import app.offlineresearch.rag.SqlDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    /** The numbered sources this answer may cite; `sources[0]` is source 1. */
    val sources: List<Passage> = emptyList(),
    /** What the pipeline is doing; null once the answer is complete. */
    val stage: RagStage? = null,
)

sealed interface ModelStatus {
    data object Loading : ModelStatus

    data class Ready(
        val profile: String,
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
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val nativeLibDir = application.applicationInfo.nativeLibraryDir
    private val answerer: InferenceEngine = LlamaEngine(nativeLibDir, "llama-answerer")
    private val plannerEngine: InferenceEngine = LlamaEngine(nativeLibDir, "llama-planner")
    private val profiles = ProfileStore(application)
    private val metricsLog = MetricsLog(application.getExternalFilesDir(null)?.let { File(it, "logs") })
    private val appVersion: String =
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "unknown"

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var profile: ModelProfile? = null
    private var pipeline: RagPipeline? = null
    private var plannerName = KEYWORDS
    private var indexes: Map<String, SqlDatabase> = emptyMap()
    private var pendingQuestion: String? = null

    init {
        loadModel()
    }

    fun loadModel() {
        _state.update { it.copy(status = ModelStatus.Loading) }
        viewModelScope.launch {
            val status = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(MetricsLog.TAG, "load failed", e)
                ModelStatus.Failed(e.message ?: e.toString())
            }
            _state.update { it.copy(status = status, metrics = if (status is ModelStatus.Ready) answerer.metrics() else null) }
            if (status is ModelStatus.Ready) pendingQuestion?.let { send(it) }
            pendingQuestion = null
        }
    }

    private suspend fun load(): ModelStatus {
        val active = withContext(Dispatchers.IO) { profiles.load() }
        val model = withContext(Dispatchers.IO) { profiles.findModel(active.modelFile) }
            ?: return ModelStatus.Missing("Model file ${active.modelFile}", profiles.modelDirs().map { it.absolutePath })
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
        answerer.load(model.absolutePath, config(active.contextSize))
        Log.i(MetricsLog.TAG, "system info: ${answerer.systemInfo()}")

        // The planner is optional: without its model file, search uses the question's keywords.
        val plannerFile = active.planner?.let { planner ->
            withContext(Dispatchers.IO) { profiles.findModel(planner.modelFile) }
        }
        val planner: QueryPlanner = if (active.planner != null && plannerFile != null) {
            plannerEngine.load(plannerFile.absolutePath, config(active.planner.contextSize))
            LlmPlanner(plannerEngine, active.promptSuffix)
        } else {
            if (active.planner != null) Log.w(MetricsLog.TAG, "planner model ${active.planner.modelFile} not found; using keywords")
            KeywordPlanner
        }
        plannerName = plannerFile?.name ?: KEYWORDS

        indexes.values.forEach { it.close() }
        indexes = withContext(Dispatchers.IO) { IndexFiles.open(indexFiles) }
        Log.i(MetricsLog.TAG, "index: ${indexFiles.map { "${it.key} (${it.value.length() / 1_000_000} MB)" }}, planner: $plannerName")

        profile = active
        pipeline = RagPipeline(
            planner = planner,
            retriever = Retriever(indexes),
            budgeter = ContextBudgeter(active.retrievalBudgetTokens, countTokens = answerer::countTokens),
            answerer = answerer,
            settings = AnswerSettings(
                promptSuffix = active.promptSuffix,
                maxTokens = active.maxTokens,
                temperature = active.temperature,
                topK = active.topK,
                topP = active.topP,
                presencePenalty = active.presencePenalty,
            ),
            seed = Random::nextInt,
        )
        return ModelStatus.Ready(active.name, active.modelFile, indexFiles.keys.toList(), plannerFile?.name)
    }

    /** Asks [question] now, or as soon as loading has finished. */
    fun ask(question: String) {
        if (pipeline == null) pendingQuestion = question else send(question)
    }

    fun send(question: String) {
        val active = profile ?: return
        val rag = pipeline ?: return
        val text = question.trim()
        if (text.isEmpty() || _state.value.generating) return

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(true, text) + ChatMessage(false, "", stage = RagStage.PLANNING),
                generating = true,
            )
        }
        viewModelScope.launch {
            val raw = StringBuilder()
            var sources = emptyList<Passage>()
            var report: RagReport? = null
            var failure: String? = null
            try {
                rag.answer(text).collect { event ->
                    when (event) {
                        is RagEvent.Stage -> updateReply { it.copy(stage = event.stage) }
                        is RagEvent.Sources -> {
                            sources = event.sources
                            updateReply { it.copy(sources = event.sources) }
                        }
                        is RagEvent.Token -> {
                            raw.append(event.text)
                            val visible = stripThinking(raw.toString())
                            updateReply { it.copy(text = visible) }
                        }
                        is RagEvent.Finished -> report = event.report
                    }
                }
            } catch (e: EngineException) {
                failure = e.message
            } finally {
                withContext(NonCancellable) {
                    val answer = stripThinking(raw.toString())
                    updateReply { it.copy(stage = null, text = if (failure != null) "[error] $failure" else answer) }
                    val finished = report
                    if (failure == null && finished != null) {
                        withContext(Dispatchers.IO) { log(active, text, answer, sources, finished) }
                    }
                    _state.update { it.copy(generating = false, metrics = finished?.answerer ?: it.metrics) }
                }
            }
        }
    }

    private fun log(active: ModelProfile, question: String, answer: String, sources: List<Passage>, report: RagReport) {
        metricsLog.record(
            report.answerer,
            RunInfo(active.name, active.modelFile, active.contextSize, active.repack, appVersion, active.threadAffinity, active.batchSize),
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
                cited = Citations.cited(answer, sources.size),
                invalidCitations = Citations.invalid(answer, sources.size),
                notCovered = Citations.isNotCovered(answer),
            ),
        )
    }

    fun stop() {
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

    private companion object {
        const val KEYWORDS = "keywords"
    }
}
