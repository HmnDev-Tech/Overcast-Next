package com.sameerasw.overcast.weather.ai

data class AiProvider(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val defaultBaseUrl: String?,
    val signupUrl: String?,
    val needsBaseUrl: Boolean = false,
) {
    val isOff: Boolean get() = id == AiProviders.OFF_ID
}

object AiProviders {
    const val OFF_ID = "off"

    val off = AiProvider(
        id = OFF_ID,
        displayName = "",
        defaultModel = "",
        defaultBaseUrl = null,
        signupUrl = null,
    )
    val gemini = AiProvider(
        id = "gemini",
        displayName = "Gemini (AI Studio)",
        defaultModel = "gemini-3.5-flash-lite",
        defaultBaseUrl = null,
        signupUrl = "https://aistudio.google.com/apikey",
    )
    val openai = AiProvider(
        id = "openai",
        displayName = "OpenAI",
        defaultModel = "gpt-4o-mini",
        defaultBaseUrl = "https://api.openai.com/v1",
        signupUrl = "https://platform.openai.com/api-keys",
        needsBaseUrl = true,
    )
    val openrouter = AiProvider(
        id = "openrouter",
        displayName = "OpenRouter",
        defaultModel = "google/gemini-2.5-flash-lite",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        signupUrl = "https://openrouter.ai/keys",
        needsBaseUrl = true,
    )

    val all: List<AiProvider> = listOf(off, gemini, openai, openrouter)

    fun byId(id: String?): AiProvider = all.firstOrNull { it.id == id } ?: off
}
