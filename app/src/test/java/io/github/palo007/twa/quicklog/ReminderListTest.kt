// HAND-OWNED (quick-log). JVM tests for /inbox-meta/reminders.json handling.
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ReminderListTest {
    private val tz = TimeZone.getTimeZone("UTC")

    private fun ms(s: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = tz
        return fmt.parse(s)!!.time
    }

    @Test fun parsesV1() {
        val l = parseReminderList(
            """{"v":1,"updatedAt":5,"items":[{"key":"a#0","taskId":"a","type":"habit","title":"Run","body":"go","time":"08:30","date":null,"days":null}]}"""
        )!!
        assertEquals(1, l.size)
        val it = l[0]
        assertEquals("a#0", it.key)
        assertEquals("a", it.taskId)
        assertEquals("habit", it.type)
        assertEquals("Run", it.title)
        assertEquals("go", it.body)
        assertEquals(8, it.hour)
        assertEquals(30, it.minute)
        assertNull(it.date)
        assertNull(it.days)
    }

    // CR-PWA-001 mirror: a blank `date` string marks a broken once item and is skipped.
    @Test fun blankDateStringIsSkipped() {
        val l = parseReminderList(
            """{"v":1,"items":[
                {"key":"t#0","taskId":"t","type":"todo","time":"09:00","date":"","days":null},
                {"key":"t#1","taskId":"t","type":"todo","time":"09:00","date":"2026-10-01","days":null}
            ]}"""
        )!!
        assertEquals(listOf("t#1"), l.map { it.key })
        assertEquals("2026-10-01", l[0].date)
    }

    // Guard (option A): date:null + days:null is an every-day reminder and must stay.
    @Test fun nullDateNullDaysStaysEveryDay() {
        val l = parseReminderList(
            """{"v":1,"items":[{"key":"t#0","taskId":"t","type":"todo","title":"T","time":"09:00","date":null,"days":null}]}"""
        )!!
        assertEquals(1, l.size)
        assertNull(l[0].date)
        assertNull(l[0].days)
    }

    @Test fun badFilesReturnNull() {
        assertNull(parseReminderList("not json"))
        assertNull(parseReminderList("""{"v":2,"items":[]}"""))
        assertNull(parseReminderList("""{"v":1}"""))
    }

    @Test fun skipsItemsWithMissingKeyOrBadTime() {
        val l = parseReminderList(
            """{"v":1,"items":[
                {"taskId":"a","time":"08:00"},
                {"key":"b#0","taskId":"b","time":"25:00"},
                {"key":"c#0","taskId":"c","time":"08:00"}
            ]}"""
        )!!
        assertEquals(listOf("c#0"), l.map { it.key })
    }

    @Test fun everyDayRecurringFiresNextOccurrence() {
        val item = ReminderItem("k", "t", "habit", "T", "", 8, 0, null, null)
        val now = ms("2026-09-24 07:00:00") // Thursday
        val fire = nextFireMs(item, now, tz)
        assertEquals(ms("2026-09-24 08:00:00"), fire)
    }

    @Test fun everyDayRecurringRollsToTomorrowWhenPast() {
        val item = ReminderItem("k", "t", "habit", "T", "", 8, 0, null, null)
        val now = ms("2026-09-24 09:00:00")
        val fire = nextFireMs(item, now, tz)
        assertEquals(ms("2026-09-25 08:00:00"), fire)
    }

    @Test fun daysRestrictToAllowedWeekdays() {
        // 2026-09-24 is Thursday (index 4). Allow only Saturday (6).
        val days = listOf(false, false, false, false, false, false, true)
        val item = ReminderItem("k", "t", "habit", "T", "", 8, 0, null, days)
        val now = ms("2026-09-24 07:00:00")
        val fire = nextFireMs(item, now, tz)
        assertEquals(ms("2026-09-26 08:00:00"), fire)
    }

    @Test fun allFalseDaysNeverFires() {
        val days = List(7) { false }
        val item = ReminderItem("k", "t", "habit", "T", "", 8, 0, null, days)
        assertNull(nextFireMs(item, ms("2026-09-24 07:00:00"), tz))
    }

    @Test fun oneShotInFutureFires() {
        val item = ReminderItem("k", "t", "todo", "T", "", 8, 0, "2026-09-30", null)
        val fire = nextFireMs(item, ms("2026-09-24 07:00:00"), tz)
        assertEquals(ms("2026-09-30 08:00:00"), fire)
    }

    @Test fun oneShotInPastReturnsNull() {
        val item = ReminderItem("k", "t", "todo", "T", "", 8, 0, "2026-09-01", null)
        assertNull(nextFireMs(item, ms("2026-09-24 07:00:00"), tz))
    }

    @Test fun oneShotIgnoresDaysField() {
        val days = List(7) { false } // would never fire if treated as recurring
        val item = ReminderItem("k", "t", "todo", "T", "", 8, 0, "2026-09-30", days)
        val fire = nextFireMs(item, ms("2026-09-24 07:00:00"), tz)
        assertEquals(ms("2026-09-30 08:00:00"), fire)
    }

    @Test fun exactlyAtNowIsNotFiredAgain() {
        val item = ReminderItem("k", "t", "habit", "T", "", 8, 0, null, null)
        val now = ms("2026-09-24 08:00:00")
        val fire = nextFireMs(item, now, tz)
        assertTrue(fire!! > now)
        assertEquals(ms("2026-09-25 08:00:00"), fire)
    }
}
