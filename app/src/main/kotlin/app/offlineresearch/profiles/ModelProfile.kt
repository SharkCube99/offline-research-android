package app.offlineresearch.profiles

import android.content.Context
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
    /** File name inside the models/ directory on the device. */
    @SerialName("model_file") val modelFile: String,
    @SerialName("n_ctx") val contextSize: Int,
    @SerialName("n_batch") val batchSize: Int,
    /** 0 = let the engine choose. */
    @SerialName("n_threads") val threads: Int = 0,
    @SerialName("use_mmap") val useMmap: Boolean = true,
    @SerialName("use_mlock") val useMlock: Boolean = false,
    /** llama.cpp weight repacking: a second copy of the weights in RAM. */
    val repack: Boolean = false,
    /** "auto" = the template embedded in the GGUF file. */
    @SerialName("chat_template") val chatTemplate: String = "auto",
    @SerialName("system_prompt") val systemPrompt: String = "",
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Float,
    @SerialName("top_k") val topK: Int,
    @SerialName("top_p") val topP: Float,
    @SerialName("presence_penalty") val presencePenalty: Float = 0f,
    /** Token budget for retrieved passages. Not used until the RAG pipeline (M3). */
    @SerialName("retrieval_budget_tokens") val retrievalBudgetTokens: Int = 0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): ModelProfile = json.decodeFromString(serializer(), text)
    }
}

/** Finds the active profile and its model file on the device. */
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

    /** Directories searched for model files, in order. */
    fun modelDirs(): List<File> = listOfNotNull(
        context.getExternalFilesDir(null)?.let { File(it, MODELS_DIR) },
        File(context.filesDir, MODELS_DIR),
    )

    fun findModel(profile: ModelProfile): File? =
        modelDirs().map { File(it, profile.modelFile) }.firstOrNull { it.isFile && it.canRead() }

    companion object {
        const val OVERRIDE_FILE = "profile.json"
        const val DEFAULT_ASSET = "low.json"
        const val MODELS_DIR = "models"
    }
}
