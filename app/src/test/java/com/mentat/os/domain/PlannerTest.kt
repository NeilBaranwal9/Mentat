package com.mentat.os.domain

import com.mentat.os.Fixtures
import com.mentat.os.Fixtures.TODAY
import com.mentat.os.domain.engine.CandidateBuilder
import com.mentat.os.domain.engine.CandidateKind
import com.mentat.os.domain.engine.EnergyBucket
import com.mentat.os.domain.engine.PlanBlock
import com.mentat.os.domain.engine.Planner
import com.mentat.os.domain.engine.PlannerInput
import com.mentat.os.domain.model.CommitKind
import com.mentat.os.domain.model.Commitment
import com.mentat.os.domain.model.DateRange
import com.mentat.os.domain.model.RecallCheck
import com.mentat.os.domain.model.RecallMode
import com.mentat.os.domain.model.RecallResult
import com.mentat.os.domain.model.Recurrence
import com.mentat.os.domain.model.SkillStatus
import com.mentat.os.domain.model.SleepWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PlannerTest {
    private val planner = Planner()
    private val sleep = SleepWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))
    private val skills = listOf(Fixtures.simpleSkill("a", 5), Fixtures.simpleSkill("b", 3), Fixtures.simpleSkill("c", 2))

    private fun input(
        commitments: List<Commitment> = emptyList(),
        exam: List<DateRange> = emptyList(),
        recalls: List<RecallCheck> = emptyList(),
        peaks: Set<EnergyBucket>? = null,
    ) = PlannerInput(
        today = TODAY, commitments = commitments, sleep = sleep, examModes = exam,
        candidates = CandidateBuilder.build(TODAY, skills, recalls, emptyList()), peakBuckets = peaks,
    )

    private fun assertNoOverlap(blocks: List<PlanBlock>) {
        val timed = blocks.filter { it.startMinute != null }.sortedBy { it.startMinute }
        timed.zipWithNext().forEach { (x, y) -> assertTrue("overlap $x / $y", x.endMinute!! <= y.startMinute!!) }
    }

    @Test fun emptyDay_placesEverySkill_withReasons_noOverlap() {
        val plan = planner.plan(input())
        assertNoOverlap(plan.blocks)
        assertTrue(plan.blocks.all { it.reason.isNotBlank() })
        assertTrue(plan.blocks.map { it.skillId }.containsAll(listOf("a", "b", "c")))
        // nothing during sleep
        assertTrue(plan.blocks.filter { it.startMinute != null }.all { it.startMinute!! >= 7 * 60 && it.endMinute!! <= 23 * 60 })
        assertTrue("guarantee tiny", plan.blocks.any { it.isTiny })
    }

    @Test fun fullClassDay_stillPlacesOneTinyItem() {
        val classes = listOf(Commitment("x", "Classes", CommitKind.CLASS, TODAY.atTime(7, 0), TODAY.atTime(23, 0), Recurrence.NONE))
        val plan = planner.plan(input(classes))
        assertNoOverlap(plan.blocks)
        assertTrue(plan.blocks.isNotEmpty())
        assertTrue(plan.blocks.any { it.isTiny && it.minimumDay })
    }

    @Test fun weeklyCommitment_blocksItsSlot() {
        val weekly = Commitment("w", "Physics", CommitKind.CLASS, TODAY.minusWeeks(2).atTime(9, 0), TODAY.minusWeeks(2).atTime(17, 0), Recurrence.WEEKLY)
        val plan = planner.plan(input(listOf(weekly)))
        assertTrue(plan.blocks.filter { it.startMinute != null }.none { it.startMinute!! < 17 * 60 && it.endMinute!! > 9 * 60 })
    }

    @Test fun examMode_onlyPriority5RunsFull() {
        val plan = planner.plan(input(exam = listOf(DateRange(TODAY, TODAY.plusDays(3)))))
        assertTrue(plan.examMode)
        plan.blocks.filter { !it.minimumDay && it.kind == CandidateKind.ACTIVITY }.forEach { b ->
            if (b.skillId == "a") assertTrue(!b.isTiny) else assertTrue(b.isTiny)
        }
    }

    @Test fun dueRecall_isPlannedAndScoredHigh() {
        val recall = RecallCheck("r1", "b_m1", "b", TODAY.minusDays(2), RecallMode.YES_SHAKY_NO, RecallResult.PENDING, null)
        val plan = planner.plan(input(recalls = listOf(recall)))
        val r = plan.blocks.firstOrNull { it.kind == CandidateKind.RECALL }
        assertNotNull(r)
        assertEquals(5, r!!.minutes)
        assertTrue(r.reason.contains("overdue"))
    }

    @Test fun whatShouldIDo_respectsAvailableMinutes() {
        val one = planner.suggestOne(input(), 25, LocalTime.of(10, 0))
        assertNotNull(one)
        assertTrue(one!!.minutes <= 25 || one.isTiny)
        val tinyOnly = planner.suggestOne(input(), 4, LocalTime.of(10, 0))
        assertTrue(tinyOnly!!.isTiny)
    }

    @Test fun deterministic_sameInputTenTimes() {
        val i = input(peaks = setOf(EnergyBucket.EVENING))
        val first = planner.plan(i)
        repeat(10) { assertEquals(first, planner.plan(i)) }
    }

    @Test fun activeSkillCap_isThree() {
        val many = (1..5).map { Fixtures.simpleSkill("s$it", it) }
        assertEquals(3, CandidateBuilder.activeSkills(many).size)
        assertEquals(listOf("s5", "s4", "s3"), CandidateBuilder.activeSkills(many).map { it.id })
        val c = CandidateBuilder.build(TODAY, many, emptyList(), emptyList())
        assertEquals(3, c.count { it.kind == CandidateKind.ACTIVITY })
    }

    @Test fun prerequisitesGateTheNextActivity() {
        val c = CandidateBuilder.build(TODAY, listOf(Fixtures.simpleSkill("a")), emptyList(), emptyList())
        assertEquals("a_a1", c.single().activityId)
    }

    @Test fun samplingSkill_becomesWeeklyWildcard() {
        val sampling = Fixtures.skill(
            "w", 3, SkillStatus.SAMPLING, TODAY.minusDays(3),
            Fixtures.miniSkill("w_s", 0, listOf(Fixtures.activity("w_sa", "w_s", est = 20, tiny = 5)), sampler = true),
        )
        val c = CandidateBuilder.build(TODAY, listOf(sampling), emptyList(), emptyList())
        assertEquals(CandidateKind.WILDCARD, c.single().kind)
        val used = CandidateBuilder.build(TODAY, listOf(sampling), emptyList(), listOf(Fixtures.log("l", "w_sa", "w", TODAY.minusDays(1))))
        assertTrue(used.none { it.kind == CandidateKind.WILDCARD })
    }

    @Test fun minimumDay_everythingSkipped_stillOffersTiny() {
        val plan = planner.plan(input(listOf(Commitment("x", "All day", CommitKind.OTHER, TODAY.atStartOfDay(), TODAY.plusDays(1).atStartOfDay(), Recurrence.NONE))))
        val tiny = plan.blocks.single()
        assertTrue(tiny.isTiny && tiny.minimumDay && tiny.startMinute == null)
    }
}
