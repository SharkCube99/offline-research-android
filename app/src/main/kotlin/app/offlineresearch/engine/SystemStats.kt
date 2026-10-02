package app.offlineresearch.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

/** Memory, paging and heat at one moment, as far as an app may read them. */
data class SystemSnapshot(
    /** Resident memory of this process, including the mapped parts of the model that are in RAM. */
    val rssKb: Long,
    /** Highest resident memory since the process started. */
    val peakRssKb: Long,
    /** Page faults that needed a read from storage, since the process started. */
    val majorFaults: Long,
    /** What the system reports as available to apps. */
    val memAvailableKb: Long,
    /** Android thermal status: NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, or UNKNOWN. */
    val thermal: String,
)

/** Parsing of /proc files; pure, so it can be tested off the device. */
object ProcParser {
    /** Value in kB of a line like "VmHWM:   123456 kB" in /proc/self/status, or -1. */
    fun statusKb(status: String, key: String): Long =
        status.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull() ?: -1

    /**
     * Major page faults from /proc/self/stat, or -1. The process name is in
     * parentheses and may itself contain spaces or parentheses, so fields are
     * counted from the last ')'. majflt is the 12th field of the whole line.
     */
    fun majorFaults(stat: String): Long {
        val afterName = stat.substringAfterLast(')', "").trim()
        if (afterName.isEmpty()) return -1
        return afterName.split(' ').getOrNull(9)?.toLongOrNull() ?: -1
    }
}

object SystemStats {
    fun snapshot(context: Context): SystemSnapshot {
        val status = readOrEmpty("/proc/self/status")
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        return SystemSnapshot(
            rssKb = ProcParser.statusKb(status, "VmRSS"),
            peakRssKb = ProcParser.statusKb(status, "VmHWM"),
            majorFaults = ProcParser.majorFaults(readOrEmpty("/proc/self/stat")),
            memAvailableKb = memory.availMem / 1024,
            thermal = thermal(context),
        )
    }

    private fun readOrEmpty(path: String): String = try {
        File(path).readText()
    } catch (e: java.io.IOException) {
        ""
    }

    private fun thermal(context: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "UNKNOWN"
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }
}

/** One line of logs/exits.jsonl: why Android says an earlier run of the app ended. */
@Serializable
data class ExitRecord(
    val timestamp: String,
    val reason: String,
    val description: String,
    @SerialName("pss_kb") val pssKb: Long,
    @SerialName("rss_kb") val rssKb: Long,
)

/**
 * A process cannot log its own death. On the next start this asks Android why
 * the previous runs ended (crash, native crash, low-memory kill, ...) and
 * writes anything new to logcat and logs/exits.jsonl.
 */
object ExitLog {
    private const val KEY_LAST_SEEN = "last_exit_seen"

    fun recordNew(context: Context, logDir: File?) {
        if (Build.VERSION.SDK_INT < 30) return
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0)
        val fresh = manager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > lastSeen }
            .sortedBy { it.timestamp }
        if (fresh.isEmpty()) return
        for (exit in fresh) {
            val record = ExitRecord(
                timestamp = Instant.ofEpochMilli(exit.timestamp).toString(),
                reason = reasonName(exit.reason),
                description = exit.description ?: "",
                pssKb = exit.pss,
                rssKb = exit.rss,
            )
            val line = Json.encodeToString(record)
            Log.i(MetricsLog.TAG, "EXIT $line")
            try {
                logDir?.let { dir ->
                    dir.mkdirs()
                    File(dir, "exits.jsonl").appendText(line + "\n")
                }
            } catch (e: java.io.IOException) {
                Log.w(MetricsLog.TAG, "could not append to exits.jsonl", e)
            }
        }
        prefs.edit().putLong(KEY_LAST_SEEN, fresh.last().timestamp).apply()
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        // Added in later Android releases; named here by value so the app still builds for older ones.
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN_$reason"
    }
}
