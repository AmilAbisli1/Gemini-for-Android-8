package com.example.domain.model

enum class GeminiModel(
    val modelId: String,
    val displayName: String,
    val badgeLabel: String,
    val description: String,
) {
    FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash",
        badgeLabel = "3.8 Flash",
        description = "Balanced intelligence and fast multi-turn responses"
    ),
    ARGON(
        modelId = "gemini-4-argon",
        displayName = "Gemini 4 Argon",
        badgeLabel = "4 Argon",
        description = "Deep reasoning, complex tasks, and visual photo analysis"
    ),
    FLASH_LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite",
        badgeLabel = "3.5 Lite",
        description = "Ultra-low latency for quick answers and summaries"
    );

    companion object {
        fun fromId(modelId: String): GeminiModel =
            entries.firstOrNull { it.modelId == modelId } ?: FLASH
    }
}

enum class GeminiPersona(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultModel: GeminiModel,
    val systemInstruction: String,
    val starterPrompts: List<String>,
) {
    GENERAL(
        id = "general",
        title = "Gemini Assistant",
        subtitle = "Everyday partner for writing, planning & Q&A",
        defaultModel = GeminiModel.FLASH,
        systemInstruction = "You are Gemini, a warm, articulate, and reliable AI assistant on Android. " +
            "Provide clear, scannable answers with concise headings or bullet points when helpful, " +
            "and tailor explanations for easy reading on a mobile screen.",
        starterPrompts = listOf(
            "Plan a focused 3-day weekend trip to Kyoto on a budget",
            "Draft a polite follow-up email after a product interview",
            "Compare SQLite, DataStore, and Cloud Firestore for mobile apps"
        )
    ),
    VISION(
        id = "vision",
        title = "Vision Analyst",
        subtitle = "Photo understanding, OCR & scene analysis with Argon",
        defaultModel = GeminiModel.ARGON,
        systemInstruction = "You are Gemini Vision Analyst, a precision multimodal visual assistant powered by Gemini 4 Argon. " +
            "Inspect uploaded photos thoroughly: transcribe visible text accurately, identify objects, plants, landmarks, or UI issues, " +
            "and explain key visual details clearly.",
        starterPrompts = listOf(
            "Describe everything important in this photo",
            "Extract and organize all readable text from this image",
            "Identify what this is and share helpful context"
        )
    ),
    REASONING(
        id = "reasoning",
        title = "Deep Thinker",
        subtitle = "Step-by-step STEM, math & complex problem solving",
        defaultModel = GeminiModel.ARGON,
        systemInstruction = "You are Gemini Deep Thinker, a rigorous analytical specialist. " +
            "Break complex questions into transparent logical steps, check edge cases and assumptions, " +
            "and conclude with a concise final answer.",
        starterPrompts = listOf(
            "Walk me through how public-key cryptography secures HTTPS step by step",
            "Analyze the trade-offs between eventual consistency and strong consistency",
            "Help me solve a tricky probability puzzle carefully"
        )
    ),
    CODING(
        id = "coding",
        title = "Code Architect",
        subtitle = "Kotlin, Android 8+ & Jetpack Compose engineering mentor",
        defaultModel = GeminiModel.FLASH,
        systemInstruction = "You are Gemini Code Architect, a principal Android engineer. " +
            "Write clean, idiomatic Kotlin and Jetpack Compose compatible with Android 8 (API 26) and newer, " +
            "explain architectural trade-offs concisely, and highlight lifecycle or concurrency pitfalls.",
        starterPrompts = listOf(
            "Write a clean Kotlin Flow debounce search helper for Compose",
            "Explain Android 8 notification channels with minimal Kotlin code",
            "Review coroutine structured concurrency best practices in ViewModels"
        )
    ),
    FAST(
        id = "fast",
        title = "Quick Concierge",
        subtitle = "Instant TL;DRs, definitions & quick translations",
        defaultModel = GeminiModel.FLASH_LITE,
        systemInstruction = "You are Gemini Quick Concierge. Give ultra-concise, accurate, high-signal answers " +
            "in as few words as possible without losing clarity.",
        starterPrompts = listOf(
            "Give me a 3-bullet TL;DR on how lithium-ion fast charging works",
            "Translate 'Where is the nearest train station?' into Japanese & Spanish",
            "5 quick high-protein breakfast ideas under 5 minutes"
        )
    );

    companion object {
        fun fromId(id: String): GeminiPersona =
            entries.firstOrNull { it.id == id } ?: GENERAL
    }
}
