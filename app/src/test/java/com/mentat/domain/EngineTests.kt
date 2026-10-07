package com.mentat.domain

import com.mentat.Fixtures
import com.mentat.Fixtures.TODAY
import com.mentat.domain.engine.ComboMatcher
import com.mentat.domain.engine.ComboSeed
import com.mentat.domain.engine.Consistency
import com.mentat.domain.engine.EnergyAnalyzer
import com.mentat.domain.engine.EnergyBucket
import com.mentat.domain.engine.IcsParser
import com.mentat.domain.engine.JournalInput
import com.mentat.domain.engine.JournalLogLine
import com.mentat.domain.engine.JournalWriter
import com.mentat.domain.engine.LevelCalculator
import com.mentat.domain.engine.LevelInput
import com.mentat.domain.engine.ProofTimeline
import com.mentat.domain.engine.RecallScheduler
import com.mentat.domain.engine.ShowAndTell
import com.mentat.domain.engine.WeaknessEngine
import com.mentat.domain.engine.WeaknessHistory
import com.mentat.domain.engine.WeaknessInput
import com.mentat.domain.engine.WeaknessSignal
import com.mentat.domain.model.EnergyLog
import com.mentat.domain.model.FrictionTag
import com.mentat.domain.model.QuestState
import com.mentat.domain.model.RecallMode
import com.mentat.domain.model.RecallResult
import com.mentat.domain.model.SkillStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId

class RecallSchedulerTest {
    private var n = 0
    private val s = RecallScheduler { "r${n++}" }

    @Test fun completionCreatesPlus7AndPlus30_alternatingModes() {
        val c = s.onMiniSkillCompleted("sk", "ms", TODAY, emptyList())
        assertEquals(listOf(TODAY.plusDays(7), TODAY.plusDays(30)), c.map { it.due })
        assertEquals(listOf(RecallMode.YES_SHAKY_NO, RecallMode.TEACH_BACK), c.map { it.mode })
        assertTrue(s.onMiniSkillCompleted("sk", "ms", TODAY, c).isEmpty())
    }

    @Test fun noAddsPlus3() {
        val c = s.onMiniSkillCompleted("sk", "ms", TODAY, emptyList())
        val answeredOn = TODAY.plusDays(7)
        val more = s.onAnswered(c[0], RecallResult.NO, answeredOn, TODAY, c)
        assertEquals(listOf(answeredOn.plusDays(3)), more.map { it.due })
    }

    @Test fun shakyAddsPlus3_yesDoesNot() {
        val c = s.onMiniSkillCompleted("sk", "ms", TODAY, emptyList())
        assertEquals(1, s.onAnswered(c[0], RecallResult.SHAKY, TODAY.plusDays(7), TODAY, c).size)
        assertTrue(s.onAnswered(c[0], RecallResult.YES, TODAY.plusDays(7), TODAY, c).isEmpty())
    }

    @Test fun plus30AnsweredSchedulesPlus90_thenPlus180() {
        val c = s.onMiniSkillCompleted("sk", "ms", TODAY, emptyList())
        val after30 = s.onAnswered(c[1], RecallResult.YES, TODAY.plusDays(30), TODAY, c)
        assertEquals(listOf(TODAY.plusDays(90)), after30.map { it.due })
        val after90 = s.onAnswered(after30[0], RecallResult.YES, TODAY.plusDays(90), TODAY, c + after30)
        assertEquals(listOf(TODAY.plusDays(180)), after90.map { it.due })
    }
}

class LevelCalculatorTest {
    @Test fun formula() {
        val r = LevelCalculator.compute(LevelInput(2, 2, 1, 1, 0))
        assertEquals(10 * 2 + 5 * 2 + 2 + 15, r.points)
        assertEquals(1 + 6, r.level) // sqrt(47) = 6.8
    }

    @Test fun sixtyIdleDaysDecays() {
        assertEquals(0, LevelCalculator.decay(30))
        assertEquals(0, LevelCalculator.decay(44))
        assertEquals(3, LevelCalculator.decay(45))
        assertEquals(6, LevelCalculator.decay(60))
        val r = LevelCalculator.compute(LevelInput(1, 0, 0, 0, 60))
        assertEquals(10, r.points)
        assertEquals(6, r.decay)
        assertEquals(1 + 2, r.level) // sqrt(4)
    }

    @Test fun zeroPointsIsLevelOne() = assertEquals(1, LevelCalculator.compute(LevelInput(0, 0, 0, 0, 400)).level)
}

class WeaknessEngineTest {
    private val skill = Fixtures.simpleSkill("a")

    private fun input(logs: List<com.mentat.domain.model.QuestLog>, history: List<WeaknessHistory> = emptyList(), today: java.time.LocalDate = TODAY) =
        WeaknessInput(today, listOf(skill), logs, emptyList(), emptyList(), history)

    private fun skips(n: Int) = (1..n).map { Fixtures.log("l$it", "a_a1", "a", TODAY.minusDays(it.toLong()), QuestState.SKIPPED) }

    @Test fun skipFiresExactlyAtThreshold() {
        assertNull(WeaknessEngine.evaluate(input(skips(2))))
        val q = WeaknessEngine.evaluate(input(skips(3)))
        assertEquals(WeaknessSignal.SKIPPED_REPEAT, q!!.signal)
        assertEquals("a_a1", q.activityId)
    }

    @Test fun neverTwiceADay() {
        val fired = listOf(WeaknessHistory(WeaknessSignal.UNTOUCHED, "other", TODAY, null))
        assertNull(WeaknessEngine.evaluate(input(skips(3), fired)))
    }

    @Test fun ignoreSnoozesSevenDays() {
        val ignored = listOf(WeaknessHistory(WeaknessSignal.SKIPPED_REPEAT, "a_a1", TODAY.minusDays(1), TODAY.plusDays(6)))
        val moreSkips = skips(3).map { it.copy(date = TODAY) }
        assertNull(WeaknessEngine.evaluate(input(moreSkips, ignored)))
        // After the snooze ends, new evidence (after the fire date) counts again.
        val later = TODAY.plusDays(7)
        val fresh = (1..3).map { Fixtures.log("n$it", "a_a1", "a", later.minusDays(it.toLong() - 1), QuestState.SKIPPED) }
        assertNotNull(WeaknessEngine.evaluate(input(fresh, ignored, later)))
    }

    @Test fun tooHardTwiceInARow() {
        val logs = listOf(
            Fixtures.log("1", "a_a1", "a", TODAY.minusDays(2), QuestState.PARTIAL, tag = FrictionTag.TOO_HARD),
            Fixtures.log("2", "a_a1", "a", TODAY.minusDays(1), QuestState.PARTIAL, tag = FrictionTag.TOO_HARD),
        )
        assertEquals(WeaknessSignal.TOO_HARD_STREAK, WeaknessEngine.evaluate(input(logs))!!.signal)
        assertNull(WeaknessEngine.evaluate(input(logs.take(1))))
    }

    @Test fun overrunThreeTasks() {
        val logs = (1..3).map { Fixtures.log("o$it", "a_a1", "a", TODAY.minusDays(it.toLong()), actual = 50, est = 30) }
        assertEquals(WeaknessSignal.OVERRUN, WeaknessEngine.evaluate(input(logs))!!.signal)
    }

    @Test fun untouchedFourteenDays() {
        val old = Fixtures.log("x", "a_a1", "a", TODAY.minusDays(14))
        val q = WeaknessEngine.evaluate(input(listOf(old)))
        assertEquals(WeaknessSignal.UNTOUCHED, q!!.signal)
        assertNull(WeaknessEngine.evaluate(input(listOf(old.copy(date = TODAY.minusDays(13))))))
    }

    @Test fun splitHalvesEstimate() {
        val a = Fixtures.activity("z", "m", est = 40, tiny = 8)
        val (p1, p2) = WeaknessEngine.split(a) { "z2" }!!
        assertEquals(20, p1.estMinutes)
        assertEquals(20, p2.estMinutes)
        assertTrue(p1.tinyMinutes < p1.estMinutes)
        assertNull(WeaknessEngine.split(Fixtures.activity("y", "m", est = 8, tiny = 3)) { "y2" })
    }
}

class EnergyAnalyzerTest {
    @Test fun fewerThan14Logs_noPeaks() {
        val logs = (1..13).map { EnergyLog(TODAY.atTime(9, 0).minusDays(it.toLong()), 4) }
        assertNull(EnergyAnalyzer.analyze(logs).peaks)
    }

    @Test fun peakIsTopBucket() {
        val logs = (1..10).map { EnergyLog(TODAY.atTime(19, 0).minusDays(it.toLong()), 5) } +
            (1..10).map { EnergyLog(TODAY.atTime(9, 0).minusDays(it.toLong()), 2) }
        assertEquals(setOf(EnergyBucket.EVENING), EnergyAnalyzer.analyze(logs).peaks)
    }
}

class InsightsTest {
    @Test fun showAndTellPicksLongestThenMostRecent() {
        val logs = listOf(
            Fixtures.log("1", "a", "s", TODAY.minusDays(1), actual = 40, proof = "p1"),
            Fixtures.log("2", "a", "s", TODAY, actual = 40, proof = "p2"),
            Fixtures.log("3", "a", "s", TODAY, actual = 90, proof = null),
            Fixtures.log("4", "a", "s", TODAY.minusDays(9), actual = 120, proof = "p4"),
        )
        assertEquals("2", ShowAndTell.pick(TODAY, logs)!!.id)
    }

    @Test fun proofTimelineGroupsByMonthDescending() {
        val logs = listOf(
            Fixtures.log("1", "a", "s", TODAY.minusMonths(1), proof = "p"),
            Fixtures.log("2", "a", "s", TODAY, proof = "p"),
            Fixtures.log("3", "a", "s", TODAY, proof = null),
        )
        val g = ProofTimeline.group(logs)
        assertEquals(2, g.size)
        assertTrue(g[0].first.isAfter(g[1].first))
    }

    @Test fun comboNeedsCompletedMiniSkillInEachRequiredSkill() {
        val done = Fixtures.skill("p", 3, SkillStatus.ACTIVE, TODAY, Fixtures.miniSkill("pm", 1, listOf(Fixtures.activity("pa", "pm", completed = true)))).copy(name = "Probability basics")
        val notDone = Fixtures.simpleSkill("q").copy(name = "Sketching basics")
        val combos = listOf(ComboSeed("c1", listOf("sketch", "probability"), "Poster", 3.0))
        assertNull(ComboMatcher.suggest(TODAY, combos, listOf(done, notDone), null, emptySet()))
        val sketchDone = Fixtures.skill("r", 3, SkillStatus.ACTIVE, TODAY, Fixtures.miniSkill("rm", 1, listOf(Fixtures.activity("ra", "rm", completed = true)))).copy(name = "Sketching basics")
        assertNotNull(ComboMatcher.suggest(TODAY, combos, listOf(done, sketchDone), null, emptySet()))
        assertNull("max one per week", ComboMatcher.suggest(TODAY, combos, listOf(done, sketchDone), TODAY.minusDays(3), emptySet()))
    }

    @Test fun weeklyConsistencyCountsTinyAndRecall() {
        val monday = TODAY.with(DayOfWeek.MONDAY)
        val logs = listOf(Fixtures.log("1", "a", "s", monday, tiny = true), Fixtures.log("2", "a", "s", monday.plusDays(1), QuestState.SKIPPED))
        val w = Consistency.week(TODAY, logs, setOf(monday.plusDays(2)), 1)
        assertEquals(2, w.loggedDays)
        assertEquals(6, w.target)
    }

    @Test fun journalIsFixedTemplate() {
        val text = JournalWriter.write(
            JournalInput(TODAY, listOf(JournalLogLine("Draw mug", "Sketching", QuestState.DONE, 20, false, "BORING")), listOf("Shapes (yes)"), listOf("Recall: Shapes"), listOf(3), "Good day"),
        )
        assertTrue(text.contains("- Draw mug [Sketching], 20 min"))
        assertTrue(text.contains("Minutes: 20"))
        assertTrue(text.contains("My note: Good day"))
        assertFalse(text.contains("Nothing logged"))
    }
}

class IcsParserTest {
    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:phys-1
        SUMMARY:Physics lecture
        DTSTART;TZID=Asia/Kolkata:20260105T100000
        DTEND;TZID=Asia/Kolkata:20260105T110000
        RRULE:FREQ=WEEKLY;BYDAY=MO,WE
        EXDATE;TZID=Asia/Kolkata:20261012T100000
        END:VEVENT
        BEGIN:VEVENT
        UID:holiday
        SUMMARY:Holiday
        DTSTART;VALUE=DATE:20261010
        END:VEVENT
        BEGIN:VEVENT
        UID:exam
        SUMMARY:Maths exam\, hall B
        DTSTART:20261020T043000Z
        DURATION:PT2H
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test fun expandsWeeklyFor90Days() {
        val r = IcsParser(ZoneId.of("Asia/Kolkata")).parse(ics, TODAY, 90)
        assertEquals(3, r.eventsRead)
        assertEquals(1, r.skipped) // all-day holiday
        val phys = r.occurrences.filter { it.uid == "phys-1" }
        assertTrue(phys.all { it.start.dayOfWeek == DayOfWeek.MONDAY || it.start.dayOfWeek == DayOfWeek.WEDNESDAY })
        assertTrue(phys.all { !it.start.toLocalDate().isBefore(TODAY) && it.start.toLocalDate().isBefore(TODAY.plusDays(90)) })
        assertTrue(phys.none { it.start.toLocalDate() == java.time.LocalDate.of(2026, 10, 12) })
        assertEquals(25, phys.size) // 26 Mon/Wed in the window minus one EXDATE
        val exam = r.occurrences.single { it.uid == "exam" }
        assertEquals("Maths exam, hall B", exam.title)
        assertEquals(10, exam.start.hour) // 04:30Z = 10:00 IST
        assertEquals(12, exam.end.hour)
    }
}
