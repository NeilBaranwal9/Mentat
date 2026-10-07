package com.mentat.domain.validate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Section 4 safety filter, applied to every string in every LLM output:
 * no URLs, no percentages, no "x/10" ratings, no medical claims.
 * (Unknown keys are rejected separately by strict decoding.)
 */
object SafetyFilter {
    private val url = Regex("(?i)(https?://|www\\.)")
    private val percent = Regex("\\b\\d{1,3}\\s?%")
    private val rating = Regex("\\b\\d(\\.\\d)?\\s?/\\s?10\\b")
    private val medical = listOf(
        Regex("(?i)\\bdeficien(t|cy|cies)\\b"),
        Regex("(?i)\\bdiagnos(e|es|is|ed|ing)\\b"),
        Regex("(?i)\\b(cures?|heals?|treats?|treatment for|prevents?)\\b.{0,60}\\b(disease|diabetes|cancer|depression|anxiety disorder|disorder|illness|infection|hypertension)\\b"),
        Regex("(?i)\\b(disease|diabetes|cancer|depression|disorder|illness|hypertension)\\b.{0,60}\\b(you should take|supplements?|medication|dosage|dose of)\\b"),
    )

    fun check(text: String): List<String> = buildList {
        if (url.containsMatchIn(text)) add("contains a URL or web address")
        if (percent.containsMatchIn(text)) add("contains a percentage")
        if (rating.containsMatchIn(text)) add("contains a score out of 10")
        if (medical.any { it.containsMatchIn(text) }) add("contains a medical claim")
    }

    fun isClean(text: String) = check(text).isEmpty()

    fun checkTree(element: JsonElement, path: String = "$"): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (k, v) -> checkTree(v, "$path.$k") }
        is JsonArray -> element.flatMapIndexed { i, v -> checkTree(v, "$path[$i]") }
        is JsonNull -> emptyList()
        is JsonPrimitive -> if (element.isString) check(element.content).map { "$path $it" } else emptyList()
    }
}
