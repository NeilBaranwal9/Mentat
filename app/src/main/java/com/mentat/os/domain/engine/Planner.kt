package com.mentat.os.domain.engine

import com.mentat.os.domain.model.Commitment
import com.mentat.os.domain.model.DateRange
import com.mentat.os.domain.model.Recurrence
import com.mentat.os.domain.model.SleepWindow
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Section 6.1 weights. Defaults are [Guessing]; tune after 2-3 weeks of real logs. */
data class PlannerWeights(
    val priority: Double = 3.0,
    val deadline: Double = 4.0,
    val recallOverdue: Double = 5.0,
    val unlocks: Double = 1.5,
    val goalCore: Double = 1.0,
    val recentRepetition: Double = 2.0,
    val energyFit: Double = 0.8,
)

data class PlannerConfig(
    val weights: PlannerWeights = PlannerWeights(),
    val bufferRatio: Double = 0.82,
    val minWindowMinutes: Int = 15,
    val gapMinutes: Int = 5,
    val calibrationMin: Double = 0.7,
    val calibrationMax: Double = 2.0,
)

@Serializable
enum class CandidateKind { ACTIVITY, RECALL, WILDCARD, CAPSTONE }

data class PlanCandidate(
    val id: String,
    val kind: CandidateKind,
    val skillId: String,
    val skillName: String,
    val title: String,
    val estMinutes: Int,
    val tinyTitle: String,
    val tinyMinutes: Int,
    val priority: Int,
    val deadlineUrgency: Double = 0.0,
    /** Days past due for RECALL candidates (0 = due today). Ignored for other kinds. */
    val recallOverdueDays: Int = 0,
    val unlocksNextStep: Boolean = false,
    val goalCore: Boolean = false,
    val goalTitle: String? = null,
    val recentRepetition: Boolean = false,
    /** The user chose "always tiny" for this activity (weakness answer no_time). */
    val forceTiny: Boolean = false,
    val activityId: String? = null,
    val recallCheckId: String? = null,
)

data class PlannerInput(
    val today: LocalDate,
    val commitments: List<Commitment>,
    val sleep: SleepWindow?,
    val examModes: List<DateRange>,
    val candidates: List<PlanCandidate>,
    /** skillId -> running mean of actual/estimated minutes. */
    val calibration: Map<String, Double> = emptyMap(),
    /** null = fewer than 14 energy logs, energy_fit is then 0.5 for everyone. */
    val peakBuckets: Set<EnergyBucket>? = null,
    /** When planning mid-day, nothing is placed before this time. */
    val notBefore: LocalTime? = null,
)

@Serializable
data class PlanBlock(
    val candidateId: String,
    val kind: CandidateKind,
    val skillId: String,
    val skillName: String,
    val title: String,
    val minutes: Int,
    val isTiny: Boolean,
    /** null = "anytime today" (no free window was left). */
    val startMinute: Int?,
    val score: Double,
    val reason: String,
    val minimumDay: Boolean = false,
    val activityId: String? = null,
    val recallCheckId: String? = null,
) {
    val endMinute: Int? get() = startMinute?.plus(minutes)
}

data class DayPlan(
    val date: LocalDate,
    val blocks: List<PlanBlock>,
    val freeWindows: List<IntRange>,
    val examMode: Boolean,
)

/** Pure, deterministic planner. Same input always yields the same output. */
class Planner(private val config: PlannerConfig = PlannerConfig()) {

    private data class Window(val start: Int, val end: Int, var cursor: Int, var usableLeft: Int)

    private data class Scored(val c: PlanCandidate, val isTiny: Boolean, val minutes: Int, val base: Double, val terms: List<Pair<Double, String>>)

    fun plan(input: PlannerInput): DayPlan {
        val examMode = input.examModes.any { input.today in it }
        val free = freeWindows(input)
        val windows = free.map { r ->
            val len = r.last - r.first
            Window(r.first, r.last, r.first, floor(len * config.bufferRatio).toInt())
        }
        val scored = input.candidates.map { score(it, input, examMode) }
        val peaks = input.peakBuckets

        fun energyFit(s: Scored): Double = when {
            peaks == null -> 0.5
            windows.any { it.usableLeft >= s.minutes && EnergyBucket.of(it.cursor) in peaks } -> 1.0
            else -> 0.0
        }

        val ranked = scored
            .map { it to (it.base + config.weights.energyFit * energyFit(it)) }
            .sortedWith(compareByDescending<Pair<Scored, Double>> { it.second }.thenBy { it.first.c.id })

        val blocks = mutableListOf<PlanBlock>()
        val placedIds = mutableSetOf<String>()
        for ((s, total) in ranked) {
            val fits = windows.filter { it.usableLeft >= s.minutes && it.cursor + s.minutes <= it.end }
            if (fits.isEmpty()) continue
            val inPeak = peaks != null && !s.isTiny
            val w = (if (inPeak) fits.firstOrNull { EnergyBucket.of(it.cursor) in peaks!! } else null) ?: fits.first()
            val energyHit = peaks != null && EnergyBucket.of(w.cursor) in peaks
            blocks += block(s, total, w.cursor, energyHit)
            placedIds += s.c.id
            w.usableLeft -= s.minutes
            w.cursor = min(w.end, w.cursor + s.minutes + config.gapMinutes)
        }

        // Step 9: at least one tiny-version item, even on a full day.
        if (ranked.isNotEmpty() && blocks.none { it.isTiny }) {
            val pick = ranked.firstOrNull { it.first.c.id !in placedIds } ?: ranked.first()
            val c = pick.first.c
            val tinyMin = c.tinyMinutes
            val w = windows.firstOrNull { it.cursor + tinyMin <= it.end }
            blocks += PlanBlock(
                candidateId = c.id, kind = c.kind, skillId = c.skillId, skillName = c.skillName,
                title = c.tinyTitle, minutes = tinyMin, isTiny = true, startMinute = w?.cursor,
                score = pick.second, minimumDay = true, activityId = c.activityId, recallCheckId = c.recallCheckId,
                reason = "Minimum day: if the rest slips, this $tinyMin-minute version still counts.",
            )
            if (w != null) w.cursor = min(w.end, w.cursor + tinyMin + config.gapMinutes)
        }

        val ordered = blocks.sortedWith(compareBy<PlanBlock> { it.startMinute ?: Int.MAX_VALUE }.thenBy { it.candidateId })
        return DayPlan(input.today, ordered, free, examMode)
    }

    /** "What should I do?" mode: exactly one task that fits [availableMinutes], else the tiny version of the top candidate. */
    fun suggestOne(input: PlannerInput, availableMinutes: Int, now: LocalTime): PlanBlock? {
        val examMode = input.examModes.any { input.today in it }
        val peaks = input.peakBuckets
        val nowFit = when {
            peaks == null -> 0.5
            EnergyBucket.of(now.hour * 60 + now.minute) in peaks -> 1.0
            else -> 0.0
        }
        val ranked = input.candidates.map { score(it, input, examMode) }
            .map { it to it.base + config.weights.energyFit * nowFit }
            .sortedWith(compareByDescending<Pair<Scored, Double>> { it.second }.thenBy { it.first.c.id })
        if (ranked.isEmpty()) return null
        val fit = ranked.firstOrNull { it.first.minutes <= availableMinutes }
        if (fit != null) return block(fit.first, fit.second, null, nowFit == 1.0)
        val top = ranked.first()
        val c = top.first.c
        return PlanBlock(
            candidateId = c.id, kind = c.kind, skillId = c.skillId, skillName = c.skillName,
            title = c.tinyTitle, minutes = c.tinyMinutes, isTiny = true, startMinute = null, score = top.second,
            activityId = c.activityId, recallCheckId = c.recallCheckId,
            reason = "Nothing full-size fits in $availableMinutes minutes, so here is the ${c.tinyMinutes}-minute version of your top task.",
        )
    }

    private fun score(c: PlanCandidate, input: PlannerInput, examMode: Boolean): Scored {
        val w = config.weights
        val examTiny = examMode && c.kind != CandidateKind.RECALL && c.priority < 5
        val isTiny = c.forceTiny || examTiny
        val minutes = when {
            isTiny -> c.tinyMinutes
            c.kind == CandidateKind.RECALL -> c.estMinutes
            else -> {
                val ratio = (input.calibration[c.skillId] ?: 1.0).coerceIn(config.calibrationMin, config.calibrationMax)
                (c.estMinutes * ratio).roundToInt().coerceAtLeast(1)
            }
        }
        val priorityNorm = c.priority.coerceIn(1, 5) / 5.0
        val recallTerm = if (c.kind == CandidateKind.RECALL) min(1.0, (c.recallOverdueDays.coerceAtLeast(0) + 1) / 7.0) else 0.0
        val terms = buildList {
            add(w.priority * priorityNorm to "it's a priority-${c.priority} skill")
            if (c.deadlineUrgency > 0) add(w.deadline * c.deadlineUrgency to "${c.goalTitle?.let { "'$it'" } ?: "a goal"} has a deadline coming up")
            if (c.kind == CandidateKind.RECALL) {
                val phrase = if (c.recallOverdueDays > 0) "the recall check is ${c.recallOverdueDays} day${if (c.recallOverdueDays == 1) "" else "s"} overdue" else "a recall check is due today"
                add(w.recallOverdue * recallTerm to phrase)
            }
            if (c.unlocksNextStep) add(w.unlocks to "finishing it completes this mini-skill")
            if (c.goalCore) add(w.goalCore to "it's core to ${c.goalTitle?.let { "your goal '$it'" } ?: "a goal"}")
            if (c.recentRepetition) add(-w.recentRepetition to "you practised this skill in the last day")
        }
        val base = terms.sumOf { it.first }
        val extra = buildList {
            if (c.kind == CandidateKind.WILDCARD) add("it's this week's wildcard sampler")
            if (c.kind == CandidateKind.CAPSTONE) add("it's the capstone for this path")
            if (examTiny) add("exam mode is on, so it's the tiny version")
            else if (c.forceTiny) add("you chose the tiny version for this task")
        }
        val positive = terms.filter { it.first > 0 }.sortedByDescending { it.first }.map { it.second }
        return Scored(c, isTiny, minutes, base, (extra + positive).map { 0.0 to it })
    }

    private fun block(s: Scored, total: Double, start: Int?, energyHit: Boolean): PlanBlock {
        val phrases = s.terms.map { it.second }.toMutableList()
        if (energyHit) phrases.add(minOf(1, phrases.size), "it lands in your high-energy time")
        val reason = if (phrases.isEmpty()) "Picked as your next step." else "Picked because " + phrases.take(2).joinToString(" and ") + "."
        val c = s.c
        return PlanBlock(
            candidateId = c.id, kind = c.kind, skillId = c.skillId, skillName = c.skillName,
            title = if (s.isTiny) c.tinyTitle else c.title, minutes = s.minutes, isTiny = s.isTiny,
            startMinute = start, score = total, reason = reason,
            activityId = c.activityId, recallCheckId = c.recallCheckId,
        )
    }

    /** Free windows (minute-of-day ranges, end exclusive in [IntRange.last]) after removing all blocks. */
    fun freeWindows(input: PlannerInput): List<IntRange> {
        val busy = busyIntervals(input).sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        var cursor = 0
        for (b in busy) {
            if (b.first > cursor) out += cursor..b.first
            cursor = maxOf(cursor, b.last)
        }
        if (cursor < DAY) out += cursor..DAY
        return out.filter { it.last - it.first >= config.minWindowMinutes }
    }

    fun busyIntervals(input: PlannerInput): List<IntRange> {
        val day = input.today
        val out = mutableListOf<IntRange>()
        input.sleep?.let { s ->
            val a = s.start.toMinuteOfDay()
            val b = s.end.toMinuteOfDay()
            when {
                a > b -> { out += 0..b; out += a..DAY }
                a < b -> out += a..b
            }
        }
        input.notBefore?.let { out += 0..it.toMinuteOfDay() }
        val dayStart = day.atStartOfDay()
        val dayEnd = day.plusDays(1).atStartOfDay()
        for (c in input.commitments) {
            val dur = Duration.between(c.start, c.end)
            if (dur.isNegative || dur.isZero) continue
            val starts = if (c.recurrence == Recurrence.NONE) listOf(c.start)
            else listOf(day.minusDays(1), day).filter { occursOn(c, it) }.map { it.atTime(c.start.toLocalTime()) }
            for (occStart in starts) {
                val occEnd = occStart.plus(dur)
                val s = maxOf(occStart, dayStart)
                val e = minOf(occEnd, dayEnd)
                if (e.isAfter(s)) out += minuteOf(s, day)..minuteOf(e, day)
            }
        }
        return out
    }

    private fun occursOn(c: Commitment, d: LocalDate): Boolean {
        val first = c.start.toLocalDate()
        return when (c.recurrence) {
            Recurrence.NONE -> d == first
            Recurrence.DAILY -> !d.isBefore(first)
            Recurrence.WEEKLY -> !d.isBefore(first) && d.dayOfWeek == first.dayOfWeek
        }
    }

    private fun minuteOf(t: LocalDateTime, day: LocalDate): Int =
        if (t.toLocalDate().isAfter(day)) DAY else t.hour * 60 + t.minute

    companion object {
        const val DAY = 24 * 60
    }
}

fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute
fun minuteToLabel(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)
