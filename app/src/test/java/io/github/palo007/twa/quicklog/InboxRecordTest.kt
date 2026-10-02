// HAND-OWNED (quick-log). JVM tests for the frozen inbox contract (Opti sync.js inboxParseRecord).
package io.github.palo007.twa.quicklog

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.TimeZone

class InboxRecordTest {
    // quote, backslash, newline, control char, U+2028, non-ASCII; built from codes to keep the source plain.
    private val AWKWARD = "we" + 34.toChar() + "ird" + 92.toChar() + "id" + 10.toChar() + 1.toChar() + 0x2028.toChar() + 0x17E.toChar()
    private val cest = TimeZone.getTimeZone("Europe/Bratislava")
    private val summerMs = 1790000000000L // 2026-09-21, CEST

    @Test fun jsonMatchesWebContract() {
        val r = InboxRecord.create("t_abc123", summerMs, cest)
        val o = JSONObject(r.toJson())
        assertEquals(1, o.get("v"))
        assertEquals("habit", o.getString("kind"))
        assertEquals("t_abc123", o.getString("habitId"))
        assertEquals(1, o.get("dir"))               // number, not "1"
        assertEquals(summerMs, o.getLong("ts"))
        assertTrue(o.get("ts") is Number)
        assertEquals(-120, o.getInt("tzOffsetMin")) // JS getTimezoneOffset sign
        assertEquals("android-shortcut", o.getString("src"))
        assertTrue(InboxRecord.ID_RE.matches(o.getString("id")))
        assertEquals(o.getString("id") + ".json", r.fileName)
        assertEquals(8, o.length())
    }

    @Test fun twoTapsGetTwoIds() {
        val a = InboxRecord.create("h", summerMs, cest)
        val b = InboxRecord.create("h", summerMs, cest)
        assertTrue(a.id != b.id)
    }

    @Test fun escapesAwkwardHabitIds() {
        val id = AWKWARD
        val o = JSONObject(InboxRecord.create(id, summerMs, cest).toJson())
        assertEquals(id, o.getString("habitId"))
    }

    @Test fun rejectsBadValues() {
        val ok = InboxRecord.create("h", summerMs, cest)
        for (bad in listOf<() -> Unit>(
            { ok.copy(id = "short") },
            { ok.copy(id = "has space in it") },
            { ok.copy(habitId = "") },
            { ok.copy(dir = 0) },
            { ok.copy(ts = 0) },
        )) {
            try { bad(); fail("accepted a bad record") } catch (e: IllegalArgumentException) { }
        }
    }

    /**
     * TWA-C3: exact bytes the web parser (Opti sync.js inboxParseRecord) accepts. Both strings were
     * checked once with node against the real parser (run note P1c.md). Escapes are JSON escapes.
     */
    @Test fun goldenBytesForWebParser() {
        val plain = InboxRecord(id = "abcdefgh-0002", habitId = "t_abc123", dir = 1, ts = summerMs, tzOffsetMin = -120)
        assertEquals(
            "{\"v\":1,\"id\":\"abcdefgh-0002\",\"kind\":\"habit\",\"habitId\":\"t_abc123\"," +
                "\"dir\":1,\"ts\":1790000000000,\"tzOffsetMin\":-120,\"src\":\"android-shortcut\"}",
            plain.toJson(),
        )
        val awkward = InboxRecord(id = "abcdefgh-0001", habitId = AWKWARD, dir = 1, ts = summerMs, tzOffsetMin = -120)
        assertEquals(
            "{\"v\":1,\"id\":\"abcdefgh-0001\",\"kind\":\"habit\"," +
                "\"habitId\":\"we\\\"ird\\\\id\\n\\u0001\\u2028ž\"," +
                "\"dir\":1,\"ts\":1790000000000,\"tzOffsetMin\":-120,\"src\":\"android-shortcut\"}",
            awkward.toJson(),
        )
    }
}
