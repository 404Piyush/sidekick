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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sidekick.app.provider.LlmChunk
import com.sidekick.app.provider.LlmException
import com.sidekick.app.provider.LlmRequest
import com.sidekick.app.provider.LlmRouter
import com.sidekick.app.provider.Provider
import com.sidekick.app.provider.ChatMessage
import com.sidekick.app.ui.theme.SidekickTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Streaming conversation screen wired to [LlmRouter] for M1.
 *
 * Persisted settings (DataStore) arrive in M2. For now the user's choices
 * live only for the lifetime of this screen and are surfaced through the
 * Settings sheet — entered on first run, mutated any time the icon is tapped.
 */

/** Mutable settings shared between the main view and the settings sheet. */
class ConversationSettings(
    initialProvider: Provider = Provider.LocalOllama(),
) {
    var provider: Provider by mutableStateOf(initialProvider)
    var systemPrompt: String by mutableStateOf("You are a helpful assistant.")
}

/** One chat turn — user or assistant — rendered in the LazyColumn. */
private data class Turn(
    val role: String,
    val text: String,
)

/**
 * Hardcoded teammate system prompts for M1. M3 replaces these with files
 * shipped in the assets directory so the prompt library is editable
 * without rebuilding the APK.
 */
private fun systemPromptFor(teammateTitle: String): String = when (teammateTitle) {
    "Coder" -> "You are Coder. You write Kotlin and refactor Android code. Be terse."
    "Builder" -> "You are Builder. You draft HTML, scripts, and configs. Be concrete."
    "Researcher" -> "You are Researcher. You summarize sources and cite links. Be neutral."
    else -> "You are ${teammateTitle}."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    teammateTitle: String,
    router: LlmRouter = remember { LlmRouter() },
    settings: ConversationSettings = remember { ConversationSettings() },
) {
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    var isStreaming by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    var settingsOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var streamJob by remember { mutableStateOf<Job?>(null) }

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
                if (turns.isEmpty()) {
                    Text(
                        text = "Send a message to start.",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = turns, key = { it.hashCode() }) { turn ->
                            TurnBubble(turn)
                        }
                    }
                    androidx.compose.runtime.LaunchedEffect(turns.size) {
                        if (turns.isNotEmpty()) {
                            listState.animateScrollToItem(turns.size - 1)
                        }
                    }
                }
            }
            HorizontalDivider()
            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = !isStreaming,
                onSend = { text ->
                    val userText = text.trim()
                    if (userText.isEmpty()) return@InputBar
                    turns.add(Turn("user", userText))
                    input = ""
                    startStream(
                        router = router,
                        settings = settings,
                        teammateTitle = teammateTitle,
                        userText = userText,
                        turns = turns,
                        scope = scope,
                        onJob = { streamJob = it },
                        onStreamingChange = { isStreaming = it },
                    )
                },
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            sheetState = sheetState,
            settings = settings,
            onDismiss = { settingsOpen = false },
        )
    }
}

@Composable
private fun TurnBubble(turn: Turn) {
    val isUser = turn.role == "user"
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
                text = turn.text,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
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
            onClick = { onSend(value) },
            enabled = enabled && value.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    sheetState: androidx.compose.material3.SheetState,
    settings: ConversationSettings,
    onDismiss: () -> Unit,
) {
    val current = settings.provider
    val isOllama = current is Provider.LocalOllama
    val isCloud = current is Provider.CloudOpenAI
    var baseUrl: String by remember(current) { mutableStateOf(
        when (current) {
            is Provider.LocalOllama -> current.baseUrl
            is Provider.CloudOpenAI -> current.apiBaseUrl
        }
    ) }
    var modelName: String by remember(current) { mutableStateOf(
        when (current) {
            is Provider.LocalOllama -> current.modelName
            is Provider.CloudOpenAI -> current.modelName
        }
    ) }
    var apiKey: String by remember(current) { mutableStateOf(
        when (current) {
            is Provider.LocalOllama -> ""
            is Provider.CloudOpenAI -> current.apiKey
        }
    ) }

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
                    onClick = {
                        settings.provider = Provider.LocalOllama(
                            baseUrl = baseUrl.ifBlank { "http://10.0.2.2:11434" },
                            modelName = modelName.ifBlank { "qwen2.5-coder:7b" },
                        )
                    },
                    enabled = isOllama,
                ) { Text("Local Ollama") }
                Button(
                    onClick = {
                        settings.provider = Provider.CloudOpenAI(
                            apiBaseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
                            apiKey = apiKey,
                            modelName = modelName.ifBlank { "gpt-4o-mini" },
                        )
                    },
                    enabled = isCloud,
                ) { Text("Cloud OpenAI") }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = {
                    baseUrl = it
                    applyFieldChange(settings, baseUrl, modelName, apiKey)
                },
                label = { Text("Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = modelName,
                onValueChange = {
                    modelName = it
                    applyFieldChange(settings, baseUrl, modelName, apiKey)
                },
                label = { Text("Model") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (isCloud) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        applyFieldChange(settings, baseUrl, modelName, apiKey)
                    },
                    label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            Text(
                "M2 will persist these via DataStore.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun applyFieldChange(
    settings: ConversationSettings,
    baseUrl: String,
    modelName: String,
    apiKey: String,
) {
    val current = settings.provider
    settings.provider = when (current) {
        is Provider.LocalOllama -> Provider.LocalOllama(
            baseUrl = baseUrl.ifBlank { "http://10.0.2.2:11434" },
            modelName = modelName.ifBlank { "qwen2.5-coder:7b" },
        )
        is Provider.CloudOpenAI -> Provider.CloudOpenAI(
            apiBaseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
            apiKey = apiKey,
            modelName = modelName.ifBlank { "gpt-4o-mini" },
        )
    }
}

/**
 * Launch a streaming call against the current [settings.provider] and
 * append the assistant's reply to [turns] chunk by chunk. Cancellable via
 * the returned [Job] (held by the caller in `streamJob`).
 */
private fun startStream(
    router: LlmRouter,
    settings: ConversationSettings,
    teammateTitle: String,
    userText: String,
    turns: SnapshotStateList<Turn>,
    scope: CoroutineScope,
    onJob: (Job?) -> Unit,
    onStreamingChange: (Boolean) -> Unit,
) {
    val systemPrompt = systemPromptFor(teammateTitle)
    val request = LlmRequest(
        messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userText),
        ),
    )
    val assistantIndex = turns.size
    turns.add(Turn("assistant", ""))
    onStreamingChange(true)

    val job = scope.launch {
        val collected = StringBuilder()
        var caught: Throwable? = null
        try {
            val streamJob = router.stream(settings.provider, request) { chunk ->
                when (chunk) {
                    is LlmChunk.Text -> {
                        collected.append(chunk.delta)
                        turns[assistantIndex] = Turn("assistant", collected.toString())
                    }
                    is LlmChunk.Done -> {
                        // Final token accounting is currently unused in the UI.
                    }
                }
            }
            streamJob.join()
        } catch (t: Throwable) {
            caught = t
        }
        if (caught != null) {
            val message = when (val e = caught) {
                is LlmException -> e.message ?: e::class.simpleName.orEmpty()
                else -> caught.message ?: caught::class.simpleName.orEmpty()
            }
            turns[assistantIndex] = Turn(
                "assistant",
                "[error] " + (if (caught is LlmException) errorSummary(caught) else message),
            )
        }
        onStreamingChange(false)
        onJob(null)
    }
    onJob(job)
}

private fun errorSummary(e: LlmException): String = when (e) {
    is LlmException.Network -> "Network: ${e.message}"
    is LlmException.HttpStatus -> "HTTP ${e.code}: ${e.message}"
    is LlmException.Decode -> "Decode: ${e.message}"
    is LlmException.ProviderSpecific -> "Provider: ${e.message}"
}

@Preview(showBackground = true)
@Composable
private fun ConversationScreenPreview() {
    SidekickTheme {
        ConversationScreen(teammateTitle = "Coder")
    }
}