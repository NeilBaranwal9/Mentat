package com.mentat.domain.validate

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Strict JSON for agent outputs: unknown keys are rejected (Section 4). */
object AgentJson {
    val strict: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
        coerceInputValues = false
    }

    /** Lenient JSON for storage and API envelopes. */
    val relaxed: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val fence = Regex("^```[a-zA-Z]*\\s*|\\s*```$")

    fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) s = s.replace(fence, "").trim()
        val first = s.indexOf('{')
        val last = s.lastIndexOf('}')
        return if (first >= 0 && last > first) s.substring(first, last + 1) else s
    }

    data class Decoded<T>(val value: T?, val tree: JsonElement?, val errors: List<String>)

    fun <T> decode(raw: String, serializer: KSerializer<T>): Decoded<T> {
        val text = stripFences(raw)
        val tree = try {
            strict.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return Decoded(null, null, listOf("Output is not valid JSON: ${e.message?.take(160)}"))
        } catch (e: IllegalArgumentException) {
            return Decoded(null, null, listOf("Output is not valid JSON: ${e.message?.take(160)}"))
        }
        if (tree !is JsonObject) return Decoded(null, tree, listOf("Output must be a single JSON object."))
        return try {
            Decoded(strict.decodeFromJsonElement(serializer, tree), tree, emptyList())
        } catch (e: SerializationException) {
            Decoded(null, tree, listOf("Schema error: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}"))
        } catch (e: IllegalArgumentException) {
            Decoded(null, tree, listOf("Schema error: ${e.message?.take(200)}"))
        }
    }
}
