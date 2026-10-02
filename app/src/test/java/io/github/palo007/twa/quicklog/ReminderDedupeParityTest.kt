// HAND-OWNED (quick-log). P7 / TWA-12: the web (DelegationService) and native (ReminderReceiver)
// sides must derive the SAME slot key `title|yyyy-MM-dd|HH:MM` (DL-17, format kept) for one reminder.
// The PWA has no key of its own: it sends title = t.title || 'Questa Reminder' and body either plain
// (on time) or 'Missed at ' + r.time + ' - ' + body (Opti app.js getReminderNotificationPayload);
// reminders.json carries the same title and time (Opti sync.js syncInboxWriteReminders).
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ReminderDedupeParityTest {
    private val tz = TimeZone.getTimeZone("Europe/Bratislava")

    private fun ms(s: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = tz
        return fmt.parse(s)!!.time
    }

    private fun fmt(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm zzz", Locale.US)
        f.timeZone = tz
        return f.format(ms)
    }

    /** reminders.json as the PWA writes it (sync.js), one daily item "Walk" at the given time. */
    private fun item(time: String, title: String = "Walk", key: String = "t1#0"): ReminderItem {
        val json = """{"v":1,"updatedAt":0,"items":[{"key":"$key","taskId":"t1","type":"daily",
            "title":"$title","body":"Check off your daily task!","time":"$time","date":null,"days":null}]}"""
        return parseReminderList(json)!!.single()
    }

    private fun native(it: ReminderItem, fireAt: Long, now: Long) =
        ReminderDedupe.nativeSlotKey(it.title, it.hour, it.minute, fireAt, now, tz)

    // --- parity on the wire format ---------------------------------------------------------

    @Test fun onTimeWebCopyMatchesNativeKey() {
        val r = item("08:00")
        val nativeKey = native(r, ms("2026-10-01 08:00:00"), ms("2026-10-01 08:00:03"))
        assertEquals("Walk|2026-10-01|08:00", nativeKey)
        // PWA scheduler ticks every 60 s, so the on-time copy arrives anywhere in 08:00:00..08:00:59.
        for (s in listOf("08:00:00", "08:00:59")) {
            val web = ReminderDedupe.webSlotKeys("Walk", "Check off your daily task!", ms("2026-10-01 $s"), tz)
            assertTrue("web $s keys $web", nativeKey in web)
        }
    }

    @Test fun missedWebCopyNamesItsOwnSlot() {
        val r = item("08:00")
        val nativeKey = native(r, ms("2026-10-01 08:00:00"), ms("2026-10-01 08:00:01"))
        val web = ReminderDedupe.webSlotKeys(
            "Walk", "Missed at 08:00 - Check off your daily task!", ms("2026-10-01 14:00:00"), tz
        )
        assertEquals(listOf(nativeKey), web)
    }

    @Test fun webDropsWhenNativeAlreadyShownAndPassesOtherwise() {
        val r = item("08:00")
        val nativeKey = native(r, ms("2026-10-01 08:00:00"), ms("2026-10-01 08:00:01"))
        val now = ms("2026-10-01 08:00:40")
        val drop = ReminderDedupe.webDecision("Walk", "x", now, tz, setOf("Walk")) { it == nativeKey }
        assertEquals(ReminderDedupe.WebDecision.Drop(nativeKey), drop)
        val pass = ReminderDedupe.webDecision("Walk", "x", now, tz, setOf("Walk")) { false }
        assertTrue(pass is ReminderDedupe.WebDecision.Pass)
        assertTrue(nativeKey in (pass as ReminderDedupe.WebDecision.Pass).slots)
    }

    // --- B-T6: window size --------------------------------------------------------------------

    @Test fun webOneMinuteLateStillMatchesTwoMinutesDoesNot() {
        val nativeKey = native(item("08:00"), ms("2026-10-01 08:00:00"), ms("2026-10-01 08:00:00"))
        assertTrue(nativeKey in ReminderDedupe.webSlotKeys("Walk", "x", ms("2026-10-01 08:01:30"), tz))
        // Pinned legacy window: a copy > 1 min late is a duplicate (TWA-12 B-T6, DEFERRED).
        assertFalse(nativeKey in ReminderDedupe.webSlotKeys("Walk", "x", ms("2026-10-01 08:02:00"), tz))
    }

    // --- late fire after midnight (A4) ----------------------------------------------------------

    @Test fun lateNativeFireAfterMidnightKeepsIntendedDateAndMatchesWeb() {
        val r = item("23:59")
        val nativeKey = native(r, ms("2026-10-01 23:59:00"), ms("2026-10-02 00:01:10"))
        assertEquals("Walk|2026-10-01|23:59", nativeKey)
        val web = ReminderDedupe.webSlotKeys("Walk", "x", ms("2026-10-01 23:59:20"), tz)
        assertTrue("web $web", nativeKey in web)
    }

    @Test fun webOnTimeCopyDeliveredAfterMidnightGetsNextDate_documentedMismatch() {
        // Finding P7-F1: the web side always uses TODAY at delegation; an on-time 23:59 copy that
        // reaches DelegationService at 00:00:20 is keyed on the next date and misses the native slot.
        val nativeKey = native(item("23:59"), ms("2026-10-01 23:59:00"), ms("2026-10-01 23:59:01"))
        val web = ReminderDedupe.webSlotKeys("Walk", "x", ms("2026-10-02 00:00:20"), tz)
        assertEquals(listOf("Walk|2026-10-02|00:00", "Walk|2026-10-02|23:59"), web)
        assertFalse(nativeKey in web)
    }

    // --- B-T3: same title, different times -------------------------------------------------------

    @Test fun sameTitleDifferentTimesDoNotCollide() {
        val a = native(item("08:00", key = "t1#0"), ms("2026-10-01 08:00:00"), ms("2026-10-01 08:00:00"))
        val b = native(item("08:30", key = "t1#1"), ms("2026-10-01 08:30:00"), ms("2026-10-01 08:30:00"))
        assertNotEquals(a, b)
        val web = ReminderDedupe.webDecision("Walk", "x", ms("2026-10-01 08:30:10"), tz, setOf("Walk")) { it == a }
        assertTrue("08:00 native must not drop the 08:30 web copy: $web", web is ReminderDedupe.WebDecision.Pass)
    }

    // --- DST spring-forward gap (Europe/Bratislava 2026-03-29 02:00 -> 03:00) --------------------

    @Test fun springForwardGapSlotKeepsWallClockKeyOnBothSides() {
        val r = item("02:30")
        val fireAt = nextFireMs(r, ms("2026-03-29 01:00:00"), tz)
        assertNotNull(fireAt)
        // Lenient Calendar resolves the non-existent 02:30 to 03:30 CEST.
        assertEquals("2026-03-29 03:30 CEST", fmt(fireAt!!))
        val nativeKey = native(r, fireAt, fireAt)
        assertEquals("Walk|2026-03-29|02:30", nativeKey)
        // PWA: isReminderDue never matches "02:30" that day; at 03:00 isReminderMissed is true and
        // the body names r.time. Same key, so the web copy (first) wins and native skips at 03:30.
        val web = ReminderDedupe.webSlotKeys(
            "Walk", "Missed at 02:30 - Check off your daily task!", ms("2026-03-29 03:00:05"), tz
        )
        assertEquals(listOf(nativeKey), web)
    }

    // --- B-T1: unknown title is never tracked -------------------------------------------------------

    @Test fun unknownTitleIsUntracked() {
        val d = ReminderDedupe.webDecision("Walk dog", "x", ms("2026-10-01 08:00:10"), tz, setOf("Walk")) { true }
        assertEquals(ReminderDedupe.WebDecision.Untracked, d)
    }
}
