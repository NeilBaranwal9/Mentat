package com.mentat.data.net

import com.mentat.data.prefs.SettingsStore
import com.mentat.data.secret.SecretStore
import com.mentat.domain.DispatcherProvider
import com.mentat.domain.agent.ChatMessage
import com.mentat.domain.agent.LlmCaller
import com.mentat.domain.model.AiError
import com.mentat.domain.model.AiResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groq chat completions in JSON mode, behind [AiGuard] and [safeAiCall].
 * Retries at most once for TIMEOUT/SERVER after 2 s; never retries 429.
 */
@Singleton
class GroqClient @Inject constructor(
    private val api: GroqApi,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
    private val guard: AiGuard,
    private val dispatchers: DispatcherProvider,
) : LlmCaller {

    override suspend fun call(messages: List<ChatMessage>, maxTokens: Int): AiResult<String> = withContext(dispatchers.io) {
        val key = secrets.groqKey()
        if (key.isNullOrBlank()) return@withContext AiResult.Failure(AiError.NO_KEY)
        val first = guard.guarded { once(key, messages, maxTokens) }
        if (first is AiResult.Failure && (first.reason == AiError.TIMEOUT || first.reason == AiError.SERVER)) {
            delay(2_000)
            guard.guarded { once(key, messages, maxTokens) }
        } else {
            first
        }
    }

    private suspend fun once(key: String, messages: List<ChatMessage>, maxTokens: Int): AiResult<String> {
        val model = settings.current().model
        val reasoning = if (model.startsWith("openai/gpt-oss")) "low" else null
        val result = safeAiCall {
            api.chat(
                auth = "Bearer $key",
                body = ChatRequest(
                    model = model,
                    messages = messages.map { Msg(it.role, it.content) },
                    maxCompletionTokens = maxTokens,
                    reasoningEffort = reasoning,
                ),
            )
        }
        return when (result) {
            is AiResult.Failure -> result
            is AiResult.Success -> {
                val content = result.value.choices.firstOrNull()?.message?.content
                if (content.isNullOrBlank()) AiResult.Failure(AiError.BAD_JSON) else AiResult.Success(content)
            }
        }
    }

    /** Explicit user action from Settings. Not an LLM call, so it does not count against the daily cap. */
    suspend fun listModels(): AiResult<List<String>> = withContext(dispatchers.io) {
        val key = secrets.groqKey()
        if (key.isNullOrBlank()) return@withContext AiResult.Failure(AiError.NO_KEY)
        safeAiCall { api.models("Bearer $key").data.filter { it.active != false }.map { it.id }.sorted() }
    }
}
