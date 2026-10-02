// HAND-OWNED (quick-log). JVM tests for the "Native reminders" switch (P6, DL-03 / TWA-R1) and TWA-A6.
package io.github.palo007.twa.quicklog

import io.github.palo007.twa.quicklog.ReminderScheduler.RearmPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRemindersSwitchTest {
    private val armed = setOf("a#0", "b#0")
    private val list = """{"v":1,"items":[{"key":"a#0","taskId":"a","time":"08:00"}]}"""

    @Test fun switchOffCancelsAllAndArmsNothing() {
        assertEquals(RearmPlan.Apply(emptyList(), armed), ReminderScheduler.rearmPlan(list, armed, enabled = false))
        assertEquals(RearmPlan.Apply(emptyList(), armed), ReminderScheduler.rearmPlan("not json", armed, enabled = false))
    }

    @Test fun switchOnArmsAsBefore() {
        val plan = ReminderScheduler.rearmPlan(list, armed, enabled = true) as RearmPlan.Apply
        assertEquals(listOf("a#0"), plan.items.map { it.key })
    }

    @Test fun askOnlyWhenOwnerFoundNotAskedAndStillOn() {
        assertTrue(shouldAskOwner(ownerFound = true, alreadyAsked = false, enabled = true))
        assertFalse("no Kotlin app", shouldAskOwner(false, false, true))
        assertFalse("asked before", shouldAskOwner(true, true, true))
        assertFalse("already off", shouldAskOwner(true, false, false))
    }

    @Test fun answerYesTurnsNativeOffNoKeepsOn() {
        assertFalse(enabledAfterAnswer(yes = true))
        assertTrue(enabledAfterAnswer(yes = false))
    }

    @Test fun bootReceiverRearmsOnExactAlarmGrant() {
        assertTrue(isRearmAction("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
    }

    @Test fun bootReceiverKeepsOldActionsAndIgnoresOthers() {
        for (a in listOf(
            "android.intent.action.BOOT_COMPLETED", "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED", "android.intent.action.MY_PACKAGE_REPLACED"
        )) assertTrue(a, isRearmAction(a))
        assertFalse(isRearmAction("android.intent.action.SCREEN_ON"))
        assertFalse(isRearmAction(null))
    }
}
