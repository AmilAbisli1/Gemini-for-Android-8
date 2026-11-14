package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.domain.model.ChatMessage
import com.example.domain.model.GeminiModel
import com.example.domain.model.GeminiPersona
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createConversationAndAppendMessage_validOwner_succeedsAndEmits() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repo = ChatRepository(firestore)
        val convId = "conv_${UUID.randomUUID().toString().replace("-", "")}"

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createConversationWithFirstMessage(
                userId = aliceUid,
                title = "Analyze mountain photo",
                personaId = GeminiPersona.VISION.id,
                modelName = GeminiModel.ARGON.modelId,
                messageText = "What peak is this?",
                hasImage = true,
                localImagePath = "/data/user/0/app/files/sample.jpg",
                conversationId = convId,
            )
        }
        assertTrue("Create should succeed: ${createResult.exceptionOrNull()}", createResult.isSuccess)

        val appendResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.appendMessage(
                userId = aliceUid,
                conversationId = convId,
                role = ChatMessage.ROLE_MODEL,
                text = "This looks like Mount Rainier at sunrise.",
                hasImage = false,
                modelUsed = GeminiModel.ARGON.modelId,
            )
        }
        assertTrue("Append model turn should succeed: ${appendResult.exceptionOrNull()}", appendResult.isSuccess)

        val conversations = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeConversations(aliceUid).first { list -> list.any { it.id == convId } }
        }
        val savedConv = conversations.first { it.id == convId }
        assertEquals(2L, savedConv.messageCount)
        assertEquals(GeminiModel.ARGON.modelId, savedConv.modelName)

        val messages = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeMessages(aliceUid, convId).first { list -> list.size >= 2 }
        }
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.ROLE_USER, messages[0].role)
        assertEquals(ChatMessage.ROLE_MODEL, messages[1].role)
    }

    @Test
    fun observeConversations_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = ChatRepository(firestore)
        val convId = "conv_${UUID.randomUUID().toString().replace("-", "")}"
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createConversationWithFirstMessage(
                userId = aliceUid,
                title = "Alice private chat",
                personaId = GeminiPersona.GENERAL.id,
                modelName = GeminiModel.FLASH.modelId,
                messageText = "Secret note",
                hasImage = false,
                conversationId = convId,
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = ChatRepository(firestore)
        val error = try {
            withTimeout(FLOW_TIMEOUT_MS) {
                bobRepo.observeConversations(aliceUid).first()
            }
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Bob observing Alice's conversations must fail", error)
        val firestoreException = checkNotNull(error?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $error"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    @Test
    fun observeConversations_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = ChatRepository(firestore)

        val error = try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeConversations("some_uid").first()
            }
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Unauthenticated query must fail", error)
        val firestoreException = checkNotNull(error?.unwrapFirestoreException()) {
            "Expected nested FirebaseFirestoreException, got $error"
        }
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }

    private companion object {
        const val ALICE_EMAIL = "alice@test.com"
        const val BOB_EMAIL = "bob@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
