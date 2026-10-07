package com.mentat.os.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

/** OpenAI-compatible Groq endpoints. Base URL comes from BuildConfig (public, non-secret). */
interface GroqApi {
    @POST("chat/completions")
    suspend fun chat(@Header("Authorization") auth: String, @Body body: ChatRequest): ChatResponse

    @GET("models")
    suspend fun models(@Header("Authorization") auth: String): ModelsResponse
}

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<Msg>,
    val temperature: Double = 0.2,
    @SerialName("response_format") val responseFormat: ResponseFormat = ResponseFormat(),
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int,
    /** Only sent for reasoning models (gpt-oss); null is omitted from the JSON body. */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class Msg(val role: String, val content: String? = null)

@Serializable
data class ResponseFormat(val type: String = "json_object")

@Serializable
data class ChatResponse(val choices: List<Choice> = emptyList())

@Serializable
data class Choice(val message: Msg? = null, @SerialName("finish_reason") val finishReason: String? = null)

@Serializable
data class ModelsResponse(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(val id: String, val active: Boolean? = null)
