package com.mentat.domain.validate

import com.mentat.domain.model.GoalMap
import com.mentat.domain.model.NormalizedCuriosity
import com.mentat.domain.model.ParsedCommands
import com.mentat.domain.model.RewrittenTask
import com.mentat.domain.model.SkillBlueprint
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun words(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

/** Section 7 cross-checks. [aiRules] = full AI ranges; manual entry uses relaxed counts but the same structure rules. */
object BlueprintValidator {
    fun validate(bp: SkillBlueprint, aiRules: Boolean = true): List<String> = buildList {
        if (bp.skillName.isBlank()) add("skill_name is empty")
        if (aiRules && bp.domain.isBlank()) add("domain is empty")
        val styleRange = if (aiRules) 5..7 else 0..7
        if (bp.styles.size !in styleRange) add("styles must have ${styleRange.first}-${styleRange.last} items, got ${bp.styles.size}")
        bp.styles.forEachIndexed { i, s ->
            if (s.name.isBlank()) add("styles[$i].name is empty")
            if (s.difficulty !in 1..3) add("styles[$i].difficulty must be 1-3")
            if (s.minCostInr < 0) add("styles[$i].min_cost_inr must not be negative")
        }
        val ss = bp.samplerSession
        if (ss.title.isBlank()) add("sampler_session.title is empty")
        if (ss.minutes !in 15..30) add("sampler_session.minutes must be 15-30, got ${ss.minutes}")
        if (ss.tinyMinutes !in 3..10) add("sampler_session.tiny_minutes must be 3-10, got ${ss.tinyMinutes}")
        if (ss.tinyMinutes >= ss.minutes) add("sampler_session.tiny_minutes must be less than minutes")
        if (aiRules && ss.steps.isEmpty()) add("sampler_session.steps is empty")

        val msRange = if (aiRules) 4..6 else 1..12
        if (bp.miniSkills.size !in msRange) add("mini_skills must have ${msRange.first}-${msRange.last} items, got ${bp.miniSkills.size}")
        val ids = bp.miniSkills.map { it.localId }
        if (ids.toSet().size != ids.size) add("mini_skills local_id values must be unique")
        val seen = mutableSetOf<String>()
        val estRange = if (aiRules) 5..90 else 5..120
        bp.miniSkills.forEachIndexed { i, ms ->
            val at = "mini_skills[$i]"
            if (aiRules && ms.localId != "m${i + 1}") add("$at.local_id must be \"m${i + 1}\"")
            if (ms.name.isBlank()) add("$at.name is empty")
            if (ms.estHours <= 0) add("$at.est_hours must be positive")
            ms.prerequisiteLocalIds.forEach { p ->
                if (p !in seen) add("$at.prerequisite_local_ids may only reference earlier ids, \"$p\" is not earlier")
            }
            val actRange = if (aiRules) 1..3 else 1..10
            if (ms.activities.size !in actRange) add("$at.activities must have ${actRange.first}-${actRange.last} items, got ${ms.activities.size}")
            ms.activities.forEachIndexed { j, a ->
                val aa = "$at.activities[$j]"
                if (a.title.isBlank()) add("$aa.title is empty")
                if (a.tinyTitle.isBlank()) add("$aa.tiny_title is empty (every activity needs a tiny version)")
                if (a.estMinutes !in estRange) add("$aa.est_minutes must be ${estRange.first}-${estRange.last}, got ${a.estMinutes}")
                if (a.tinyMinutes !in 3..10) add("$aa.tiny_minutes must be 3-10, got ${a.tinyMinutes}")
                if (a.tinyMinutes >= a.estMinutes) add("$aa.tiny_minutes must be less than est_minutes")
            }
            val rqRange = if (aiRules) 3..5 else 0..10
            if (ms.recallQuestions.size !in rqRange) add("$at.recall_questions must have ${rqRange.first}-${rqRange.last} items, got ${ms.recallQuestions.size}")
            if (aiRules && ms.teachBackPrompt.isBlank()) add("$at.teach_back_prompt is empty")
            seen += ms.localId
        }
        if (hasCycle(bp.miniSkills.associate { it.localId to it.prerequisiteLocalIds })) add("prerequisite graph has a cycle")

        if (aiRules || bp.capstone.title.isNotBlank()) {
            if (bp.capstone.title.isBlank()) add("capstone.title is empty")
            if (bp.capstone.estHours < 0) add("capstone.est_hours must not be negative")
        }
        if (aiRules) {
            val sum = bp.miniSkills.sumOf { it.estHours } + bp.capstone.estHours
            if (sum > 0 && (bp.totalHours < 0.7 * sum || bp.totalHours > 1.5 * sum)) {
                add("total_hours_to_basic_competence (${bp.totalHours}) must be within 0.7x-1.5x of the sum of mini-skill and capstone hours (${"%.1f".format(sum)})")
            }
        }
        val kinds = setOf("book", "tool", "kit", "course", "free_material", "other")
        bp.resources.forEachIndexed { i, r ->
            if (r.kind !in kinds) add("resources[$i].kind must be one of $kinds")
            if (r.estCostInr < 0) add("resources[$i].est_cost_inr must not be negative")
        }
    }
}

fun hasCycle(graph: Map<String, List<String>>): Boolean {
    val state = mutableMapOf<String, Int>()
    fun visit(n: String): Boolean {
        when (state[n]) { 1 -> return true; 2 -> return false }
        state[n] = 1
        for (m in graph[n].orEmpty()) if (m in graph && visit(m)) return true
        state[n] = 2
        return false
    }
    return graph.keys.sorted().any { visit(it) }
}

/** A5 param shapes (Section 9). Commands with confidence "low" may leave params unresolved (gate G4 handles them). */
object CommandValidator {
    val TYPES = setOf(
        "add_commitment", "add_exam_mode", "end_exam_mode", "set_sleep_window", "add_curiosity",
        "pause_skill", "resume_skill", "set_priority", "request_task", "log_energy", "unknown",
    )
    private val isoMinute = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    fun validate(pc: ParsedCommands, knownSkillIds: Set<String>): List<String> = buildList {
        if (pc.commands.isEmpty()) add("commands must contain at least one command (use \"unknown\" for small talk)")
        pc.commands.forEachIndexed { i, c ->
            val at = "commands[$i]"
            if (c.type !in TYPES) add("$at.type \"${c.type}\" is not a known command type")
            if (c.confidence !in setOf("low", "medium", "high")) add("$at.confidence must be low, medium or high")
            if (c.sourceQuote.isBlank()) add("$at.source_quote is empty")
            val low = c.confidence == "low"
            addAll(paramErrors(c.type, c.params, knownSkillIds, low).map { "$at.params $it" })
        }
        if (pc.commands.any { it.confidence == "low" } && pc.clarifyingQuestion.isNullOrBlank()) {
            add("clarifying_question is required when any command has confidence \"low\"")
        }
    }

    fun paramErrors(type: String, p: JsonObject, skills: Set<String>, low: Boolean): List<String> = buildList {
        fun str(k: String) = (p[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun int(k: String) = (p[k] as? JsonPrimitive)?.intOrNull
        fun need(k: String, ok: Boolean, what: String) {
            if (p[k] == null || (p[k] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) { if (!low) add("missing \"$k\"") }
            else if (!ok) add("\"$k\" must be $what")
        }
        val allowed: Set<String> = when (type) {
            "add_commitment" -> {
                need("title", !str("title").isNullOrBlank(), "a string")
                need("kind", str("kind") in setOf("class", "exam", "other"), "class, exam or other")
                need("start_iso", parse(str("start_iso")) { LocalDateTime.parse(it, isoMinute) }, "YYYY-MM-DDTHH:MM")
                need("end_iso", parse(str("end_iso")) { LocalDateTime.parse(it, isoMinute) }, "YYYY-MM-DDTHH:MM")
                need("recurrence", str("recurrence") in setOf("none", "daily", "weekly"), "none, daily or weekly")
                val s = str("start_iso")?.let { runCatching { LocalDateTime.parse(it, isoMinute) }.getOrNull() }
                val e = str("end_iso")?.let { runCatching { LocalDateTime.parse(it, isoMinute) }.getOrNull() }
                if (s != null && e != null && !e.isAfter(s)) add("end_iso must be after start_iso")
                setOf("title", "kind", "start_iso", "end_iso", "recurrence")
            }
            "add_exam_mode" -> {
                need("start_date", parse(str("start_date")) { LocalDate.parse(it) }, "YYYY-MM-DD")
                need("end_date", parse(str("end_date")) { LocalDate.parse(it) }, "YYYY-MM-DD")
                setOf("start_date", "end_date")
            }
            "end_exam_mode" -> emptySet()
            "set_sleep_window" -> {
                need("start_time", parse(str("start_time")) { LocalTime.parse(it) }, "HH:MM")
                need("end_time", parse(str("end_time")) { LocalTime.parse(it) }, "HH:MM")
                setOf("start_time", "end_time")
            }
            "add_curiosity" -> { need("text", !str("text").isNullOrBlank(), "a string"); setOf("text") }
            "pause_skill", "resume_skill" -> { need("skill_id", str("skill_id") in skills, "one of the active skill ids"); setOf("skill_id") }
            "set_priority" -> {
                need("skill_id", str("skill_id") in skills, "one of the active skill ids")
                need("priority", int("priority") in 1..5, "an integer 1-5")
                setOf("skill_id", "priority")
            }
            "request_task" -> { need("minutes", (int("minutes") ?: 0) in 1..600, "a positive integer"); setOf("minutes") }
            "log_energy" -> { need("level", int("level") in 1..5, "an integer 1-5"); setOf("level") }
            "unknown" -> { need("text", true, "a string"); setOf("text") }
            else -> p.keys
        }
        (p.keys - allowed).forEach { add("has unknown key \"$it\"") }
    }

    private inline fun parse(v: String?, f: (String) -> Any): Boolean = v != null && runCatching { f(v) }.isSuccess
}

object RewrittenTaskValidator {
    private val judging = Regex("(?i)\\b(good|great|well|nicely|quality|talent(ed)?|beautiful|perfect(ly)?|better|best|skilled|impressive)\\b")

    fun validate(t: RewrittenTask, originalEst: Int): List<String> = buildList {
        if (t.title.isBlank()) add("title is empty")
        if (t.steps.size !in 3..6) add("steps must have 3-6 items, got ${t.steps.size}")
        if (t.steps.any { it.isBlank() }) add("steps must not be empty")
        val max = (originalEst * 1.2).toInt()
        if (t.estMinutes < 1 || t.estMinutes > max) add("est_minutes must be 1-$max (at most 1.2x the original $originalEst)")
        if (t.tinyTitle.isBlank()) add("tiny_title is empty")
        if (t.tinyMinutes !in 3..10) add("tiny_minutes must be 3-10")
        if (t.tinyMinutes >= t.estMinutes) add("tiny_minutes must be less than est_minutes")
        if (t.selfCheck.size !in 2..4) add("self_check must have 2-4 items, got ${t.selfCheck.size}")
        t.selfCheck.forEachIndexed { i, s ->
            if (!s.trim().endsWith("?")) add("self_check[$i] must be a yes/no question ending with '?'")
            if (judging.containsMatchIn(s)) add("self_check[$i] must not judge quality, skill or talent")
        }
    }
}

object CuriosityValidator {
    fun validate(n: NormalizedCuriosity, inboxIds: Set<String>, activeSkillNames: Set<String>): List<String> = buildList {
        if (n.normalizedTitle.isBlank()) add("normalized_title is empty")
        if (words(n.normalizedTitle) > 8) add("normalized_title must be 8 words or fewer")
        if (n.domain.isBlank()) add("domain is empty")
        if (words(n.hook) > 20) add("hook must be 20 words or fewer")
        if (n.samplerMinutes !in 15..30) add("sampler_minutes must be 15-30")
        n.duplicateOfId?.let { if (it !in inboxIds) add("duplicate_of_id \"$it\" is not an existing inbox id") }
        n.relatedActiveSkills.forEach { if (it !in activeSkillNames) add("related_active_skills contains \"$it\" which is not an active skill name") }
    }
}

object GoalMapValidator {
    private val feasibility = Regex("(?i)\\b(unrealistic|realistic|achievable|unachievable|feasible|infeasible|impossible|probability|chances?|likelihood)\\b")

    fun validate(g: GoalMap): List<String> = buildList {
        if (g.goalTitle.isBlank()) add("goal_title is empty")
        if (g.requiredSkills.size !in 4..12) add("required_skills must have 4-12 items, got ${g.requiredSkills.size}")
        g.suggestedTargetDate?.let { if (runCatching { LocalDate.parse(it) }.isFailure) add("suggested_target_date must be YYYY-MM-DD or null") }
        val names = g.requiredSkills.map { it.name.trim().lowercase() }
        if (names.toSet().size != names.size) add("required_skills names must be unique")
        g.requiredSkills.forEachIndexed { i, s ->
            val at = "required_skills[$i]"
            if (s.name.isBlank()) add("$at.name is empty")
            if (s.importance !in setOf("core", "supporting", "optional")) add("$at.importance must be core, supporting or optional")
            if (s.estHours <= 0) add("$at.est_hours must be positive")
            if (words(s.reason) > 25) add("$at.reason must be 25 words or fewer")
            s.dependsOn.forEach { d ->
                if (d.trim().lowercase() !in names) add("$at.depends_on \"$d\" is not a name in required_skills")
                if (d.trim().equals(s.name.trim(), ignoreCase = true)) add("$at depends on itself")
            }
            if (feasibility.containsMatchIn(s.reason)) add("$at.reason must not judge feasibility")
        }
        val graph = g.requiredSkills.associate { s -> s.name.trim().lowercase() to s.dependsOn.map { it.trim().lowercase() } }
        if (hasCycle(graph)) add("depends_on contains a cycle")
        if (feasibility.containsMatchIn(g.goalTitle) || g.assumptions.any { feasibility.containsMatchIn(it) }) add("goal text must not judge feasibility")
    }
}
