package app.offlineresearch.profiles

import android.app.ActivityManager
import android.content.Context
import app.offlineresearch.rag.IndexFiles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/**
 * Everything that differs between models. Changing the model means changing
 * (or pushing) one of these JSON files; no code changes.
 */
@Serializable
data class ModelProfile(
    val name: String,
    /** The answerer: file name inside the models/ directory on the device. */
    @SerialName("model_file") val modelFile: String,
    /**
     * What runs the answerer: "llama" (llama.cpp in this process, the whole model
     * memory-mapped) or "bmoe" (BigMoeOnEdge, which streams a mixture-of-experts
     * model's experts from flash).
     */
    val engine: String = "llama",
    /** Extra command-line flags for the "bmoe" engine, including its sampling settings. */
    @SerialName("engine_args") val engineArgs: List<String> = emptyList(),
    /** Let a reasoning model think before it answers ("bmoe" engine; "llama" uses prompt_suffix). */
    val think: Boolean = false,
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
    /** Send the sentences that bear on the question instead of whole passages. */
    @SerialName("compress_sources") val compressSources: Boolean = true,
    /** How many passages sentences may be taken from when compressing. */
    @SerialName("source_passages") val sourcePassages: Int = 10,
    /**
     * Lets the answerer add what it knows itself where the sources fall short,
     * under a fixed line that marks it as unsourced. Off: sources only.
     */
    @SerialName("own_knowledge") val ownKnowledge: Boolean = true,
    /** "full" (the default) or "short": three plain sentences, for small models that copy the full rules into their answers. */
    @SerialName("answer_rules") val answerRules: String = "full",
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
    /**
     * Switches appended to the planner's instructions. It is the planner's own:
     * the answerer's prompt_suffix says nothing about a different model. The
     * default turns off the Qwen3 planner's thinking, without which it spends
     * its few tokens on thoughts and returns no searches.
     */
    @SerialName("prompt_suffix") val promptSuffix: String = "/no_think",
)

/** The active profile and a short note on why it was picked, for the status line. */
data class LoadedProfile(val profile: ModelProfile, val source: String)

/** Finds the active profile, model files and index files on the device. */
class ProfileStore(private val context: Context) {

    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** What the user picked in settings; AUTO until they pick something. */
    var choice: ProfileChoice
        get() = ProfileChoice.parse(settings.getString(KEY_CHOICE, null))
        set(value) = settings.edit().putString(KEY_CHOICE, value.name).apply()

    fun totalRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem
    }

    private fun overrideFile(): File? =
        context.getExternalFilesDir(null)?.let { File(it, OVERRIDE_FILE) }?.takeIf { it.isFile }

    /** True when a profile.json pushed over adb is taking precedence over the settings. */
    fun hasPushedOverride(): Boolean = overrideFile() != null

    /**
     * Order of precedence: a profile.json pushed over adb, then the profile
     * chosen in settings, then the one that suits this phone's RAM.
     */
    fun load(): LoadedProfile {
        overrideFile()?.let { return LoadedProfile(ModelProfile.parse(it.readText()), "pushed profile.json") }
        val picked = choice
        val ram = totalRamBytes()
        val asset = ProfileSelector.assetFor(picked, ram)
        val text = context.assets.open(asset).bufferedReader().use { it.readText() }
        val source = if (picked == ProfileChoice.AUTO) {
            String.format(Locale.US, "auto, %.1f GB RAM", ram / 1e9)
        } else {
            "chosen in settings"
        }
        return LoadedProfile(ModelProfile.parse(text), source)
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
        const val MODELS_DIR = "models"
        private const val KEY_CHOICE = "profile_choice"
    }
}
