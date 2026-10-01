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
    @SerialName("load_ms") val loadMs: Double,
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("prompt_ms") val promptMs: Double,
    @SerialName("ttft_ms") val timeToFirstTokenMs: Double,
    @SerialName("gen_tokens") val generatedTokens: Int,
    @SerialName("gen_ms") val generationMs: Double,
    @SerialName("tokens_per_sec") val tokensPerSecond: Double,
    val stop: String,
)

/**
 * Writes each run to logcat (tag OfflineResearch, prefix "METRICS ") and appends
 * it to <external files>/logs/metrics.jsonl, which `adb pull` can fetch.
 */
class MetricsLog(private val logDir: File?) {

    fun record(metrics: EngineMetrics, profile: String, modelFile: String, contextSize: Int, appVersion: String) {
        val record = MetricsRecord(
            timestamp = Instant.now().toString(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            androidSdk = Build.VERSION.SDK_INT,
            appVersion = appVersion,
            profile = profile,
            modelFile = modelFile,
            contextSize = contextSize,
            threads = metrics.threads,
            loadMs = metrics.loadMs,
            promptTokens = metrics.promptTokens,
            promptMs = metrics.promptMs,
            timeToFirstTokenMs = metrics.timeToFirstTokenMs,
            generatedTokens = metrics.generatedTokens,
            generationMs = metrics.generationMs,
            tokensPerSecond = metrics.tokensPerSecond,
            stop = metrics.stopReason.name,
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
