package com.sidekick.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidekick.app.data.ProviderConfigEntity
import com.sidekick.app.data.ToolCallEntity
import com.sidekick.app.data.TurnEntity
import com.sidekick.app.ui.theme.SidekickTheme

/**
 * Streaming conversation screen wired to [ConversationViewModel] for M2.
 *
 * Reads everything from [ConversationUiState] (a [kotlinx.coroutines.flow.StateFlow])
 * and dispatches user input via `viewModel.sendMessage(...)`. No in-memory
 * `mutableStateListOf` for messages, no `mutableStateOf<Provider>` for
 * settings — both are owned by the ViewModel now.
 *
 * Manual factory injection (no Hilt in M2). M3 swaps in a Hilt factory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    teammateSlug: String,
    teammateTitle: String,
) {
    val context = LocalContext.current
    val viewModel: ConversationViewModel = viewModel(
        factory = ConversationViewModel.factory(
            context = context,
            teammateSlug = teammateSlug,
            title = teammateTitle,
        ),
        key = "conversation-$teammateSlug",
    )

    LaunchedEffect(teammateSlug) {
        viewModel.start(context)
    }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    var settingsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(teammateTitle) },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            // Transcript.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.messages.isEmpty() && !state.isStreaming) {
                    Text(
                        text = "Send a message to start.",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // Build a flat list of "transcript entries" — turns
                    // interleaved with their tool calls — so we can render
                    // them with a single LazyColumn `items` call (a nested
                    // `items` isn't supported inside the outer one).
                    val entries: List<TranscriptEntry> = buildList {
                        for (turn in state.messages) {
                            add(TranscriptEntry.TurnEntry(turn))
                            state.toolCallsByTurn[turn.id].orEmpty().forEach { call ->
                                add(TranscriptEntry.ToolCallEntry(call))
                            }
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = entries, key = { it.id }) { entry ->
                            when (entry) {
                                is TranscriptEntry.TurnEntry -> TurnBubble(
                                    turn = entry.turn,
                                    partialText = state.partialResponse,
                                )
                                is TranscriptEntry.ToolCallEntry -> ToolCallBubble(call = entry.call)
                            }
                        }
                    }
                    LaunchedEffect(state.messages.size) {
                        if (state.messages.isNotEmpty()) {
                            listState.animateScrollToItem(state.messages.size - 1)
                        }
                    }
                }
            }
            HorizontalDivider()
            InputBar(
                onSend = { text -> viewModel.sendMessage(text) },
                enabled = !state.isStreaming,
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            sheetState = sheetState,
            active = state.activeProvider,
            onActivate = { config -> viewModel.setProvider(config) },
            onDismiss = { settingsOpen = false },
        )
    }
}

@Composable
private fun TurnBubble(turn: TurnEntity, partialText: String) {
    val isUser = turn.role == "user"
    val isStreamingAssistant = !isUser && turn.content.isEmpty()
    val displayContent = if (isStreamingAssistant && partialText.isNotEmpty()) partialText else turn.content

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(horizontal = 4.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Text(
                text = if (isUser) "You" else "Assistant",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = displayContent,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/**
 * Compact one-line render of a [ToolCallEntity]. M3 shows the tool name
 * + args summary; M4 will render a richer preview (e.g. image
 * thumbnail for camera calls).
 */
@Composable
private fun ToolCallBubble(call: ToolCallEntity) {
    val summary = "${call.toolName}(${call.argsJson})"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = "• $summary",
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun InputBar(
    onSend: (String) -> Unit,
    enabled: Boolean,
) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = { Text("Ask anything…") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send,
            ),
        )
        Button(
            onClick = {
                val text = input
                if (text.isNotBlank()) {
                    onSend(text)
                    input = ""
                }
            },
            enabled = enabled && input.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

/**
 * Provider settings sheet. For M2 it surfaces just the two built-in
 * providers — M3 will add Anthropic, fetch live model lists, etc.
 *
 * The active row comes from [ConversationUiState.activeProvider] (loaded from
 * [com.sidekick.app.data.dao.ProviderConfigDao]); changes write through
 * [ConversationViewModel.setProvider] so the router's cache invalidates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    sheetState: androidx.compose.material3.SheetState,
    active: ProviderConfigEntity?,
    onActivate: (ProviderConfigEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var baseUrl by remember(active?.id) {
        mutableStateOf(active?.baseUrl ?: "http://10.0.2.2:11434")
    }
    var modelName by remember(active?.id) {
        mutableStateOf(active?.modelName ?: "qwen2.5-coder:7b")
    }
    var apiKey by remember(active?.id) {
        mutableStateOf(active?.apiKey ?: "")
    }
    var pendingKind by remember(active?.id) {
        mutableStateOf(active?.providerKind ?: "local_ollama")
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Provider", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pendingKind = "local_ollama" },
                    enabled = pendingKind != "local_ollama",
                ) { Text("Local Ollama") }
                Button(
                    onClick = { pendingKind = "cloud_openai" },
                    enabled = pendingKind != "cloud_openai",
                ) { Text("Cloud OpenAI") }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = { modelName = it },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (pendingKind == "cloud_openai") {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Button(
                onClick = {
                    val config = ProviderConfigEntity(
                        id = active?.id ?: 0L,
                        providerKind = pendingKind,
                        baseUrl = baseUrl,
                        apiKey = if (pendingKind == "cloud_openai") apiKey else null,
                        modelName = modelName,
                        isActive = true,
                    )
                    onActivate(config)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = baseUrl.isNotBlank() && modelName.isNotBlank(),
            ) {
                Text("Save")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ConversationScreenPreview() {
    SidekickTheme {
        // Preview cannot construct a real ViewModel + DB; render an empty
        // home-screen preview instead. Compose previews don't exercise the
        // real data layer.
        Text("ConversationScreen — preview unavailable (real ViewModel needed)")
    }
}

/**
 * One item in the rendered transcript. Turns and tool calls are
 * interleaved into a single list so the [LazyColumn] can `items` them
 * flat — nested `items` calls aren't supported.
 */
private sealed class TranscriptEntry {
    abstract val id: Long

    data class TurnEntry(val turn: com.sidekick.app.data.TurnEntity) : TranscriptEntry() {
        override val id: Long get() = turn.id
    }

    data class ToolCallEntry(val call: com.sidekick.app.data.ToolCallEntity) : TranscriptEntry() {
        override val id: Long get() = call.id
    }
}
