package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.domain.model.ChatMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

class GeminiAiService {

    private val modelCache = mutableMapOf<Pair<String, String>, GenerativeModel>()

    private fun getOrCreateModel(
        modelName: String,
        systemInstructionText: String,
    ): GenerativeModel {
        val key = modelName to systemInstructionText
        return modelCache.getOrPut(key) {
            // Lazily evaluated on first inference rather than at class construction time.
            Firebase.ai.generativeModel(
                modelName = modelName,
                generationConfig = generationConfig {
                    temperature = 0.7f
                },
                systemInstruction = content {
                    text(systemInstructionText)
                }
            )
        }
    }

    fun streamReply(
        modelName: String,
        systemInstruction: String,
        priorMessages: List<ChatMessage>,
        userPrompt: String,
        attachedBitmap: Bitmap?,
    ): Flow<String> = flow {
        val model = getOrCreateModel(modelName, systemInstruction)
        val historyContent = buildChatHistory(priorMessages)
        val chat = model.startChat(history = historyContent)

        val promptContent = content("user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            val effectivePrompt = userPrompt.trim().ifBlank {
                "Analyze this image in detail."
            }
            text(effectivePrompt)
        }

        val accumulated = StringBuilder()
        try {
            withTimeout(AI_TIMEOUT_MS) {
                chat.sendMessageStream(promptContent).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        emit(accumulated.toString())
                    }
                }
            }
            if (accumulated.isBlank()) {
                throw IllegalStateException("Gemini returned an empty response. Please try again.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini streaming timed out after ${AI_TIMEOUT_MS}ms", e)
            throw TimeoutException("Gemini took longer than 60 seconds to respond. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini streaming failed for model=$modelName", e)
            throw e
        }
    }

    private fun buildChatHistory(messages: List<ChatMessage>): List<Content> {
        // Keep recent context bounded and alternate clean text turns for multi-turn continuity.
        return messages.takeLast(MAX_HISTORY_TURNS)
            .filter { it.text.isNotBlank() || it.hasImage }
            .map { msg ->
                val role = if (msg.role == ChatMessage.ROLE_MODEL) "model" else "user"
                val textBody = when {
                    msg.text.isNotBlank() && msg.hasImage -> "[Attached photo]\n${msg.text}"
                    msg.hasImage -> "[Attached photo]"
                    else -> msg.text
                }
                content(role) {
                    text(textBody)
                }
            }
    }

    class TimeoutException(message: String, cause: Throwable) : Exception(message, cause)

    private companion object {
        const val TAG = "GeminiAiService"
        const val AI_TIMEOUT_MS = 60_000L
        const val MAX_HISTORY_TURNS = 24
    }
}
