package app.offlineresearch.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [InferenceEngine] backed by BigMoeOnEdge, which streams a mixture-of-experts
 * model's experts from flash and keeps the recently used ones in RAM. The
 * engine is a separate program ([executable], `bmoe-cli --session`) that this
 * class starts and talks to over its standard input and output.
 *
 * Differences from [LlamaEngine] that the rest of the app has to live with:
 * sampling settings are fixed when the model loads ([extraArgs]), there is no
 * separate system prompt (it is put in front of the user's text), and token
 * counts are estimates because the protocol has no tokenize command.
 */
class BmoeEngine(
    private val executable: String,
    /** Engine flags from the profile, such as `--moe-stream` or `--temp 0.7`. */
    private val extraArgs: List<String>,
    private val think: Boolean = false,
) : InferenceEngine {

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val stderrTail = StringBuilder()
    private val nextId = AtomicInteger(1)

    @Volatile
    private var description = "BigMoeOnEdge (not loaded)"

    @Volatile
    private var loadMs = 0.0

    @Volatile
    private var threads = 0

    @Volatile
    private var last: EngineMetrics? = null

    override suspend fun load(modelPath: String, config: EngineConfig) = withContext(Dispatchers.IO) {
        stop()
        val argv = buildList {
            add(executable)
            addAll(listOf("--model", modelPath, "--session", "--chatml", "--progress"))
            addAll(listOf("--ctx-size", config.contextSize.toString(), "--ubatch", config.batchSize.toString()))
            if (config.threads > 0) addAll(listOf("--threads", config.threads.toString()))
            if (!think) add("--no-think")
            addAll(extraArgs)
        }
        val started = try {
            ProcessBuilder(argv).directory(File(modelPath).parentFile).redirectErrorStream(false).start()
        } catch (e: IOException) {
            throw EngineException("Could not start the engine at $executable: ${e.message}")
        }
        process = started
        reader = started.inputStream.bufferedReader()
        writer = started.outputStream.bufferedWriter()
        synchronized(stderrTail) { stderrTail.setLength(0) }
        // The engine logs to standard error. It must be read or the engine blocks
        // when the pipe fills; the last lines explain a failed start.
        thread(name = "bmoe-stderr", isDaemon = true) {
            try {
                started.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(stderrTail) {
                        stderrTail.append(line).append('\n')
                        if (stderrTail.length > 4000) stderrTail.delete(0, stderrTail.length - 4000)
                    }
                }
            } catch (e: IOException) {
                // The stream closes when the engine stops.
            }
        }

        while (true) {
            val line = reader?.readLine() ?: throw EngineException("The engine stopped while loading $modelPath.\n${errors()}")
            when (val event = BmoeProtocol.parse(line)) {
                is BmoeEvent.Ready -> {
                    loadMs = event.loadSeconds * 1000
                    threads = config.threads
                    description = "BigMoeOnEdge, ${event.architecture}, context ${event.contextSize}, thinking control ${event.thinkControl}"
                    return@withContext
                }
                is BmoeEvent.Error -> throw EngineException("The engine could not load $modelPath: ${event.message}")
                else -> Unit
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<String> = channelFlow {
        val input = reader ?: throw EngineException("No model is loaded")
        val id = nextId.getAndIncrement()
        val start = System.nanoTime()
        var firstTokenMs = 0.0
        var sent = ""
        // The engine has one text slot per turn, so the answer contract goes in front of the sources.
        val prompt = if (request.systemPrompt.isBlank()) request.userPrompt else "${request.systemPrompt}\n\n${request.userPrompt}"
        writeLine(BmoeProtocol.generate(id, prompt, request.maxTokens, think))

        while (true) {
            val line = input.readLine() ?: throw EngineException("The engine stopped while answering.\n${errors()}")
            when (val event = BmoeProtocol.parse(line)) {
                is BmoeEvent.Progress -> {
                    if (firstTokenMs == 0.0) firstTokenMs = (System.nanoTime() - start) / 1e6
                    // A reset replaces what was sent. Text already shown cannot be taken
                    // back, so only what extends it is passed on.
                    val text = if (event.reset) event.deltaText else sent + event.deltaText
                    if (text.startsWith(sent) && text.length > sent.length) {
                        if (trySend(text.substring(sent.length)).isFailure) this@BmoeEngine.cancel()
                    }
                    if (text.startsWith(sent)) sent = text
                }
                is BmoeEvent.Done -> if (event.id == id) {
                    if (firstTokenMs == 0.0) firstTokenMs = (System.nanoTime() - start) / 1e6
                    last = EngineMetrics(
                        loadMs = loadMs,
                        promptTokens = event.promptTokens,
                        promptMs = event.prefillSeconds * 1000,
                        timeToFirstTokenMs = firstTokenMs,
                        generatedTokens = event.tokens,
                        generationMs = if (event.tokensPerSecond > 0) event.tokens * 1000.0 / event.tokensPerSecond else 0.0,
                        threads = threads,
                        stopReason = when {
                            event.cancelled -> StopReason.CANCELLED
                            event.tokens >= request.maxTokens -> StopReason.MAX_TOKENS
                            else -> StopReason.EOS
                        },
                    )
                    return@channelFlow
                }
                is BmoeEvent.Error -> if (event.id == id || event.fatal) {
                    throw EngineException("The engine reported an error: ${event.message}")
                }
                else -> Unit
            }
        }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** An estimate: about four characters per token. The engine's protocol cannot count tokens. */
    override fun countTokens(text: String): Int = (text.length + 3) / 4

    override fun cancel() {
        try {
            writeLine(BmoeProtocol.CANCEL)
        } catch (e: EngineException) {
            // Nothing is running.
        }
    }

    override fun metrics(): EngineMetrics =
        last ?: EngineMetrics(loadMs, 0, 0.0, 0.0, 0, 0.0, threads, StopReason.NONE)

    override fun systemInfo(): String = description

    override suspend fun unload() = withContext(Dispatchers.IO) { stop() }

    private fun writeLine(line: String) {
        val out = writer ?: throw EngineException("No model is loaded")
        try {
            synchronized(out) {
                out.write(line)
                out.newLine()
                out.flush()
            }
        } catch (e: IOException) {
            throw EngineException("The engine is not running.\n${errors()}")
        }
    }

    private fun stop() {
        val running = process ?: return
        try {
            writeLine(BmoeProtocol.CLOSE)
        } catch (e: EngineException) {
            // Already gone.
        }
        // The model must be out of memory before another one loads.
        if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroyForcibly().waitFor()
        process = null
        reader = null
        writer = null
        description = "BigMoeOnEdge (not loaded)"
    }

    private fun errors(): String = synchronized(stderrTail) { stderrTail.toString().trim() }
}
