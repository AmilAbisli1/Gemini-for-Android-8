package com.example.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.GeminiAiService
import com.example.data.repository.ChatRepository
import com.example.domain.model.ChatMessage
import com.example.domain.model.Conversation
import com.example.domain.model.GeminiModel
import com.example.domain.model.GeminiPersona
import com.example.util.ImageHelper
import com.example.util.ProcessedAttachment
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConversationsUiState {
    data object Loading : ConversationsUiState
    data class Success(val items: List<Conversation>) : ConversationsUiState
    data class Error(val message: String) : ConversationsUiState
}

data class PendingAttachment(
    val uri: Uri,
    val bitmap: Bitmap,
    val localFilePath: String,
    val width: Int,
    val height: Int,
    val approxSizeKb: Int,
)

data class AiErrorBanner(
    val message: String,
    val failedPrompt: String,
    val failedAttachment: PendingAttachment?,
    val failedModel: GeminiModel,
    val canRetryWithFlash: Boolean,
)

data class ChatUiState(
    val activeConversationId: String? = null,
    val selectedPersona: GeminiPersona = GeminiPersona.GENERAL,
    val selectedModel: GeminiModel = GeminiModel.FLASH,
    val isGenerating: Boolean = false,
    val streamingReply: String? = null,
    val streamingModel: GeminiModel? = null,
    val pendingAttachment: PendingAttachment? = null,
    val isProcessingImage: Boolean = false,
    val aiError: AiErrorBanner? = null,
    val transientNotice: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val repository: ChatRepository,
    private val aiService: GeminiAiService,
    private val currentUserId: String,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val activeConversationIdFlow = MutableStateFlow<String?>(null)
    private var generationJob: Job? = null

    val conversationsState: StateFlow<ConversationsUiState> =
        repository.observeConversations(currentUserId)
            .map<List<Conversation>, ConversationsUiState> { ConversationsUiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                Log.w(TAG, "Error observing conversations", rootError)
                emit(ConversationsUiState.Error(rootError.message ?: "Failed to load chat history"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ConversationsUiState.Loading,
            )

    val messages: StateFlow<List<ChatMessage>> =
        activeConversationIdFlow
            .flatMapLatest { convId ->
                if (convId == null) {
                    flowOf(emptyList())
                } else {
                    repository.observeMessages(currentUserId, convId)
                        .catch { error ->
                            val firestoreError = generateSequence(error) { it.cause }
                                .filterIsInstance<FirebaseFirestoreException>()
                                .firstOrNull()
                            if (firestoreError == null && error is CancellationException) throw error
                            Log.w(TAG, "Error observing messages for $convId", firestoreError ?: error)
                            emit(emptyList())
                        }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = emptyList(),
            )

    fun selectConversation(conversation: Conversation) {
        if (conversation.id == _uiState.value.activeConversationId) return
        stopGeneration()
        val persona = GeminiPersona.fromId(conversation.personaId)
        val model = GeminiModel.fromId(conversation.modelName)
        activeConversationIdFlow.value = conversation.id
        _uiState.update {
            it.copy(
                activeConversationId = conversation.id,
                selectedPersona = persona,
                selectedModel = model,
                streamingReply = null,
                streamingModel = null,
                isGenerating = false,
                aiError = null,
            )
        }
    }

    fun startNewChat(persona: GeminiPersona? = null) {
        stopGeneration()
        val targetPersona = persona ?: _uiState.value.selectedPersona
        activeConversationIdFlow.value = null
        _uiState.update {
            it.copy(
                activeConversationId = null,
                selectedPersona = targetPersona,
                selectedModel = targetPersona.defaultModel,
                streamingReply = null,
                streamingModel = null,
                isGenerating = false,
                pendingAttachment = null,
                aiError = null,
            )
        }
    }

    fun selectPersona(persona: GeminiPersona) {
        val convId = _uiState.value.activeConversationId
        _uiState.update {
            it.copy(
                selectedPersona = persona,
                selectedModel = persona.defaultModel,
            )
        }
        if (convId != null) {
            viewModelScope.launch {
                repository.updateConversationPersonaAndModel(
                    userId = currentUserId,
                    conversationId = convId,
                    personaId = persona.id,
                    modelName = persona.defaultModel.modelId,
                )
            }
        }
    }

    fun selectModel(model: GeminiModel) {
        val convId = _uiState.value.activeConversationId
        _uiState.update { it.copy(selectedModel = model) }
        if (convId != null) {
            viewModelScope.launch {
                repository.updateConversationPersonaAndModel(
                    userId = currentUserId,
                    conversationId = convId,
                    personaId = _uiState.value.selectedPersona.id,
                    modelName = model.modelId,
                )
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            val result = repository.deleteConversation(currentUserId, conversationId)
            if (result.isSuccess) {
                if (_uiState.value.activeConversationId == conversationId) {
                    startNewChat()
                }
            } else {
                _uiState.update {
                    it.copy(transientNotice = "Unable to delete conversation. Please try again.")
                }
            }
        }
    }

    fun onPhotoPicked(context: Context, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingImage = true, aiError = null) }
            val result: Result<ProcessedAttachment> = ImageHelper.processSelectedImage(context, uri)
            result.fold(
                onSuccess = { processed ->
                    val attachment = PendingAttachment(
                        uri = uri,
                        bitmap = processed.bitmap,
                        localFilePath = processed.localFilePath,
                        width = processed.width,
                        height = processed.height,
                        approxSizeKb = processed.approxSizeKb,
                    )
                    _uiState.update { state ->
                        // Switch to Vision Analyst / Gemini 4 Argon when user picks an image if on general default
                        val nextPersona = if (state.activeConversationId == null &&
                            state.selectedPersona == GeminiPersona.GENERAL
                        ) {
                            GeminiPersona.VISION
                        } else {
                            state.selectedPersona
                        }
                        state.copy(
                            isProcessingImage = false,
                            pendingAttachment = attachment,
                            selectedPersona = nextPersona,
                            selectedModel = GeminiModel.ARGON,
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to process picked image", error)
                    _uiState.update {
                        it.copy(
                            isProcessingImage = false,
                            transientNotice = error.localizedMessage ?: "Couldn't read selected photo.",
                        )
                    }
                },
            )
        }
    }

    fun clearPendingAttachment() {
        _uiState.update { it.copy(pendingAttachment = null) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(transientNotice = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(aiError = null) }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        _uiState.update {
            it.copy(
                isGenerating = false,
                streamingReply = null,
                streamingModel = null,
            )
        }
    }

    fun sendMessage(
        rawPrompt: String,
        overrideModel: GeminiModel? = null,
        retryAttachment: PendingAttachment? = null,
        skipSavingUserMessage: Boolean = false,
    ) {
        val currentState = _uiState.value
        if (currentState.isGenerating) return

        val attachment = retryAttachment ?: currentState.pendingAttachment
        val trimmedPrompt = rawPrompt.trim()
        if (trimmedPrompt.isBlank() && attachment == null) return

        val effectivePrompt = trimmedPrompt.ifBlank {
            "Analyze this photo thoroughly and share key details."
        }

        // Photo understanding uses gemini-4-argon by default unless explicitly overridden on fallback retry.
        val effectiveModel = overrideModel
            ?: if (attachment != null) GeminiModel.ARGON else currentState.selectedModel

        val persona = currentState.selectedPersona
        val priorHistory = messages.value

        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    streamingReply = "",
                    streamingModel = effectiveModel,
                    pendingAttachment = if (retryAttachment == null) null else it.pendingAttachment,
                    aiError = null,
                )
            }

            try {
                var targetConversationId = currentState.activeConversationId
                if (!skipSavingUserMessage) {
                    if (targetConversationId == null) {
                        val derivedTitle = deriveTitle(effectivePrompt, attachment != null)
                        val createRes = repository.createConversationWithFirstMessage(
                            userId = currentUserId,
                            title = derivedTitle,
                            personaId = persona.id,
                            modelName = effectiveModel.modelId,
                            messageText = effectivePrompt,
                            hasImage = attachment != null,
                            localImagePath = attachment?.localFilePath,
                        )
                        if (createRes.isFailure) {
                            throw createRes.exceptionOrNull()
                                ?: IllegalStateException("Failed to create conversation")
                        }
                        targetConversationId = createRes.getOrThrow()
                        activeConversationIdFlow.value = targetConversationId
                        _uiState.update {
                            it.copy(
                                activeConversationId = targetConversationId,
                                selectedModel = effectiveModel,
                            )
                        }
                    } else {
                        val appendUserRes = repository.appendMessage(
                            userId = currentUserId,
                            conversationId = targetConversationId,
                            role = ChatMessage.ROLE_USER,
                            text = effectivePrompt,
                            hasImage = attachment != null,
                            localImagePath = attachment?.localFilePath,
                            personaId = persona.id,
                            modelName = effectiveModel.modelId,
                        )
                        if (appendUserRes.isFailure) {
                            throw appendUserRes.exceptionOrNull()
                                ?: IllegalStateException("Failed to save message")
                        }
                    }
                }

                var finalReply = ""
                aiService.streamReply(
                    modelName = effectiveModel.modelId,
                    systemInstruction = persona.systemInstruction,
                    priorMessages = priorHistory,
                    userPrompt = effectivePrompt,
                    attachedBitmap = attachment?.bitmap,
                ).collect { accumulatedText ->
                    finalReply = accumulatedText
                    _uiState.update { it.copy(streamingReply = accumulatedText) }
                }

                val convIdForModel = targetConversationId ?: activeConversationIdFlow.value
                if (convIdForModel != null && finalReply.isNotBlank()) {
                    val saveModelRes = repository.appendMessage(
                        userId = currentUserId,
                        conversationId = convIdForModel,
                        role = ChatMessage.ROLE_MODEL,
                        text = finalReply,
                        hasImage = false,
                        modelUsed = effectiveModel.modelId,
                    )
                    if (saveModelRes.isFailure) {
                        Log.e(
                            TAG,
                            "Failed to persist model reply",
                            saveModelRes.exceptionOrNull(),
                        )
                        _uiState.update {
                            it.copy(transientNotice = "Reply generated, but cloud sync failed.")
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingReply = null,
                        streamingModel = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat turn failed", e)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingReply = null,
                        streamingModel = null,
                        aiError = AiErrorBanner(
                            message = e.localizedMessage ?: "Gemini request failed. Please try again.",
                            failedPrompt = effectivePrompt,
                            failedAttachment = attachment,
                            failedModel = effectiveModel,
                            canRetryWithFlash = effectiveModel == GeminiModel.ARGON,
                        ),
                    )
                }
            }
        }
    }

    private fun deriveTitle(prompt: String, hasImage: Boolean): String {
        val cleaned = prompt.trim().replace("\n", " ")
        return when {
            cleaned.length > 48 -> cleaned.take(45).trimEnd() + "..."
            cleaned.isNotBlank() -> cleaned
            hasImage -> "Photo Analysis"
            else -> "New Gemini Chat"
        }
    }

    private companion object {
        const val TAG = "ChatViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
