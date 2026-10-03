package com.polymath.os.domain.agent

import com.polymath.os.domain.model.AiError
import com.polymath.os.domain.model.AiResult
import com.polymath.os.domain.validate.AgentJson
import com.polymath.os.domain.validate.SafetyFilter
import kotlinx.serialization.KSerializer

enum class AgentId(val code: String) {
    A1_ONBOARDING("A1"), A2_SKILL_ARCHITECT("A2"), A3_GOAL_MAPPER("A3"), A4_TASK_REWRITER("A4"),
    A5_COMMAND_PARSER("A5"), A6_CURIOSITY_NORMALIZER("A6"), A7_MEAL_PARSER("A7"),
}

data class ChatMessage(val role: String, val content: String)

/** The only thing an agent run needs from the network layer. Implemented by the Groq client. */
fun interface LlmCaller {
    suspend fun call(messages: List<ChatMessage>, maxTokens: Int): AiResult<String>
}

class AgentSpec<T>(
    val id: AgentId,
    val prompt: String,
    val serializer: KSerializer<T>,
    val maxTokens: Int,
    /** Cross-field rules. Schema and safety checks are applied by the runner. */
    val validate: (T) -> List<String>,
)

/** Per-event loop breaker (Section 4): max 4 LLM calls per event. */
class EventBudget(val maxCalls: Int = 4) {
    var calls: Int = 0
        private set

    fun tryConsume(): Boolean {
        if (calls >= maxCalls) return false
        calls++
        return true
    }
}

sealed interface AgentOutcome<out T> {
    data class Valid<T>(val value: T, val raw: String, val calls: Int) : AgentOutcome<T>
    /** Gate G5: repairs exhausted or event budget spent; show the manual form. */
    data class ManualForm(val reason: String, val errors: List<String>) : AgentOutcome<Nothing>
    data class Failed(val error: AiError) : AgentOutcome<Nothing>
}

/**
 * Section 8 pipeline: call -> parse -> validate -> safety filter -> repair (max 2) -> manual form.
 * Agents never call agents; the runner never writes to the database.
 */
class AgentRunner(private val llm: LlmCaller) {

    companion object {
        const val MAX_REPAIRS = 2
    }

    suspend fun <T> run(spec: AgentSpec<T>, vars: Map<String, String>, budget: EventBudget): AgentOutcome<T> {
        val system = Templates.fill(spec.prompt, vars)
        if (!budget.tryConsume()) return AgentOutcome.ManualForm("budget", emptyList())
        val first = llm.call(listOf(ChatMessage("system", system), ChatMessage("user", Prompts.KICKOFF)), spec.maxTokens)
        var raw = when (first) {
            is AiResult.Failure -> return AgentOutcome.Failed(first.reason)
            is AiResult.Success -> first.value
        }
        var attempt = 0
        while (true) {
            val errors = check(spec, raw)
            if (errors.first != null && errors.second.isEmpty()) return AgentOutcome.Valid(errors.first!!, raw, budget.calls)
            if (attempt >= MAX_REPAIRS) return AgentOutcome.ManualForm("repair_exhausted", errors.second)
            if (!budget.tryConsume()) return AgentOutcome.ManualForm("budget", errors.second)
            attempt++
            val repair = Templates.fill(Prompts.REPAIR, mapOf("error_list" to errors.second.joinToString("\n") { "- $it" }))
            val r = llm.call(
                listOf(ChatMessage("system", system), ChatMessage("assistant", raw), ChatMessage("user", repair)),
                spec.maxTokens,
            )
            raw = when (r) {
                is AiResult.Failure -> return AgentOutcome.Failed(r.reason)
                is AiResult.Success -> r.value
            }
        }
    }

    fun <T> check(spec: AgentSpec<T>, raw: String): Pair<T?, List<String>> {
        val d = AgentJson.decode(raw, spec.serializer)
        val errs = buildList {
            addAll(d.errors)
            d.value?.let { addAll(spec.validate(it)) }
            d.tree?.let { addAll(SafetyFilter.checkTree(it)) }
        }.distinct().take(25)
        return d.value to errs
    }
}
