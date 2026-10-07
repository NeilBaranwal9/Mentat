package com.mentat.domain.engine

import com.mentat.domain.model.RecallCheck
import com.mentat.domain.model.RecallMode
import com.mentat.domain.model.RecallResult
import java.time.LocalDate

/**
 * Section 6.4. On mini-skill completion: +7 and +30 days. Answering the +30 check schedules +90,
 * answering +90 schedules +180 (offsets measured from the completion date). A NO or SHAKY adds a
 * +3 day check. Mode alternates YES_SHAKY_NO / TEACH_BACK by sequence number.
 */
class RecallScheduler(private val idFactory: () -> String) {

    companion object {
        val LADDER = listOf(7L, 30L, 90L, 180L)
        const val RETRY_DAYS = 3L
        fun modeFor(sequence: Int) = if (sequence % 2 == 0) RecallMode.YES_SHAKY_NO else RecallMode.TEACH_BACK
    }

    fun onMiniSkillCompleted(skillId: String, miniSkillId: String, completedOn: LocalDate, existing: List<RecallCheck>): List<RecallCheck> {
        if (existing.any { it.miniSkillId == miniSkillId }) return emptyList()
        return listOf(0, 1).map { step ->
            RecallCheck(
                id = idFactory(), miniSkillId = miniSkillId, skillId = skillId,
                due = completedOn.plusDays(LADDER[step]), mode = modeFor(step), result = RecallResult.PENDING,
                teachBackText = null, sequence = step,
            )
        }
    }

    /**
     * Returns new checks to create after [answered] got [result].
     * [completedOn] is the mini-skill completion date (anchor for the ladder).
     */
    fun onAnswered(answered: RecallCheck, result: RecallResult, answeredOn: LocalDate, completedOn: LocalDate, existing: List<RecallCheck>): List<RecallCheck> {
        val out = mutableListOf<RecallCheck>()
        val nextSeq = (existing.filter { it.miniSkillId == answered.miniSkillId }.maxOfOrNull { it.sequence } ?: answered.sequence) + 1
        var seq = nextSeq
        if (result == RecallResult.NO || result == RecallResult.SHAKY) {
            out += RecallCheck(idFactory(), answered.miniSkillId, answered.skillId, answeredOn.plusDays(RETRY_DAYS), modeFor(seq), RecallResult.PENDING, null, seq)
            seq++
        }
        val ladderDue = existing.filter { it.miniSkillId == answered.miniSkillId }.map { it.due }.toSet() + out.map { it.due }
        val stepIndex = LADDER.indexOfFirst { completedOn.plusDays(it) == answered.due }
        if (stepIndex in 1 until LADDER.lastIndex) {
            val nextDue = completedOn.plusDays(LADDER[stepIndex + 1])
            if (nextDue !in ladderDue) {
                out += RecallCheck(idFactory(), answered.miniSkillId, answered.skillId, nextDue, modeFor(seq), RecallResult.PENDING, null, seq)
            }
        }
        return out
    }
}
