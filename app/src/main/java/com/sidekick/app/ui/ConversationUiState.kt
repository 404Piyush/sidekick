package com.sidekick.app.ui

import com.sidekick.app.data.ProviderConfigEntity
import com.sidekick.app.data.TeammateEntity
import com.sidekick.app.data.ToolCallEntity
import com.sidekick.app.data.TurnEntity
import com.sidekick.app.provider.LlmException

/**
 * UI state exposed by [ConversationViewModel] as a [kotlinx.coroutines.flow.StateFlow].
 *
 * All fields are stable values; Compose sees a new immutable snapshot on each
 * update so recomposition stays tight.
 *
 * @property conversationId The Room row id of the active conversation. `null`
 *                          when no conversation has been opened yet (initial
 *                          state before [ConversationViewModel.start]).
 * @property teammate The teammate profile this conversation targets. `null`
 *                    until the database returns it.
 * @property messages Persisted turns in display order. Empty list before the
 *                    first load.
 * @property toolCallsByTurn Persisted tool-call rows, keyed by parent turn id.
 *                            M3 uses this to render "Coder used read_file(...)"
 *                            inline between assistant messages.
 * @property isStreaming True while the agent loop is mid-flight; controls the
 *                       "stop" button and disables the input bar.
 * @property partialResponse The assistant's in-flight text. Empty string
 *                           when nothing is streaming; populated as chunks
 *                           arrive. Concatenated into the final
 *                           [TurnEntity] when [com.sidekick.app.agent.AgentEvent.TextDone] lands.
 * @property error Last failure, if any. The UI renders it inline; the ViewModel
 *                 clears it on the next sendMessage.
 * @property activeProvider The currently-selected [ProviderConfigEntity],
 *                          read once per stream start from
 *                          [com.sidekick.app.data.dao.ProviderConfigDao.getActive].
 * @property pendingImageUri A freshly-captured photo URI that has not yet
 *                           been sent. The UI renders a thumbnail preview
 *                           above the input bar; tapping the camera
 *                           button again replaces this with a new capture.
 *                           `null` when no image is queued.
 * @property cameraEnabled Whether the camera input is currently usable.
 *                          Toggled by the Settings sheet's "Allow on-device
 *                          image processing" switch.
 */
data class ConversationUiState(
    val conversationId: Long? = null,
    val teammate: TeammateEntity? = null,
    val messages: List<TurnEntity> = emptyList(),
    val toolCallsByTurn: Map<Long, List<ToolCallEntity>> = emptyMap(),
    val isStreaming: Boolean = false,
    val partialResponse: String = "",
    val error: LlmException? = null,
    val activeProvider: ProviderConfigEntity? = null,
    val pendingImageUri: String? = null,
    val cameraEnabled: Boolean = true,
)
