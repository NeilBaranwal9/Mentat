package com.mentat.os.domain.engine

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class IcsOccurrence(val uid: String, val title: String, val start: LocalDateTime, val end: LocalDateTime)

data class IcsResult(val occurrences: List<IcsOccurrence>, val eventsRead: Int, val skipped: Int)

/**
 * Small hand-written iCalendar parser (Section 6.5): VEVENT with DTSTART/DTEND/DURATION, SUMMARY,
 * RRULE FREQ=DAILY|WEEKLY (INTERVAL, COUNT, UNTIL, BYDAY) and EXDATE. Recurrences are expanded for
 * [horizonDays] from [today]. All-day and cancelled events are skipped. Pure; run it on Dispatchers.IO.
 */
class IcsParser(private val zone: ZoneId) {

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    private val dateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateOnly = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun parse(text: String, today: LocalDate, horizonDays: Long = 90): IcsResult {
        val lines = unfold(text)
        val horizonEnd = today.plusDays(horizonDays)
        val out = mutableListOf<IcsOccurrence>()
        var events = 0
        var skipped = 0
        var current: MutableList<Prop>? = null
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> current = mutableListOf()
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    val props = current ?: continue
                    current = null
                    events++
                    val occ = runCatching { expand(props, today, horizonEnd) }.getOrNull()
                    if (occ == null) skipped++ else out += occ
                }
                current != null -> parseProp(line)?.let { current.add(it) }
            }
        }
        val sorted = out.distinctBy { Triple(it.uid, it.start, it.title) }.sortedWith(compareBy<IcsOccurrence> { it.start }.thenBy { it.uid })
        return IcsResult(sorted, events, skipped)
    }

    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        for (l in raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + l.substring(1)
            else if (l.isNotBlank()) out += l
        }
        return out
    }

    private fun parseProp(line: String): Prop? {
        val colon = indexOfUnquotedColon(line)
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = head.split(';')
        val params = parts.drop(1).mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(parts[0].uppercase(), params, value)
    }

    private fun indexOfUnquotedColon(s: String): Int {
        var quoted = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> quoted = !quoted
                ':' -> if (!quoted) return i
            }
        }
        return -1
    }

    /** Returns null for events that must be skipped (all-day, cancelled, unreadable). */
    private fun expand(props: List<Prop>, today: LocalDate, horizonEnd: LocalDate): List<IcsOccurrence>? {
        fun get(name: String) = props.firstOrNull { it.name == name }
        if (get("STATUS")?.value.equals("CANCELLED", ignoreCase = true)) return null
        val dtStart = get("DTSTART") ?: return null
        if (dtStart.params["VALUE"].equals("DATE", true) || dtStart.value.length == 8) return null
        val start = toLocal(dtStart) ?: return null
        val end = get("DTEND")?.let { toLocal(it) }
            ?: get("DURATION")?.let { start.plus(Duration.parse(it.value)) }
            ?: start.plusHours(1)
        val duration = Duration.between(start, end).takeIf { !it.isNegative && !it.isZero } ?: return null
        val title = unescape(get("SUMMARY")?.value ?: "Calendar event").ifBlank { "Calendar event" }
        val uid = get("UID")?.value ?: "${title}_$start"
        val exdates = props.filter { it.name == "EXDATE" }
            .flatMap { p -> p.value.split(',').mapNotNull { v -> toLocal(Prop("EXDATE", p.params, v))?.toLocalDate() } }
            .toSet()

        val rrule = get("RRULE")?.value
        val starts: List<LocalDateTime> = if (rrule == null) listOf(start) else expandRule(rrule, start, horizonEnd)
        return starts
            .filter { it.toLocalDate() !in exdates }
            .filter { !it.plus(duration).toLocalDate().isBefore(today) && it.toLocalDate().isBefore(horizonEnd) }
            .map { IcsOccurrence(uid, title, it, it.plus(duration)) }
    }

    private fun expandRule(rule: String, start: LocalDateTime, horizonEnd: LocalDate): List<LocalDateTime> {
        val parts = rule.split(';').mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1)
        }.toMap()
        val freq = parts["FREQ"]?.uppercase()
        val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val count = parts["COUNT"]?.toIntOrNull()
        val until = parts["UNTIL"]?.let { u -> toLocal(Prop("UNTIL", emptyMap(), u)) ?: runCatching { LocalDate.parse(u.take(8), dateOnly).atTime(23, 59) }.getOrNull() }
        val limitDate = listOfNotNull(horizonEnd, until?.toLocalDate()).minOrNull()!!
        val out = mutableListOf<LocalDateTime>()
        var produced = 0
        fun accept(dt: LocalDateTime): Boolean {
            if (dt.isBefore(start)) return true
            if (until != null && dt.isAfter(until)) return false
            if (dt.toLocalDate().isAfter(limitDate)) return false
            if (count != null && produced >= count) return false
            produced++
            out += dt
            return true
        }
        when (freq) {
            "DAILY" -> {
                var d = start
                while (accept(d)) d = d.plusDays(interval.toLong())
            }
            "WEEKLY" -> {
                val days = parts["BYDAY"]?.split(',')?.mapNotNull { dayOf(it.takeLast(2)) }?.sorted()
                    ?.ifEmpty { null } ?: listOf(start.dayOfWeek)
                var weekStart = start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                outer@ while (true) {
                    for (dow in days) {
                        val dt = weekStart.plusDays((dow.value - 1).toLong()).atTime(start.toLocalTime())
                        if (!accept(dt)) break@outer
                    }
                    weekStart = weekStart.plusWeeks(interval.toLong())
                    if (weekStart.isAfter(limitDate)) break
                }
            }
            else -> accept(start) // MONTHLY/YEARLY: only the first occurrence is imported.
        }
        return out
    }

    private fun dayOf(code: String): DayOfWeek? = when (code.uppercase()) {
        "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
        "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY
        "SU" -> DayOfWeek.SUNDAY; else -> null
    }

    private fun toLocal(p: Prop): LocalDateTime? {
        val v = p.value.trim()
        return runCatching {
            when {
                v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), dateTime).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime()
                v.length == 8 -> LocalDate.parse(v, dateOnly).atStartOfDay()
                p.params["TZID"] != null -> {
                    val src = runCatching { ZoneId.of(p.params["TZID"]) }.getOrDefault(zone)
                    LocalDateTime.parse(v, dateTime).atZone(src).withZoneSameInstant(zone).toLocalDateTime()
                }
                else -> LocalDateTime.parse(v, dateTime)
            }
        }.getOrNull()
    }

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim()
}
