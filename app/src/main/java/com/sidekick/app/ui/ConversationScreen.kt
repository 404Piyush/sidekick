package com.sidekick.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.sidekick.app.ui.components.chat.AnimatedMessageBubble
import com.sidekick.app.ui.components.chat.DateSeparator
import com.sidekick.app.ui.components.chat.MarkdownText
import com.sidekick.app.ui.components.chat.StreamingCursor
import com.sidekick.app.ui.components.chat.TeammateAvatar
import com.sidekick.app.ui.components.chat.TeammateIcon
import com.sidekick.app.ui.components.chat.TypingIndicator
import com.sidekick.app.ui.theme.SidekickTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * Streaming conversation screen wired to [ConversationViewModel].
 *
 * M8 overhaul:
 *  - **Markdown rendering** — assistant replies render through
 *    [MarkdownText] (headers, lists, bold/italic, fenced code blocks
 *    with copy-to-clipboard)
 *  - **Animated message bubbles** — every turn fades + slides in on
 *    first appearance via [AnimatedMessageBubble]
 *  - **Per-teammate avatars** — assistant bubbles get a small circular
 *    avatar with the teammate's glyph (code / wrench / magnifier)
 *  - **Typing indicator** — three pulsing dots while the LLM streams,
 *    rendered as part of the active assistant turn
 *  - **Date separators** — "Today" / "Yesterday" / "Aug 30" rows
 *    inserted between turns when the gap crosses the threshold
 *  - **Polished tool-call pills** — the `Used read_file(...)` chip
 *    uses a low-emphasis background so it doesn't compete with the
 *    prose
 *  - **Camera preview thumbnail** — already present, slightly
 *    restyled with a paper-toned background
 *
 * The camera / settings sheet / model picker plumbing from M4-M5 is
 * preserved verbatim.
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
                    // Build the entries list interleaving turns, tool calls,
                    // and date separators. The LazyColumn renders them flat
                    // (nested `items` calls aren't supported).
                    val teammateIcon = TeammateIcon.fromSlug(teammateSlug)
                    val entries: List<TranscriptEntry> = buildList {
                        var prevTimestamp: Long = 0L
                        for (turn in state.messages) {
                            if (DateSeparator.shouldInsert(prevTimestamp, turn.createdAt)) {
                                add(TranscriptEntry.DateSeparatorEntry(DateSeparator.labelFor(turn.createdAt)))
                            }
                            add(TranscriptEntry.TurnEntry(turn))
                            state.toolCallsByTurn[turn.id].orEmpty().forEach { call ->
                                add(TranscriptEntry.ToolCallEntry(call))
                            }
                            prevTimestamp = turn.createdAt
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
                                    teammateIcon = teammateIcon,
                                    teammateTitle = teammateTitle,
                                )
                                is TranscriptEntry.ToolCallEntry -> ToolCallBubble(call = entry.call)
                                is TranscriptEntry.DateSeparatorEntry -> DateSeparatorRow(text = entry.label)
                            }
                        }
                        state.error?.let { err ->
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
            onDeviceModelReady = state.onDeviceModelReady,
            onDeviceDownloading = state.onDeviceDownloading,
            onDeviceDownloadPercent = state.onDeviceDownloadPercent,
            onDownloadOnDevice = { viewModel.downloadOnDeviceModel() },
            onActivateOnDevice = { viewModel.activateOnDeviceModel() },
        )
    }
}

/**
 * Empty-state placeholder shown before the first user message lands.
 * Reused from M4 — no M8 changes needed.
 */
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

/**
 * One turn in the transcript. M8 changed the layout from a flat text
 * block to a left-aligned (assistant) or right-aligned (user) bubble
 * with the teammate avatar visible on the assistant side.
 */
@Composable
private fun TurnBubble(
    turn: TurnEntity,
    partialText: String,
    isStreaming: Boolean,
    teammateIcon: TeammateIcon,
    teammateTitle: String,
) {
    val isUser = turn.role == "user"
    val isStreamingAssistant = !isUser && turn.content.isEmpty() && isStreaming
    val displayContent = if (isStreamingAssistant && partialText.isNotEmpty()) partialText else turn.content
    val visible = displayContent.isNotEmpty() || isStreamingAssistant

    AnimatedMessageBubble(visible = visible, fromUser = isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!isUser) {
                TeammateAvatar(icon = teammateIcon, isActive = isStreamingAssistant)
                Box(modifier = Modifier.size(8.dp))
            }
            Column(
                modifier = Modifier
                    .background(
                        color = if (isUser) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(16.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = if (isUser) "You" else teammateTitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Box(modifier = Modifier.size(4.dp))
                if (isUser) {
                    // User messages render as plain text — markdown in
                    // the input box is a footgun (you'd render your
                    // literal backticks).
                    Text(
                        text = displayContent,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    MarkdownText(source = displayContent)
                    if (isStreamingAssistant) {
                        TypingIndicator()
                        StreamingCursor()
                    }
                }
            }
            if (isUser) {
                Box(modifier = Modifier.size(8.dp))
                // User gets a placeholder right-side avatar slot so the
                // bubble doesn't lean all the way to the edge. The
                // circle stays empty — the "You" label inside the
                // bubble is enough identification.
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(50),
                        ),
                )
            }
        }
    }
}

/**
 * "Today" / "Yesterday" / "Aug 30" row inserted between two consecutive
 * turns when the gap crosses [DateSeparator.MIN_GAP_MINUTES] or the
 * calendar day changes.
 *
 * Renders as a small centred chip in the transcript gutter so it
 * doesn't compete with the prose around it.
 */
@Composable
private fun DateSeparatorRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * M8 styling for the tool-call pill: subtle paper-toned background, no
 * border (it was too noisy), and a left-side monospace `↳` so the user
 * can tell at a glance which entry is a tool invocation vs. a turn.
 */
@Composable
private fun ToolCallBubble(call: ToolCallEntity) {
    val summary = compactArgsSummary(call.argsJson)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = "↳",
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
 * Inline error chip — kept small enough to live below the assistant
 * bubble without forcing a layout shift.
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
                    Canvas(modifier = Modifier.size(24.dp)) {
                        val stroke = 1.5f
                        val bodyWidth = size.width * 0.9f
                        val bodyHeight = size.height * 0.6f
                        val bodyLeft = (size.width - bodyWidth) / 2f
                        val bodyTop = size.height * 0.35f
                        drawRect(
                            color = androidx.compose.ui.graphics.Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset(bodyLeft, bodyTop),
                            size = androidx.compose.ui.geometry.Size(bodyWidth, bodyHeight),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        )
                        val cx = size.width / 2f
                        val cy = bodyTop + bodyHeight / 2f
                        val r = bodyHeight * 0.32f
                        drawCircle(
                            color = androidx.compose.ui.graphics.Color.Black,
                            radius = r,
                            center = androidx.compose.ui.geometry.Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                        )
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
 * Provider settings sheet — preserved verbatim from M4.5 so the M8
 * UI overhaul doesn't churn the settings surface.
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
    onDeviceModelReady: Boolean,
    onDeviceDownloading: Boolean,
    onDeviceDownloadPercent: Int,
    onDownloadOnDevice: () -> Unit,
    onActivateOnDevice: () -> Unit,
) {
    var baseUrl by remember(active?.id) {
        mutableStateOf(active?.baseUrl ?: "http://10.0.2.2:11434")
    }
    var modelName by remember(active?.id) {
        mutableStateOf(active?.modelName ?: "qwen2.5-coder:1.5b")
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
            Text("On-device model", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Runs fully on your phone. No internet, no cloud.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            when {
                onDeviceModelReady -> {
                    Text(
                        text = "Qwen3-0.6B — ready",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onActivateOnDevice,
                        enabled = active?.providerKind != "local_on_device",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (active?.providerKind == "local_on_device") {
                                "On-device model active"
                            } else {
                                "Use on-device model"
                            },
                        )
                    }
                }
                onDeviceDownloading -> {
                    Text(
                        text = if (onDeviceDownloadPercent >= 0) {
                            "Downloading model… $onDeviceDownloadPercent%"
                        } else {
                            "Downloading model…"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (onDeviceDownloadPercent >= 0) {
                                onDeviceDownloadPercent / 100f
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    Button(
                        onClick = onDownloadOnDevice,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Download model (~328 MB)")
                    }
                }
            }
            HorizontalDivider()

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
                    coroutineScope.launch {
                        try {
                            localModels = listLocalModels(baseUrl)
                        } catch (_: Exception) {
                        }
                    }
                },
                onPullComplete = { modelId ->
                    onSelectModel(modelId)
                },
            )
        }
    }
}

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    curatedModels.take(4).forEach { name ->
                        AssistChip(
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
                        AssistChip(
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
            TextButton(
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
            TextButton(
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
 * One item in the rendered transcript. M8 added [DateSeparatorEntry]
 * so the LazyColumn can render date chips inline with turns and tool
 * calls. Sealed so the `when` in [ConversationScreen] is exhaustive.
 */
private sealed class TranscriptEntry {
    abstract val id: Long

    data class TurnEntry(val turn: TurnEntity) : TranscriptEntry() {
        override val id: Long get() = turn.id
    }

    data class ToolCallEntry(val call: ToolCallEntity) : TranscriptEntry() {
        override val id: Long get() = call.id
    }

    /**
     * Date separator row inserted between two consecutive turns when
     * the gap crosses [com.sidekick.app.ui.components.chat.DateSeparator.MIN_GAP_MINUTES]
     * or the calendar day changes.
     */
    data class DateSeparatorEntry(val label: String) : TranscriptEntry() {
        // Use a stable hash of the label so identical labels don't
        // collide on the LazyColumn key.
        override val id: Long get() = -label.hashCode().toLong()
    }
}