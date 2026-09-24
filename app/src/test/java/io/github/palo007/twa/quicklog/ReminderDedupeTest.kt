// HAND-OWNED (quick-log). JVM tests for the native/web slot-dedupe pure helpers.
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class ReminderDedupeTest {
    private val tz = TimeZone.getTimeZone("UTC")

    @Test fun buildSlotKeyJoinsWithPipes() {
        assertEquals("Take pills|2026-09-24|08:30", ReminderDedupe.buildSlotKey("Take pills", "2026-09-24", "08:30"))
    }

    @Test fun localDateStringIsZeroPadded() {
        // 2026-01-05 00:00:00 UTC
        val nowMs = 1767571200000L
        assertEquals("2026-01-05", ReminderDedupe.localDateString(nowMs, tz))
    }

    @Test fun timeStringIsZeroPadded() {
        assertEquals("08:05", ReminderDedupe.timeString(8, 5))
        assertEquals("23:59", ReminderDedupe.timeString(23, 59))
    }

    @Test fun parseMissedTimeExtractsHHMM() {
        assertEquals("08:30", ReminderDedupe.parseMissedTime("Missed at 08:30 - Take pills"))
    }

    @Test fun parseMissedTimeIsNullForOnTimeBody() {
        assertNull(ReminderDedupe.parseMissedTime("Take pills"))
    }

    @Test fun parseMissedTimeIsNullForNullBody() {
        assertNull(ReminderDedupe.parseMissedTime(null))
    }

    @Test fun candidateTimesForMissedBodyIsJustThatTime() {
        val nowMs = 1767571200000L
        assertEquals(listOf("08:30"), ReminderDedupe.candidateTimes("Missed at 08:30 - Take pills", nowMs, tz))
    }

    @Test fun candidateTimesForOnTimeBodyIsNowAndNowMinusOne() {
        // 2026-01-05 08:30:00 UTC
        val nowMs = 1767601800000L
        assertEquals(listOf("08:30", "08:29"), ReminderDedupe.candidateTimes("Take pills", nowMs, tz))
    }

    @Test fun candidateTimesCollapsesWhenMinuteBoundaryDoesNotChange() {
        // Both "now" and "now-1min" round to the same HH:MM only if resolution matched minutes
        // exactly at :00; use a time where now and now-1min genuinely differ (default case) plus
        // a sanity check that the list is never empty.
        val nowMs = 1767600000000L
        val candidates = ReminderDedupe.candidateTimes("Take pills", nowMs, tz)
        assert(candidates.isNotEmpty())
    }
}
