package com.example

import com.example.domain.model.GeminiModel
import com.example.domain.model.GeminiPersona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiDomainTest {

    @Test
    fun allPersonas_haveSystemInstructionsAndStarterPrompts() {
        GeminiPersona.entries.forEach { persona ->
            assertTrue("${persona.id} needs system instruction", persona.systemInstruction.isNotBlank())
            assertTrue("${persona.id} needs starter prompts", persona.starterPrompts.isNotEmpty())
        }
    }

    @Test
    fun visionAndReasoningPersonas_defaultToGemini4Argon() {
        assertEquals(GeminiModel.ARGON, GeminiPersona.VISION.defaultModel)
        assertEquals("gemini-4-argon", GeminiPersona.VISION.defaultModel.modelId)
        assertEquals(GeminiModel.ARGON, GeminiPersona.REASONING.defaultModel)
    }

    @Test
    fun modelLookup_unknownId_fallsBackToFlash() {
        assertEquals(GeminiModel.FLASH, GeminiModel.fromId("unknown-model"))
        assertEquals(GeminiModel.ARGON, GeminiModel.fromId("gemini-4-argon"))
    }
}
