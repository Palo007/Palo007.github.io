// HAND-OWNED (quick-log). JVM tests for ShortcutPublisher's id plan (TWA-C1, shortcut half).
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutPlanTest {
    private val current = listOf("ql-h-a", "ql-h-a-down")

    private fun plan(connected: Boolean, broken: Boolean, text: String?) =
        ShortcutPublisher.plannedShortcutIds(connected, broken, ShortcutPublisher.storedHabits(text), current)

    @Test fun unreadableStoredListKeepsHabitShortcuts() {
        val p = plan(connected = true, broken = false, text = """{"v":2}""")
        assertEquals(emptySet<String>(), p.remove)
        assertEquals(current.toSet(), p.keep)
        assertEquals(emptySet<String>(), p.add)
        assertEquals(false, p.writeSignature)
    }

    @Test fun unreadableWhileBrokenAddsReconnectKeepsHabits() {
        val p = plan(connected = true, broken = true, text = """{"v":2}""")
        assertEquals(setOf("ql-connect"), p.add)
        assertEquals(emptySet<String>(), p.remove)
        assertEquals(current.toSet(), p.keep)
        assertEquals(true, p.connect)
    }

    @Test fun validEmptyListClearsHabitShortcuts() {
        val p = plan(connected = true, broken = false, text = """{"v":1,"habits":[]}""")
        assertEquals(current.toSet(), p.remove)
        assertEquals(emptySet<String>(), p.add)
        assertTrue(p.writeSignature)
    }

    @Test fun nothingStoredClearsHabitShortcuts() {
        val p = plan(connected = true, broken = false, text = null)
        assertEquals(current.toSet(), p.remove)
        assertEquals(emptySet<String>(), p.add)
    }

    @Test fun notConnectedShowsConnectOnly() {
        for (text in listOf(null, """{"v":2}""", """{"v":1,"habits":[{"id":"a","title":"Run","quickLog":true}]}""")) {
            val p = plan(connected = false, broken = false, text = text)
            assertEquals(setOf("ql-connect"), p.add)
            assertEquals(current.toSet(), p.remove)
            assertEquals(false, p.connect)
        }
    }
}
