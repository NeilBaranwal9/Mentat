package com.mentat.os.domain.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

data class LevelInput(
    val completedMiniSkills: Int,
    val recallYes: Int,
    val recallShaky: Int,
    val capstonesDone: Int,
    val idleDays: Int,
)

data class LevelResult(val level: Int, val points: Int, val decay: Int, val pointsToNext: Int)

/**
 * Section 6.3. Claimed starting levels from onboarding are never an input here,
 * so they cannot feed points ("unverified claim").
 */
object LevelCalculator {
    fun points(i: LevelInput): Int = 10 * i.completedMiniSkills + 5 * i.recallYes + 2 * i.recallShaky + 15 * i.capstonesDone

    fun decay(idleDays: Int): Int = max(0, floor((idleDays - 30) / 15.0).toInt()) * 3

    fun compute(i: LevelInput): LevelResult {
        val p = points(i)
        val d = decay(i.idleDays)
        val effective = max(0, p - d)
        val level = 1 + floor(sqrt(effective.toDouble())).toInt()
        val nextThreshold = level * level
        return LevelResult(level, p, d, max(0, nextThreshold - effective))
    }
}
