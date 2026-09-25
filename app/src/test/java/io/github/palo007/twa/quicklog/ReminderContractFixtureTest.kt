// HAND-OWNED (quick-log). JVM tests against the CR-PWA-001 contract fixture (copy of fix-plans/shared).
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class ReminderContractFixtureTest {
    private fun bytes(name: String): ByteArray {
        val s = javaClass.classLoader!!.getResourceAsStream("fixtures/CR-PWA-001/$name")
        assertNotNull("missing fixture $name", s)
        return s!!.use { it.readBytes() }
    }

    /** SHA-256 after CRLF->LF (core.autocrlf=true may add CR on checkout). */
    private fun lfSha256(name: String): String {
        val lf = bytes(name).filter { it != '\r'.code.toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(lf).joinToString("") { "%02x".format(it) }
    }

    @Test fun fixtureCopyUnchanged() {
        assertEquals(
            "d4005e2b23d34de24de5df533e067cefac5de6933ee07faaa24acd3393119190",
            lfSha256("expected_reminders.json")
        )
        assertEquals(
            "b1a4326032378b3a74f91f3362018e6a59fff2cd72c1e69d540b003cfe6c23c5",
            lfSha256("input_state_once_no_date.json")
        )
    }

    @Test fun expectedRemindersParse() {
        // updatedAt is the placeholder string "<Date.now()>"; the reader ignores it.
        val l = parseReminderList(String(bytes("expected_reminders.json"), Charsets.UTF_8))
        assertNotNull(l)
        assertEquals(listOf("t-once-dated#0", "h-weekly#0"), l!!.map { it.key })
        val once = l[0]
        assertEquals("2026-10-01", once.date)
        assertEquals(9, once.hour)
        assertEquals(30, once.minute)
        assertNull(once.days)
        val weekly = l[1]
        assertNull(weekly.date)
        assertEquals(8, weekly.hour)
        assertEquals(0, weekly.minute)
        assertEquals(listOf(false, true, true, true, true, true, false), weekly.days)
    }
}
