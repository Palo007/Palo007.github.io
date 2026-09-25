// HAND-OWNED (quick-log). JVM tests for the receiver slot date (TWA-A4).
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ReminderSlotTest {
    private val tz = TimeZone.getTimeZone("Europe/Bratislava")

    private fun ms(s: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fmt.timeZone = tz
        return fmt.parse(s)!!.time
    }

    @Test fun lateFireAfterMidnightKeepsIntendedDate() {
        val intended = ms("2026-10-01 23:59")
        val now = ms("2026-10-02 00:01")
        assertEquals("2026-10-01", ReminderDedupe.localDateString(slotBaseMs(intended, now), tz))
    }

    @Test fun missingExtraFallsBackToNow() {
        val now = ms("2026-10-02 00:01")
        assertEquals(now, slotBaseMs(0L, now))
    }
}
