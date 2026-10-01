package app.offlineresearch.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.offlineresearch.engine.EngineConfig
import app.offlineresearch.engine.EngineException
import app.offlineresearch.engine.EngineMetrics
import app.offlineresearch.engine.GenerationRequest
import app.offlineresearch.engine.InferenceEngine
import app.offlineresearch.engine.LlamaEngine
import app.offlineresearch.engine.MetricsLog
import app.offlineresearch.profiles.ModelProfile
import app.offlineresearch.profiles.ProfileStore
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
import kotlin.random.Random

data class ChatMessage(val fromUser: Boolean, val text: String)

sealed interface ModelStatus {
    data object Loading : ModelStatus
    data class Ready(val profile: String, val modelFile: String) : ModelStatus
    data class Missing(val modelFile: String, val searched: List<String>) : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

data class ChatUiState(
    val status: ModelStatus = ModelStatus.Loading,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val metrics: EngineMetrics? = null,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: InferenceEngine = LlamaEngine(application.applicationInfo.nativeLibraryDir)
    private val profiles = ProfileStore(application)
    private val metricsLog = MetricsLog(application.getExternalFilesDir(null)?.let { File(it, "logs") })
    private val appVersion: String =
        application.packageManager.getPackageInfo(application.packageName, 0).versionName ?: "unknown"

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var profile: ModelProfile? = null
    private var generation: Job? = null

    init {
        loadModel()
    }

    fun loadModel() {
        _state.update { it.copy(status = ModelStatus.Loading) }
        viewModelScope.launch {
            val status = try {
                val active = withContext(Dispatchers.IO) { profiles.load() }
                val model = withContext(Dispatchers.IO) { profiles.findModel(active) }
                if (model == null) {
                    ModelStatus.Missing(active.modelFile, profiles.modelDirs().map { it.absolutePath })
                } else {
                    engine.load(
                        model.absolutePath,
                        EngineConfig(
                            contextSize = active.contextSize,
                            batchSize = active.batchSize,
                            threads = active.threads,
                            useMmap = active.useMmap,
                            useMlock = active.useMlock,
                            chatTemplate = active.chatTemplate,
                        ),
                    )
                    profile = active
                    Log.i(MetricsLog.TAG, "system info: ${engine.systemInfo()}")
                    ModelStatus.Ready(active.name, active.modelFile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(MetricsLog.TAG, "model load failed", e)
                ModelStatus.Failed(e.message ?: e.toString())
            }
            _state.update { it.copy(status = status, metrics = if (status is ModelStatus.Ready) engine.metrics() else null) }
        }
    }

    fun send(question: String) {
        val active = profile ?: return
        val text = question.trim()
        if (text.isEmpty() || _state.value.generating) return

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(true, text) + ChatMessage(false, ""),
                generating = true,
            )
        }
        generation = viewModelScope.launch {
            val raw = StringBuilder()
            var failure: String? = null
            try {
                engine.generate(
                    GenerationRequest(
                        systemPrompt = active.systemPrompt,
                        userPrompt = text,
                        maxTokens = active.maxTokens,
                        temperature = active.temperature,
                        topK = active.topK,
                        topP = active.topP,
                        presencePenalty = active.presencePenalty,
                        seed = Random.nextInt(),
                    ),
                ).collect { fragment ->
                    raw.append(fragment)
                    replaceLastReply(stripThinking(raw.toString()))
                }
            } catch (e: EngineException) {
                failure = e.message
            } finally {
                withContext(NonCancellable) {
                    val metrics = engine.metrics()
                    if (failure != null) {
                        replaceLastReply("[error] $failure")
                    } else {
                        withContext(Dispatchers.IO) {
                            metricsLog.record(metrics, active.name, active.modelFile, active.contextSize, appVersion)
                        }
                    }
                    _state.update { it.copy(generating = false, metrics = metrics) }
                }
            }
        }
    }

    fun stop() {
        engine.cancel()
    }

    private fun replaceLastReply(text: String) {
        _state.update { it.copy(messages = it.messages.dropLast(1) + ChatMessage(false, text)) }
    }

    override fun onCleared() {
        engine.cancel()
    }
}
