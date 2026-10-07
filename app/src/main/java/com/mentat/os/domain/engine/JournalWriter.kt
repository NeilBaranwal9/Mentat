package com.mentat.os.domain.engine

import com.mentat.os.domain.model.QuestState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class JournalLogLine(val title: String, val skillName: String, val state: QuestState, val minutes: Int, val tiny: Boolean, val tag: String?)

data class JournalInput(
    val date: LocalDate,
    val logs: List<JournalLogLine>,
    val recallsAnswered: List<String>,
    val tomorrowDue: List<String>,
    val energyLevels: List<Int>,
    val userLine: String?,
)

/** Section 6.5: fixed template, no generated prose. */
object JournalWriter {
    private val fmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

    fun write(i: JournalInput): String = buildString {
        appendLine(i.date.format(fmt))
        appendLine()
        val done = i.logs.filter { it.state != QuestState.SKIPPED }
        if (done.isEmpty() && i.recallsAnswered.isEmpty()) {
            appendLine("Nothing logged.")
        } else {
            appendLine("Done:")
            done.forEach { l ->
                val kind = when {
                    l.tiny -> " (tiny version)"
                    l.state == QuestState.PARTIAL -> " (partial)"
                    else -> ""
                }
                appendLine("- ${l.title} [${l.skillName}], ${l.minutes} min$kind")
            }
            i.recallsAnswered.forEach { appendLine("- Recall: $it") }
        }
        val skipped = i.logs.filter { it.state == QuestState.SKIPPED }
        if (skipped.isNotEmpty()) {
            appendLine()
            appendLine("Skipped:")
            skipped.forEach { appendLine("- ${it.title}") }
        }
        appendLine()
        appendLine("Minutes: ${done.sumOf { it.minutes }}")
        val tags = i.logs.mapNotNull { it.tag }.groupingBy { it }.eachCount()
        if (tags.isNotEmpty()) appendLine("Tags: " + tags.entries.sortedBy { it.key }.joinToString { "${it.key.lowercase().replace('_', ' ')} x${it.value}" })
        if (i.energyLevels.isNotEmpty()) appendLine("Energy: " + i.energyLevels.joinToString(", "))
        appendLine()
        if (i.tomorrowDue.isEmpty()) appendLine("Due tomorrow: nothing scheduled.")
        else {
            appendLine("Due tomorrow:")
            i.tomorrowDue.forEach { appendLine("- $it") }
        }
        if (!i.userLine.isNullOrBlank()) {
            appendLine()
            appendLine("My note: ${i.userLine.trim()}")
        }
    }.trimEnd()
}
