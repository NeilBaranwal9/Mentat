package com.mentat.os.domain.model

sealed interface AiResult<out T> {
    data class Success<T>(val value: T) : AiResult<T>
    data class Failure(val reason: AiError) : AiResult<Nothing>
}

/**
 * Section 8 errors plus three guard states the spec implies but does not name:
 * NO_KEY (no key pasted yet), PAUSED (circuit breaker open) and BUSY (another call in flight).
 */
enum class AiError { OFFLINE, TIMEOUT, RATE_LIMITED, BAD_KEY, SERVER, BAD_JSON, BUDGET_EXHAUSTED, UNKNOWN, NO_KEY, PAUSED, BUSY }

fun AiError.userMessage(): String = when (this) {
    AiError.OFFLINE -> "You're offline. AI features are paused."
    AiError.TIMEOUT -> "Groq took too long to answer. Try again in a moment."
    AiError.RATE_LIMITED -> "Groq rate limit reached. Wait a bit before trying again."
    AiError.BAD_KEY -> "Groq rejected the API key. Check it in Settings."
    AiError.SERVER -> "Groq had a server problem. Try again later."
    AiError.BAD_JSON -> "The AI answer could not be read."
    AiError.BUDGET_EXHAUSTED -> "Daily AI call limit reached. It resets tomorrow (change it in Settings)."
    AiError.UNKNOWN -> "Something went wrong talking to Groq."
    AiError.NO_KEY -> "Add your Groq API key in Settings first."
    AiError.PAUSED -> "AI is paused for a few minutes after repeated failures."
    AiError.BUSY -> "Another AI request is still running."
}
