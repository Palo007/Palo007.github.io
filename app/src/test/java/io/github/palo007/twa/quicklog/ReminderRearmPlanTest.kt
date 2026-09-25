// HAND-OWNED (quick-log). JVM tests for ReminderScheduler.rearmPlan (TWA-C1) and TWA-A7 detector.
package io.github.palo007.twa.quicklog

import io.github.palo007.twa.quicklog.ReminderScheduler.RearmPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderRearmPlanTest {
    private val armed = setOf("a#0", "b#0")

    @Test fun unparseableStoredListKeepsArmed() {
        assertEquals(RearmPlan.Keep, ReminderScheduler.rearmPlan("{\"v\":2}", armed))
        assertEquals(RearmPlan.Keep, ReminderScheduler.rearmPlan("not json", armed))
    }

    @Test fun validEmptyListCancelsAll() {
        assertEquals(
            RearmPlan.Apply(emptyList(), armed),
            ReminderScheduler.rearmPlan("""{"v":1,"items":[]}""", armed)
        )
    }

    @Test fun nothingStoredCancelsAll() {
        assertEquals(RearmPlan.Apply(emptyList(), armed), ReminderScheduler.rearmPlan(null, armed))
    }

    @Test fun removedKeyCancelsOnlyThat() {
        val plan = ReminderScheduler.rearmPlan(
            """{"v":1,"items":[{"key":"a#0","taskId":"a","time":"08:00"}]}""", armed
        )
        assertTrue(plan is RearmPlan.Apply)
        plan as RearmPlan.Apply
        assertEquals(listOf("a#0"), plan.items.map { it.key })
        assertEquals(setOf("b#0"), plan.cancelKeys)
    }

    @Test fun detectsRequestCodeCollision() {
        assertEquals("Aa#0".hashCode(), "BB#0".hashCode()) // precondition
        assertEquals(setOf("Aa#0", "BB#0"), ReminderScheduler.requestCodeCollisions(listOf("Aa#0", "BB#0", "c#0")))
        assertEquals(emptySet<String>(), ReminderScheduler.requestCodeCollisions(listOf("t-once-dated#0", "h-weekly#0")))
    }
}
