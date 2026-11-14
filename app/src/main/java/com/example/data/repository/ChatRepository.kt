package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.domain.model.ChatMessage
import com.example.domain.model.Conversation
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ChatRepository(
    private val db: FirebaseFirestore,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    fun observeConversations(userId: String): Flow<List<Conversation>> =
        db.collection("users")
            .document(userId)
            .collection("conversations")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    Conversation::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    fun observeMessages(userId: String, conversationId: String): Flow<List<ChatMessage>> =
        db.collection("users")
            .document(userId)
            .collection("conversations")
            .document(conversationId)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    ChatMessage::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }

    suspend fun createConversationWithFirstMessage(
        userId: String,
        title: String,
        personaId: String,
        modelName: String,
        messageText: String,
        hasImage: Boolean,
        localImagePath: String? = null,
        conversationId: String = newDocumentId("conv"),
        messageId: String = newDocumentId("msg"),
    ): Result<String> {
        val convRef = db.collection("users")
            .document(userId)
            .collection("conversations")
            .document(conversationId)
        val msgRef = convRef.collection("messages").document(messageId)

        val safeTitle = title.trim().ifBlank { "New Gemini Chat" }.take(120)
        val previewSource = messageText.trim().ifBlank { if (hasImage) "Photo attached" else "" }
        val safePreview = previewSource.take(300)

        val convPayload = mapOf(
            "userId" to userId,
            "title" to safeTitle,
            "personaId" to personaId,
            "modelName" to modelName,
            "lastMessagePreview" to safePreview,
            "messageCount" to 1L,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )

        val msgPayload = buildMessagePayload(
            userId = userId,
            conversationId = conversationId,
            role = ChatMessage.ROLE_USER,
            text = messageText.take(32000),
            hasImage = hasImage,
            localImagePath = localImagePath,
            modelUsed = null,
        )

        return try {
            val batch = db.batch()
            batch.set(convRef, convPayload)
            batch.set(msgRef, msgPayload)
            batch.commit().await()
            Result.success(conversationId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, convRef.path)
            Result.failure(e)
        }
    }

    suspend fun appendMessage(
        userId: String,
        conversationId: String,
        role: String,
        text: String,
        hasImage: Boolean = false,
        localImagePath: String? = null,
        modelUsed: String? = null,
        personaId: String? = null,
        modelName: String? = null,
        messageId: String = newDocumentId("msg"),
    ): Result<String> {
        val convRef = db.collection("users")
            .document(userId)
            .collection("conversations")
            .document(conversationId)
        val msgRef = convRef.collection("messages").document(messageId)

        val msgPayload = buildMessagePayload(
            userId = userId,
            conversationId = conversationId,
            role = role,
            text = text.take(32000),
            hasImage = hasImage,
            localImagePath = localImagePath,
            modelUsed = modelUsed,
        )

        val previewText = text.trim()
            .replace('\n', ' ')
            .ifBlank { if (hasImage) "Photo attached" else "" }
            .take(300)

        val convUpdates = mutableMapOf<String, Any>(
            "lastMessagePreview" to previewText,
            "messageCount" to FieldValue.increment(1L),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (personaId != null) convUpdates["personaId"] = personaId
        if (modelName != null) convUpdates["modelName"] = modelName

        return try {
            val batch = db.batch()
            batch.set(msgRef, msgPayload)
            batch.update(convRef, convUpdates)
            batch.commit().await()
            Result.success(messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateConversationPersonaAndModel(
        userId: String,
        conversationId: String,
        personaId: String,
        modelName: String,
    ): Result<Unit> {
        val convRef = db.collection("users")
            .document(userId)
            .collection("conversations")
            .document(conversationId)
        return try {
            convRef.update(
                mapOf(
                    "personaId" to personaId,
                    "modelName" to modelName,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, convRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteConversation(userId: String, conversationId: String): Result<Unit> {
        val convRef = db.collection("users")
            .document(userId)
            .collection("conversations")
            .document(conversationId)
        return try {
            val messagesSnap = convRef.collection("messages").get().await()
            val chunks = messagesSnap.documents.chunked(400)
            for (chunk in chunks) {
                val batch = db.batch()
                chunk.forEach { doc -> batch.delete(doc.reference) }
                batch.commit().await()
            }
            convRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, convRef.path)
            Result.failure(e)
        }
    }

    private fun buildMessagePayload(
        userId: String,
        conversationId: String,
        role: String,
        text: String,
        hasImage: Boolean,
        localImagePath: String?,
        modelUsed: String?,
    ): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "userId" to userId,
            "conversationId" to conversationId,
            "role" to role,
            "text" to text,
            "hasImage" to hasImage,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        if (!localImagePath.isNullOrBlank()) {
            map["localImagePath"] = localImagePath.take(500)
        }
        if (!modelUsed.isNullOrBlank()) {
            map["modelUsed"] = modelUsed.take(80)
        }
        return map
    }

    private fun newDocumentId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"
}
