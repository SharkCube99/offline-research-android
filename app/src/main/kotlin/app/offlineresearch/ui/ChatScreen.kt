package app.offlineresearch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.offlineresearch.engine.EngineMetrics
import java.util.Locale

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
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
                itemsIndexed(state.messages) { index, message ->
                    val streaming = state.generating && index == state.messages.lastIndex
                    MessageBubble(message, streaming)
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
}

@Composable
private fun StatusLine(status: ModelStatus, onRetry: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (status) {
            ModelStatus.Loading -> Text("Loading model…", style = small)
            is ModelStatus.Ready -> Text("${status.modelFile} · profile ${status.profile} · offline", style = small)
            is ModelStatus.Missing -> {
                Text("Model file not found: ${status.modelFile}", color = MaterialTheme.colorScheme.error)
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
                Text("Could not load the model: ${status.reason}", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, streaming: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        SelectionContainer {
            Text(
                text = if (streaming && message.text.isEmpty()) "…" else message.text,
                color = if (message.fromUser) colors.onPrimaryContainer else colors.onSurfaceVariant,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .background(
                        if (message.fromUser) colors.primaryContainer else colors.surfaceVariant,
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MetricsLine(metrics: EngineMetrics) {
    val load = String.format(Locale.US, "load %.1f s", metrics.loadMs / 1000.0)
    val run = if (metrics.generatedTokens > 0) {
        String.format(
            Locale.US,
            " · first token %.1f s · %.1f tok/s · %d tokens · %d threads",
            metrics.timeToFirstTokenMs / 1000.0,
            metrics.tokensPerSecond,
            metrics.generatedTokens,
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
