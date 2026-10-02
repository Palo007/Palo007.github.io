// HAND-OWNED (quick-log). JVM tests for P6 Settings helpers (DL-12 battery, DL-16 last synced).
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    @Test fun unrestrictedBatteryButtonOnlyOnApi23PlusWhenOptimized() {
        assertTrue(showUnrestrictedBattery(23, false))
        assertTrue(showUnrestrictedBattery(34, false))
        assertFalse("already unrestricted", showUnrestrictedBattery(34, true))
        assertFalse("API 21-22 has no Doze / no dialog", showUnrestrictedBattery(22, false))
        assertFalse(showUnrestrictedBattery(21, false))
    }

    @Test fun autostartOfferedOnXiaomiFamily() {
        for (m in listOf("Xiaomi", "POCO", "Redmi", "xiaomi")) {
            val c = autostartComponents(m)
            assertTrue("$m has autostart screens", c.isNotEmpty())
            assertEquals("$m first is MIUI security center", "com.miui.securitycenter", c[0].pkg)
        }
    }

    @Test fun autostartOfferedOnOtherKillerOems() {
        for (m in listOf("HUAWEI", "HONOR", "OPPO", "realme", "OnePlus", "vivo")) {
            assertTrue(m, autostartComponents(m).isNotEmpty())
        }
    }

    @Test fun noAutostartOnStockOrUnknown() {
        for (m in listOf("Google", "samsung", "motorola", "", null)) {
            assertTrue("$m", autostartComponents(m).isEmpty())
        }
    }

    @Test fun syncAgeNeverWhenUnset() {
        assertEquals("never", syncAgeLine(0L, now))
        assertEquals("never", syncAgeLine(-5L, now))
    }

    @Test fun syncAgeRecent() {
        assertEquals("just now", syncAgeLine(now - 20_000L, now))
        assertEquals("just now", syncAgeLine(now + 30_000L, now)) // small clock skew
        assertEquals("5 min ago", syncAgeLine(now - 5 * min, now))
        assertEquals("3 h ago", syncAgeLine(now - 3 * hour - 10 * min, now))
        assertEquals("2 days ago", syncAgeLine(now - 2 * day, now))
        assertFalse(syncAgeLine(now - 2 * min, now).contains("open Questa"))
    }

    @Test fun syncAgeStaleAddsHint() {
        val s = syncAgeLine(now - 8 * day, now)
        assertTrue(s, s.startsWith("8 days ago"))
        assertTrue(s, s.contains("open Questa to sync"))
        assertFalse(syncAgeLine(now - 7 * day, now).contains("open Questa"))
    }

    @Test fun exactAlarmHintSurvivesRenderUntilAllowed() {
        assertTrue(showExactAlarmHint(requested = true, exactAllowed = false))
        assertFalse(showExactAlarmHint(requested = true, exactAllowed = true))
        assertFalse(showExactAlarmHint(requested = false, exactAllowed = false))
    }

    @Test fun retryToastOnlyWhenNothingMoved() {
        assertEquals("Nothing to retry", retryToast(0))
        assertEquals(null, retryToast(1))
        assertEquals(null, retryToast(3))
    }
}
