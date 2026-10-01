package app.offlineresearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.offlineresearch.engine.EngineMetrics
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
    val listState = rememberLazyListState()
    val ready = state.status is ModelStatus.Ready

    // Follow the reply as it streams in.
    val lastLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastLength) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
    }

    Scaffold(modifier = Modifier.fillMaxSize().imePadding()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            StatusLine(state.status, onRetry = viewModel::loadModel)

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

            state.metrics?.let { MetricsLine(it) }

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
                            viewModel.send(draft)
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
}

@Composable
private fun StatusLine(status: ModelStatus, onRetry: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (status) {
            ModelStatus.Loading -> Text("Loading model and index…", style = small)
            is ModelStatus.Ready -> Text(
                "${status.modelFile} · ${status.corpora.joinToString(", ")} · " +
                    "search: ${status.planner ?: "keywords"} · offline",
                style = small,
            )
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
                is AnswerPart.Text -> append(part.text)
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
        if (text.isNotEmpty()) Text(text = text, color = colors.onSurfaceVariant)
        stageLabel(message.stage, message.sources.size)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (message.sources.isNotEmpty()) {
            TextButton(onClick = { onOpenSources(0) }) { Text("Sources (${message.sources.size})") }
        }
    }
}

/** What to show while the answer is not streaming yet. */
private fun stageLabel(stage: RagStage?, sources: Int): String? = when (stage) {
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
                        Text(source.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsLine(metrics: EngineMetrics) {
    val load = String.format(Locale.US, "load %.1f s", metrics.loadMs / 1000.0)
    val run = if (metrics.generatedTokens > 0) {
        String.format(
            Locale.US,
            " · %d prompt tokens · first token %.1f s · %.1f tok/s · %d threads",
            metrics.promptTokens,
            metrics.timeToFirstTokenMs / 1000.0,
            metrics.tokensPerSecond,
            metrics.threads,
        )
    } else {
        ""
    }
    Text(
        text = load + run,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
