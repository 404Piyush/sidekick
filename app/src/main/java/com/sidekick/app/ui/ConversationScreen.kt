package com.sidekick.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sidekick.app.data.ProviderConfigEntity
import com.sidekick.app.data.ToolCallEntity
import com.sidekick.app.data.TurnEntity
import com.sidekick.app.provider.LlmChunk
import com.sidekick.app.provider.LlmException
import com.sidekick.app.tools.CameraLauncher
import com.sidekick.app.tools.builtins.createPhotoTarget
import com.sidekick.app.ui.theme.SidekickTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Streaming conversation screen wired to [ConversationViewModel].
 *
 * Reads everything from [ConversationUiState] (a [kotlinx.coroutines.flow.StateFlow])
 * and dispatches user input via `viewModel.sendMessage(...)`. No in-memory
 * `mutableStateListOf` for messages — both messages and settings live in the
 * ViewModel.
 *
 * M4 polish added:
 *  - Camera button + permission flow (`CameraLauncher` adapter → tool context)
 *  - Empty state hint with teammate name
 *  - Streaming typing indicator (three pulsing dots)
 *  - Error chip
 *  - Tool-call pills with the model-friendly tool marker
 *  - Settings sheet "Allow on-device image processing" toggle
 *  - Pending-image preview above the input bar
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
    val snackbarHost = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // --- Camera / permission plumbing ---------------------------------
    // We hold a single CompletableDeferred in `pendingCapture` that the
    // camera callback resolves when the system camera returns. The
    // Compose launcher fires the callback off the IO dispatcher; the
    // bridge lambda (below) resumes the deferred on the IO dispatcher
    // so the agent loop's `await()` lands safely on its original scope.
    var pendingCapture by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        pendingCapture?.complete(success)
        pendingCapture = null
    }

    val cameraActivityLauncher = remember<CameraLauncher> {
        CameraLauncher { outputUri ->
            val deferred = CompletableDeferred<Boolean>()
            pendingCapture = deferred
            cameraLauncher.launch(outputUri)
            deferred
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            // The user denied — we can't tell whether permanently without
            // `shouldShowRequestPermissionRationale` which is only available
            // on an Activity. Show a snackbar suggesting Settings as a
            // universal fallback; the user can re-open Settings anyway.
            coroutineScope.launch {
                snackbarHost.showSnackbar("Camera permission denied. Open Settings to allow it.")
            }
        }
    }

    val onCameraClick: () -> Unit = cameraClick@{
        if (!state.cameraEnabled) return@cameraClick
        val perm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        )
        if (perm == PackageManager.PERMISSION_GRANTED) {
            val target = createPhotoTarget(context)
            if (target != null) {
                val deferred = cameraActivityLauncher.takePicture(target.uri)
                // The CameraLauncher.takePicture contract is fire-and-forget
                // at the Compose layer; for the manual camera button path
                // we set pendingImageUri locally so the preview shows up.
                coroutineScope.launch {
                    val ok = deferred.await()
                    if (ok) viewModel.setPendingImage(target.uri.toString())
                }
            } else {
                coroutineScope.launch {
                    snackbarHost.showSnackbar("Could not allocate camera output destination")
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                if (state.messages.isEmpty() && !state.isStreaming) {
                    EmptyState(teammateTitle = teammateTitle)
                } else {
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
                                    isStreaming = state.isStreaming,
                                )
                                is TranscriptEntry.ToolCallEntry -> ToolCallBubble(call = entry.call)
                            }
                        }
                        state.error?.let { err ->
                            // Only show the chip if the most recent transcript
                            // message isn't already the error (otherwise the
                            // user sees the error twice — chip + assistant bubble).
                            val lastTurn = state.messages.lastOrNull()
                            val lastIsError = lastTurn?.role == "assistant" &&
                                lastTurn.content.startsWith("[error]")
                            if (!lastIsError) {
                                item(key = "error-chip") {
                                    ErrorChip(error = err, onDismiss = { viewModel.dismissError() })
                                }
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
                pendingImageUri = state.pendingImageUri,
                onClearImage = { viewModel.setPendingImage(null) },
                cameraEnabled = state.cameraEnabled,
                onCameraClick = onCameraClick,
                onSend = { text ->
                    val pending = state.pendingImageUri
                    if (pending != null) {
                        viewModel.sendMultimodal(text, pending)
                    } else {
                        viewModel.sendMessage(text)
                    }
                },
                enabled = !state.isStreaming,
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            sheetState = sheetState,
            active = state.activeProvider,
            cameraEnabled = state.cameraEnabled,
            onCameraToggle = { viewModel.setCameraEnabled(it) },
            onActivate = { config -> viewModel.setProvider(config) },
            onSelectModel = { modelId -> viewModel.selectModel(modelId) },
            curatedModels = viewModel.curatedModels,
            listLocalModels = { baseUrl -> viewModel.listLocalModels(baseUrl) },
            pullModel = { baseUrl, modelId -> viewModel.pullModel(baseUrl, modelId) },
            onDismiss = { settingsOpen = false },
        )
    }
}

@Composable
private fun EmptyState(teammateTitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Send a message to start.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = "Try asking $teammateTitle to refactor a function or summarise a note.",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun TurnBubble(
    turn: TurnEntity,
    partialText: String,
    isStreaming: Boolean,
) {
    val isUser = turn.role == "user"
    val isStreamingAssistant = !isUser && turn.content.isEmpty() && isStreaming
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
            if (isStreamingAssistant) {
                StreamingTypingIndicator()
            }
        }
    }
}

/**
 * Three pulsing dots rendered under the assistant's bubble while a
 * response is in flight. The project uses an "ink-wash, paper-toned"
 * aesthetic so the dots rely on `alpha` (no colour) — black-on-paper
 * fading in and out at staggered phases.
 */
@Composable
private fun StreamingTypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(0, 150, 300).forEachIndexed { idx, delayMs ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = delayMs),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$idx",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(alpha)
                    .background(
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}

/**
 * Pill rendering of a [ToolCallEntity] row. M3 emitted a single line of
 * raw JSON args; M4 wraps it in a paper-toned, ink-coloured pill with a
 * tiny ⌥ marker so the model — and the human reading the transcript —
 * can tell at a glance that this is a tool call, not user-facing prose.
 *
 * The args summary is truncated to keep the pill one line; the full
 * payload is still available in Room for debugging.
 */
@Composable
private fun ToolCallBubble(call: ToolCallEntity) {
    val summary = compactArgsSummary(call.argsJson)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "⌥",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = " ${call.toolName}($summary)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun compactArgsSummary(argsJson: String): String {
    val trimmed = argsJson.trim().trim('{', '}')
    return if (trimmed.length > 60) trimmed.take(57) + "…" else trimmed
}

/**
 * Small error chip rendered below the assistant's placeholder turn when
 * the agent loop fails. Tappable to dismiss.
 */
@Composable
private fun ErrorChip(error: LlmException, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Error: ${error.message ?: error::class.simpleName.orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun InputBar(
    pendingImageUri: String?,
    onClearImage: () -> Unit,
    cameraEnabled: Boolean,
    onCameraClick: () -> Unit,
    onSend: (String) -> Unit,
    enabled: Boolean,
) {
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        pendingImageUri?.let { uri ->
            PendingImagePreview(uri = uri, onClear = onClearImage)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (cameraEnabled) {
                IconButton(onClick = onCameraClick, enabled = enabled) {
                    // Compose's core icon set doesn't ship Camera; draw a
                    // minimal ink-only camera glyph (rectangle body with
                    // a circular lens) so the affordance stays
                    // discoverable without pulling in the
                    // material-icons-extended dependency.
                    Canvas(
                        modifier = Modifier.size(24.dp),
                    ) {
                        val stroke = 1.5f
                        val bodyWidth = size.width * 0.9f
                        val bodyHeight = size.height * 0.6f
                        val bodyLeft = (size.width - bodyWidth) / 2f
                        val bodyTop = size.height * 0.35f
                        // Body
                        drawRect(
                            color = androidx.compose.ui.graphics.Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset(bodyLeft, bodyTop),
                            size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        )
                        // Lens (filled circle)
                        val cx = size.width / 2f
                        val cy = bodyTop + bodyHeight / 2f
                        val r = bodyHeight * 0.32f
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.Black,
                            radius = r,
                            center = androidx.compose.ui.geometry.Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        )
                        // Top hump
                        val humpWidth = bodyWidth * 0.35f
                        val humpLeft = cx - humpWidth / 2f
                        drawRect(
                            color = androidx.compose.ui.graphics.Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset(humpLeft, bodyTop - size.height * 0.08f),
                            size = androidx.compose.ui.geometry.Size(humpWidth, size.height * 0.08f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        )
                    }
                }
            }
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
                    val hasImage = pendingImageUri != null
                    if (text.isNotBlank() || hasImage) {
                        onSend(text)
                        input = ""
                    }
                },
                enabled = enabled && (input.isNotBlank() || pendingImageUri != null),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun PendingImagePreview(uri: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tile background — keeps the preview from looking like an
        // empty rectangle when the camera URI hasn't loaded yet. The
        // real image is persisted on disk; we don't try to decode it
        // in the preview (Coil isn't on the classpath; M5 will swap
        // this for an AsyncImage).
        Box(
            modifier = Modifier
                .height(64.dp)
                .width(64.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(8.dp),
                )
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            Text(
                text = "image",
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.BottomStart),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Discard photo",
            )
        }
    }
}

/**
 * Provider settings sheet.
 *
 * M2/M3 added provider selection; M4 added the "Allow on-device image
 * processing" toggle. M4.5 adds the on-device Ollama model picker: a
 * radio list of locally-installed models fetched from `GET /api/tags`,
 * plus an "Add model" dialog with curated chips and a free-text input
 * that streams a `POST /api/pull` download.
 *
 * The model picker only renders for the `local_ollama` provider kind —
 * cloud providers' model field is set at provider-config time and not
 * user-switchable at runtime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    sheetState: androidx.compose.material3.SheetState,
    active: ProviderConfigEntity?,
    cameraEnabled: Boolean,
    onCameraToggle: (Boolean) -> Unit,
    onActivate: (ProviderConfigEntity) -> Unit,
    onSelectModel: (String) -> Unit,
    curatedModels: List<String>,
    listLocalModels: suspend (String) -> List<String>,
    pullModel: (String, String) -> kotlinx.coroutines.flow.Flow<LlmChunk.PullProgress>,
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
                .verticalScroll(rememberScrollState())
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
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("On-device image processing", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Allows the camera button. Off keeps the conversation text-only.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(checked = cameraEnabled, onCheckedChange = onCameraToggle)
            }

            // M4.5: Ollama model picker. Only meaningful for local Ollama —
            // we hide the whole section for cloud providers to avoid
            // promising a feature that isn't there.
            if (pendingKind == "local_ollama") {
                HorizontalDivider()
                ModelPickerSection(
                    baseUrl = baseUrl,
                    activeModel = modelName,
                    curatedModels = curatedModels,
                    listLocalModels = listLocalModels,
                    pullModel = pullModel,
                    onSelectModel = { id ->
                        modelName = id
                        onSelectModel(id)
                    },
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

/**
 * Renders the Ollama model picker: a radio list of locally-installed
 * models, an "Add model" button that opens the pull dialog, and the
 * pull dialog itself. Self-contained — talks to the ViewModel via the
 * three lambdas passed in.
 */
@Composable
private fun ModelPickerSection(
    baseUrl: String,
    activeModel: String,
    curatedModels: List<String>,
    listLocalModels: suspend (String) -> List<String>,
    pullModel: (String, String) -> kotlinx.coroutines.flow.Flow<LlmChunk.PullProgress>,
    onSelectModel: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var localModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pullDialogOpen by remember { mutableStateOf(false) }

    // Fetch the locally-installed models each time the sheet opens or the
    // base URL changes. A failed fetch (Ollama not running, network blip)
    // silently falls back to an empty list — the user can still pull a
    // model without a registry to consult.
    LaunchedEffect(baseUrl) {
        try {
            localModels = listLocalModels(baseUrl)
            loadError = null
        } catch (e: Exception) {
            localModels = emptyList()
            loadError = e.message ?: "could not fetch installed models"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Model", style = MaterialTheme.typography.titleMedium)
        if (localModels.isEmpty() && loadError != null) {
            Text(
                text = "Could not reach Ollama at $baseUrl",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        } else if (localModels.isEmpty()) {
            Text(
                text = "No models installed yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // Render one row per locally-installed model; the active row is
        // checked. Tapping a different row fires onSelectModel, which
        // updates the text field above (so a subsequent Save persists
        // the change) and invalidates the router's cached client.
        localModels.forEach { name ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = name == activeModel,
                    onClick = { onSelectModel(name) },
                )
                Text(name, style = MaterialTheme.typography.bodyLarge)
            }
        }
        // The text field is the source of truth for the model name —
        // show the active model there even if it's not in the radio
        // list (e.g. the user just typed a custom name).
        if (activeModel.isNotBlank() && activeModel !in localModels) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = true, onClick = {})
                Text(activeModel, style = MaterialTheme.typography.bodyLarge)
            }
        }

        Button(
            onClick = { pullDialogOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add model") }

        if (pullDialogOpen) {
            PullModelDialog(
                baseUrl = baseUrl,
                curatedModels = curatedModels,
                pullModel = pullModel,
                onDismiss = {
                    pullDialogOpen = false
                    // Refresh the local-models list — a successful pull
                    // just made a new name available on the server.
                    coroutineScope.launch {
                        try {
                            localModels = listLocalModels(baseUrl)
                        } catch (_: Exception) {
                            // Leave the list as-is; the user can pull
                            // again or re-open the sheet.
                        }
                    }
                },
                onPullComplete = { modelId ->
                    // Auto-select the freshly-pulled model so the user
                    // doesn't have to tap it in the radio list.
                    onSelectModel(modelId)
                },
            )
        }
    }
}

/**
 * Modal dialog for pulling a new Ollama model. Shows:
 *  - a curated list of chips (one tap fills the text field)
 *  - a free-text input that accepts any Ollama library name
 *  - a "Pull" button that streams progress from `/api/pull`
 *  - a live progress row (status + percent bar) while the pull runs
 *
 * The dialog dismisses itself when the pull completes; on pull
 * complete, [onPullComplete] receives the model id so the picker can
 * auto-select it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullModelDialog(
    baseUrl: String,
    curatedModels: List<String>,
    pullModel: (String, String) -> kotlinx.coroutines.flow.Flow<LlmChunk.PullProgress>,
    onDismiss: () -> Unit,
    onPullComplete: (String) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var modelId by rememberSaveable { mutableStateOf("") }
    var isPulling by remember { mutableStateOf(false) }
    var pullStatus by remember { mutableStateOf<String?>(null) }
    var pullPercent by remember { mutableStateOf(-1) }
    var pullError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isPulling) onDismiss() },
        title = { Text("Pull a model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose a recommended model or type any Ollama library name.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                // Curated chips: tap to fill the text field. The chips
                // wrap into multiple rows on narrow phones via FlowRow;
                // FlowRow comes from Compose Foundation Layout and isn't
                // in the core BOM yet, so we use a horizontally-scrolling
                // Row instead — acceptable for eight short names.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    curatedModels.take(4).forEach { name ->
                        androidx.compose.material3.AssistChip(
                            onClick = { modelId = name },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            enabled = !isPulling,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    curatedModels.drop(4).forEach { name ->
                        androidx.compose.material3.AssistChip(
                            onClick = { modelId = name },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            enabled = !isPulling,
                        )
                    }
                }
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. llama3.1:8b") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isPulling,
                )
                // Live progress row while a pull is in flight.
                if (isPulling || pullStatus != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            pullStatus ?: "starting…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        if (pullPercent >= 0) {
                            LinearProgressIndicator(
                                progress = { pullPercent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        pullError?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    if (modelId.isBlank() || isPulling) return@TextButton
                    val idToPull = modelId
                    isPulling = true
                    pullStatus = "starting…"
                    pullPercent = -1
                    pullError = null
                    coroutineScope.launch {
                        try {
                            pullModel(baseUrl, idToPull).collect { progress ->
                                when (progress) {
                                    is LlmChunk.PullProgress -> {
                                        pullStatus = progress.status
                                        if (progress.percent >= 0) {
                                            pullPercent = progress.percent
                                        }
                                    }
                                    // The pull flow is typed to
                                    // PullProgress only, so the
                                    // exhaustive `when` doesn't need
                                    // other branches in practice — but
                                    // keeping the else silences future
                                    // variants without crashing the UI.
                                    else -> Unit
                                }
                            }
                            isPulling = false
                            onPullComplete(idToPull)
                            onDismiss()
                        } catch (e: Exception) {
                            isPulling = false
                            pullError = e.message ?: "pull failed"
                        }
                    }
                },
                enabled = modelId.isNotBlank() && !isPulling,
            ) { Text("Pull") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (!isPulling) onDismiss() },
                enabled = !isPulling,
            ) { Text("Cancel") }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ConversationScreenPreview() {
    SidekickTheme {
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

    data class TurnEntry(val turn: TurnEntity) : TranscriptEntry() {
        override val id: Long get() = turn.id
    }

    data class ToolCallEntry(val call: ToolCallEntity) : TranscriptEntry() {
        override val id: Long get() = call.id
    }
}
