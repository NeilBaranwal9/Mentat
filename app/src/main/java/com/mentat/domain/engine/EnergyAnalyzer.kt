package com.mentat.domain.engine

import com.mentat.domain.model.EnergyLog
import kotlin.math.roundToInt

enum class EnergyBucket(val label: String, val fromMinute: Int, val toMinute: Int) {
    MORNING("morning", 5 * 60, 12 * 60),
    AFTERNOON("afternoon", 12 * 60, 17 * 60),
    EVENING("evening", 17 * 60, 21 * 60),
    NIGHT("night", 21 * 60, 5 * 60);

    companion object {
        fun of(minuteOfDay: Int): EnergyBucket {
            val m = ((minuteOfDay % 1440) + 1440) % 1440
            return when {
                m >= MORNING.fromMinute && m < MORNING.toMinute -> MORNING
                m >= AFTERNOON.fromMinute && m < AFTERNOON.toMinute -> AFTERNOON
                m >= EVENING.fromMinute && m < EVENING.toMinute -> EVENING
                else -> NIGHT
            }
        }
    }
}

data class EnergyProfile(
    val logCount: Int,
    val means: Map<EnergyBucket, Double>,
    /** null when fewer than [EnergyAnalyzer.MIN_LOGS] logs exist. */
    val peaks: Set<EnergyBucket>?,
)

/** Section 6.5: after 14+ logs, mean per bucket; the top tercile of buckets is the peak. */
object EnergyAnalyzer {
    const val MIN_LOGS = 14

    fun analyze(logs: List<EnergyLog>): EnergyProfile {
        val means = logs.groupBy { EnergyBucket.of(it.at.hour * 60 + it.at.minute) }
            .mapValues { (_, l) -> l.map { it.level }.average() }
        if (logs.size < MIN_LOGS || means.isEmpty()) return EnergyProfile(logs.size, means, null)
        val ranked = means.entries.sortedWith(compareByDescending<Map.Entry<EnergyBucket, Double>> { it.value }.thenBy { it.key.ordinal })
        val take = (ranked.size / 3.0).roundToInt().coerceAtLeast(1)
        val cutoff = ranked[take - 1].value
        return EnergyProfile(logs.size, means, ranked.filter { it.value >= cutoff }.map { it.key }.toSet())
    }
}
