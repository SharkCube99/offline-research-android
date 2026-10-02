package app.offlineresearch.profiles

import android.content.Context
import app.offlineresearch.rag.IndexFiles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Everything that differs between models. Changing the model means changing
 * (or pushing) one of these JSON files; no code changes.
 */
@Serializable
data class ModelProfile(
    val name: String,
    /** The answerer: file name inside the models/ directory on the device. */
    @SerialName("model_file") val modelFile: String,
    @SerialName("n_ctx") val contextSize: Int,
    @SerialName("n_batch") val batchSize: Int,
    /** Threads for generating tokens. 0 = let the engine choose. */
    @SerialName("n_threads") val threads: Int = 0,
    /** Threads for processing prompts. 0 = the same as n_threads. */
    @SerialName("n_threads_batch") val batchThreads: Int = 0,
    /** "none", or "fastest" to restrict the threads to the fastest cores. */
    @SerialName("thread_affinity") val threadAffinity: String = "none",
    @SerialName("use_mmap") val useMmap: Boolean = true,
    @SerialName("use_mlock") val useMlock: Boolean = false,
    /** llama.cpp weight repacking: a second copy of the weights in RAM. */
    val repack: Boolean = false,
    /** "auto" = the template embedded in the GGUF file. */
    @SerialName("chat_template") val chatTemplate: String = "auto",
    /** Model-specific switches appended to every system prompt, such as "/no_think". */
    @SerialName("prompt_suffix") val promptSuffix: String = "",
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Float,
    @SerialName("top_k") val topK: Int,
    @SerialName("top_p") val topP: Float,
    @SerialName("presence_penalty") val presencePenalty: Float = 0f,
    /** Token budget for retrieved passages in the answerer's prompt. */
    @SerialName("retrieval_budget_tokens") val retrievalBudgetTokens: Int,
    /** The small model that writes search queries. Null = keyword search only. */
    val planner: PlannerProfile? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ModelProfile = json.decodeFromString(serializer(), text)
    }
}

/** Threads, batch size and memory settings are shared with the answerer. */
@Serializable
data class PlannerProfile(
    @SerialName("model_file") val modelFile: String,
    @SerialName("context_size") val contextSize: Int = 1024,
)

/** Finds the active profile, model files and index files on the device. */
class ProfileStore(private val context: Context) {

    /** A profile.json pushed over adb wins over the bundled default. */
    fun load(): ModelProfile {
        val override = context.getExternalFilesDir(null)?.let { File(it, OVERRIDE_FILE) }
        val text = if (override != null && override.isFile) {
            override.readText()
        } else {
            context.assets.open(DEFAULT_ASSET).bufferedReader().use { it.readText() }
        }
        return ModelProfile.parse(text)
    }

    private fun dataDirs(name: String): List<File> = listOfNotNull(
        context.getExternalFilesDir(null)?.let { File(it, name) },
        File(context.filesDir, name),
    )

    /** Directories searched for model files, in order. */
    fun modelDirs(): List<File> = dataDirs(MODELS_DIR)

    /** Directories searched for corpus index files, in order. */
    fun indexDirs(): List<File> = dataDirs(IndexFiles.DIR)

    fun findModel(fileName: String): File? =
        modelDirs().map { File(it, fileName) }.firstOrNull { it.isFile && it.canRead() }

    companion object {
        const val OVERRIDE_FILE = "profile.json"
        const val DEFAULT_ASSET = "low.json"
        const val MODELS_DIR = "models"
    }
}
