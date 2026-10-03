package app.offlineresearch.engine

import android.os.Build
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** One line of logs/metrics.jsonl. Every number in the docs must come from one of these. */
@Serializable
data class MetricsRecord(
    val timestamp: String,
    val device: String,
    val soc: String,
    @SerialName("android_sdk") val androidSdk: Int,
    @SerialName("app_version") val appVersion: String,
    val profile: String,
    @SerialName("model_file") val modelFile: String,
    @SerialName("n_ctx") val contextSize: Int,
    val threads: Int,
    @SerialName("threads_batch") val batchThreads: Int,
    @SerialName("thread_affinity") val threadAffinity: String,
    @SerialName("n_batch") val batchSize: Int,
    val repack: Boolean,
    @SerialName("load_ms") val loadMs: Double,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("prompt_reused") val reusedPromptTokens: Int,
    @SerialName("prompt_ms") val promptMs: Double,
    /** Prefill speed: prompt tokens actually processed, per second. */
    @SerialName("prompt_tokens_per_sec") val promptTokensPerSecond: Double,
    @SerialName("ttft_ms") val timeToFirstTokenMs: Double,
    @SerialName("gen_tokens") val generatedTokens: Int,
    @SerialName("gen_ms") val generationMs: Double,
    @SerialName("tokens_per_sec") val tokensPerSecond: Double,
    val stop: String,
    @SerialName("rss_kb") val rssKb: Long,
    @SerialName("peak_rss_kb") val peakRssKb: Long,
    /** Major page faults during this answer (reads from storage, mostly model weights). */
    @SerialName("major_faults") val majorFaults: Long,
    @SerialName("mem_available_kb") val memAvailableKb: Long,
    val thermal: String,
    val rag: RagRecord? = null,
    val stress: StressTag? = null,
)

/** The retrieval side of one answer. */
@Serializable
data class RagRecord(
    val question: String,
    val answer: String,
    val planner: String,
    @SerialName("planner_fallback") val plannerFallback: Boolean,
    val queries: List<String>,
    @SerialName("plan_ms") val planMs: Long,
    @SerialName("search_ms") val searchMs: Long,
    /** From sending the question to the first answer token: plan + search + prompt processing. */
    @SerialName("total_ttft_ms") val totalTimeToFirstTokenMs: Double,
    val retrieved: Int,
    val sources: List<String>,
    /** Characters of source text put in the prompt, and in the same passages before compression. */
    @SerialName("source_chars") val sourceChars: Int = 0,
    @SerialName("source_chars_full") val sourceCharsFull: Int = 0,
    val cited: List<Int>,
    @SerialName("invalid_citations") val invalidCitations: List<Int>,
    @SerialName("not_covered") val notCovered: Boolean,
)

/** Marks an answer as question [index] of [total] in stress run [run]. */
@Serializable
data class StressTag(val run: String, val index: Int, val total: Int)

/** Which model and settings an answer ran with. */
data class RunInfo(
    val profile: String,
    val modelFile: String,
    val contextSize: Int,
    val repack: Boolean,
    val appVersion: String,
    val threadAffinity: String,
    val batchSize: Int,
)

/**
 * Writes each run to logcat (tag OfflineResearch, prefix "METRICS ") and appends
 * it to <external files>/logs/metrics.jsonl, which `adb pull` can fetch.
 */
class MetricsLog(private val logDir: File?) {

    /**
     * [metrics] is null when no model ran (nothing was retrieved). [before] and
     * [after] are system readings taken around the answer.
     */
    fun record(
        metrics: EngineMetrics?,
        run: RunInfo,
        before: SystemSnapshot,
        after: SystemSnapshot,
        rag: RagRecord? = null,
        stress: StressTag? = null,
    ) {
        val record = MetricsRecord(
            timestamp = Instant.now().toString(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            androidSdk = Build.VERSION.SDK_INT,
            appVersion = run.appVersion,
            profile = run.profile,
            modelFile = run.modelFile,
            contextSize = run.contextSize,
            threads = metrics?.threads ?: 0,
            batchThreads = metrics?.batchThreads ?: 0,
            threadAffinity = run.threadAffinity,
            batchSize = run.batchSize,
            repack = run.repack,
            loadMs = metrics?.loadMs ?: 0.0,
            promptTokens = metrics?.promptTokens ?: 0,
            reusedPromptTokens = metrics?.reusedPromptTokens ?: 0,
            promptMs = metrics?.promptMs ?: 0.0,
            promptTokensPerSecond = metrics?.promptTokensPerSecond ?: 0.0,
            timeToFirstTokenMs = metrics?.timeToFirstTokenMs ?: 0.0,
            generatedTokens = metrics?.generatedTokens ?: 0,
            generationMs = metrics?.generationMs ?: 0.0,
            tokensPerSecond = metrics?.tokensPerSecond ?: 0.0,
            stop = (metrics?.stopReason ?: StopReason.NONE).name,
            rssKb = after.rssKb,
            peakRssKb = after.peakRssKb,
            majorFaults = if (before.majorFaults >= 0 && after.majorFaults >= 0) after.majorFaults - before.majorFaults else -1,
            memAvailableKb = after.memAvailableKb,
            thermal = after.thermal,
            rag = rag,
            stress = stress,
        )
        val line = Json.encodeToString(record)
        Log.i(TAG, "METRICS $line")
        try {
            logDir?.let { dir ->
                dir.mkdirs()
                File(dir, FILE_NAME).appendText(line + "\n")
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "could not append to $FILE_NAME", e)
        }
    }

    companion object {
        const val TAG = "OfflineResearch"
        const val FILE_NAME = "metrics.jsonl"
    }
}
