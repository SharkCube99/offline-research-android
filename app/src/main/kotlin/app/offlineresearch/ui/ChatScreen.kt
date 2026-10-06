package app.offlineresearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.offlineresearch.engine.EngineMetrics
import app.offlineresearch.engine.SystemSnapshot
import app.offlineresearch.profiles.ProfileChoice
import app.offlineresearch.profiles.ProfileSelector
import app.offlineresearch.rag.AnswerPart
import app.offlineresearch.rag.Citations
import app.offlineresearch.rag.Passage
import app.offlineresearch.rag.RagStage
import java.util.Locale

/** Which sources the panel shows, and which one to open on. */
private data class OpenSources(val sources: List<Passage>, val selected: Int)

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var openSources by remember { mutableStateOf<OpenSources?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val ready = state.status is ModelStatus.Ready
    // A question about "near me" needs the position. Android's own dialog asks
    // the first time; whatever the reply, the question is then sent.
    var awaitingPermission by remember { mutableStateOf<String?>(null) }
    val askForLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        awaitingPermission?.let(viewModel::send)
        awaitingPermission = null
    }

    // An answer takes minutes. If the screen times out, Android moves the app to
    // the background, where it gets less CPU and is the first to be killed.
    val view = LocalView.current
    LaunchedEffect(state.generating) { view.keepScreenOn = state.generating }

    // Follow the reply as it streams in.
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLength) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
    }

    Scaffold(modifier = Modifier.fillMaxSize().imePadding()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            StatusLine(state.status, state.stressProgress, onRetry = viewModel::loadModel, onSettings = { showSettings = true })

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.messages) { _, message ->
                    if (message.fromUser) {
                        QuestionBubble(message.text)
                    } else {
                        AnswerBubble(message, onOpenSources = { selected -> openSources = OpenSources(message.sources, selected) })
                    }
                }
            }

            state.metrics?.let { MetricsLine(it, state.system) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    enabled = ready,
                    placeholder = { Text("Ask a question") },
                    maxLines = 4,
                )
                if (state.generating) {
                    OutlinedButton(onClick = viewModel::stop) { Text("Stop") }
                } else {
                    Button(
                        onClick = {
                            if (viewModel.needsLocationPermission(draft)) {
                                awaitingPermission = draft
                                askForLocation.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                )
                            } else {
                                viewModel.send(draft)
                            }
                            draft = ""
                        },
                        enabled = ready && draft.isNotBlank(),
                    ) { Text("Send") }
                }
            }
        }
    }

    openSources?.let { open ->
        SourcesPanel(open, onDismiss = { openSources = null })
    }
    if (showSettings) {
        SettingsDialog(
            state = state,
            onChoice = viewModel::setProfileChoice,
            onStress = {
                showSettings = false
                viewModel.runStress()
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun SettingsDialog(
    state: ChatUiState,
    onChoice: (ProfileChoice) -> Unit,
    onStress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val small = MaterialTheme.typography.bodySmall
    val auto = ProfileSelector.assetFor(ProfileChoice.AUTO, (state.totalRamGb * 1e9).toLong()).removeSuffix(".json")
    val labels = listOf(
        ProfileChoice.AUTO to String.format(Locale.US, "Automatic (%.1f GB RAM: %s)", state.totalRamGb, auto),
        ProfileChoice.LOW to "Low (4B model)",
        ProfileChoice.HIGH to "High (35B model, 3B active)",
        ProfileChoice.FAST to "Fast (2.6B model, 0.6B active)",
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                Text("Model profile", fontWeight = FontWeight.Bold)
                labels.forEach { (choice, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.profileChoice == choice,
                                enabled = !state.generating,
                                onClick = { onChoice(choice) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.profileChoice == choice, onClick = null, enabled = !state.generating)
                        Text(label, modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp))
                    }
                }
                if (state.pushedOverride) {
                    Text("A profile.json pushed over adb is active and overrides this choice.", style = small)
                }
                Text("Changing the profile reloads the models.", style = small)
                OutlinedButton(
                    onClick = onStress,
                    enabled = state.status is ModelStatus.Ready && !state.generating,
                    modifier = Modifier.padding(top = 12.dp),
                ) { Text("Run stress test (${ChatViewModel.DEFAULT_STRESS_COUNT} questions)") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun StatusLine(status: ModelStatus, stressProgress: String?, onRetry: () -> Unit, onSettings: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (status) {
            ModelStatus.Loading -> Text("Loading model and index…", style = small)
            is ModelStatus.Ready -> {
                Text(
                    "${status.modelFile} · profile ${status.profile} (${status.profileSource}) · " +
                        "${status.corpora.joinToString(", ")} · search: ${status.planner ?: "keywords"} · offline",
                    style = small,
                )
                stressProgress?.let { Text("Stress test: question $it", style = small, fontWeight = FontWeight.Bold) }
            }
            is ModelStatus.Missing -> {
                Text("${status.what} not found", color = MaterialTheme.colorScheme.error)
                Text(
                    "Run scripts/setup.sh from a computer, or push the file with adb into one of:",
                    style = small,
                )
                SelectionContainer {
                    Text(status.searched.joinToString("\n"), style = small, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = onRetry) { Text("Check again") }
            }
            is ModelStatus.Failed -> {
                Text("Could not start: ${status.reason}", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
        }
        if (status !is ModelStatus.Loading) TextButton(onClick = onSettings) { Text("Settings") }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SelectionContainer {
            Text(
                text = text,
                color = colors.onPrimaryContainer,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .background(colors.primaryContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AnswerBubble(message: ChatMessage, onOpenSources: (selected: Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val citationStyle = TextLinkStyles(SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold))
    val text = buildAnnotatedString {
        for (part in Citations.parse(message.text, message.sources.size)) {
            when (part) {
                is AnswerPart.Text ->
                    if (part.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part.text) } else append(part.text)
                is AnswerPart.Citation ->
                    withLink(LinkAnnotation.Clickable("source-${part.number}", citationStyle) { onOpenSources(part.number) }) {
                        append("[${part.number}]")
                    }
            }
        }
    }
    Column(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Until the model's own words arrive, show what search found: it is useful
        // at once, and it is labelled as source text so it is not mistaken for the answer.
        if (text.isEmpty() && message.preview != null) {
            Text(
                "Found in ${message.preview.corpus.replaceFirstChar { it.uppercase() }} · ${message.preview.title}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
            )
            Text(
                message.preview.excerpt.removePrefix("${message.preview.title}: "),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (text.isNotEmpty()) Text(text = text, color = colors.onSurfaceVariant)
        stageLabel(message.stage, message.sources.size)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (Citations.hasUnsourced(message.text)) {
            Text(
                "Part of this answer is the model's own knowledge, not taken from a source. It may be wrong or out of date.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (message.sources.isNotEmpty()) {
            TextButton(onClick = { onOpenSources(0) }) { Text("Sources (${message.sources.size})") }
        }
    }
}

/** What to show while the answer is not streaming yet. */
private fun stageLabel(stage: RagStage?, sources: Int): String? = when (stage) {
    RagStage.LOCATING -> "Finding where you are (GPS, no network)…"
    RagStage.PLANNING -> "Planning searches…"
    RagStage.SEARCHING -> "Searching offline sources…"
    RagStage.THINKING -> "Thinking deeper… reading $sources ${if (sources == 1) "source" else "sources"}"
    RagStage.ANSWERING, null -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcesPanel(open: OpenSources, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // selected is a 1-based source number, or 0 for "show all from the top".
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (open.selected - 1).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(open.sources) { index, source ->
                val number = index + 1
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (number == open.selected) colors.primaryContainer else colors.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                ) {
                    Text("[$number] ${source.title}", fontWeight = FontWeight.Bold)
                    Text(
                        "${source.corpus.replaceFirstChar { it.uppercase() }} · ${source.url}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    SelectionContainer {
                        Column {
                            // What the model was given, then the passage it was taken from.
                            Text(source.excerpt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                            if (source.excerpt != source.text) {
                                Text("Full passage", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                                Text(source.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The metrics overlay: speed of the last answer, memory, paging and heat. */
@Composable
private fun MetricsLine(metrics: EngineMetrics, system: SystemSnapshot?) {
    val lines = mutableListOf(String.format(Locale.US, "load %.1f s · %d/%d threads", metrics.loadMs / 1000.0, metrics.threads, metrics.batchThreads))
    if (metrics.generatedTokens > 0) {
        lines += String.format(
            Locale.US,
            "first token %.1f s · read %d tok at %.1f/s · write %.1f tok/s",
            metrics.timeToFirstTokenMs / 1000.0,
            metrics.promptTokens - metrics.reusedPromptTokens,
            metrics.promptTokensPerSecond,
            metrics.tokensPerSecond,
        )
    }
    if (system != null) {
        lines += String.format(
            Locale.US,
            "RSS %.2f GB (peak %.2f) · free %.2f GB · %d major faults · thermal %s",
            system.rssKb / 1e6,
            system.peakRssKb / 1e6,
            system.memAvailableKb / 1e6,
            system.majorFaults,
            system.thermal.lowercase(Locale.US),
        )
    }
    Text(
        text = lines.joinToString("\n"),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
