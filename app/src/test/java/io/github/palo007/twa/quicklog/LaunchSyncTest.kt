// HAND-OWNED (quick-log). JVM tests for the delayed follow-up reminder sync on launch (TWA-26, rule TWA-27).
package io.github.palo007.twa.quicklog

import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.impl.model.WorkSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class LaunchSyncTest {
    private fun spec(): WorkSpec {
        val req = LaunchSync.followUpRequest()
        assertNotNull("launch must build a follow-up request", req)
        return req.workSpec
    }

    @Test fun delayedAboutThreeMinutes() {
        assertEquals(TimeUnit.MINUTES.toMillis(3), spec().initialDelay)
    }

    @Test fun needsNetwork() {
        assertEquals(NetworkType.CONNECTED, spec().constraints.requiredNetworkType)
    }

    @Test fun notExpedited() {
        assertFalse("TWA-27: plain Worker, no getForegroundInfo, minSdk 21", spec().expedited)
    }

    @Test fun runsReminderSyncWorker() {
        assertEquals(ReminderSyncWorker::class.java.name, spec().workerClassName)
    }

    @Test fun uniqueNameAndKeepPolicyAvoidPileUp() {
        assertNotEquals("questa-reminder-sync", LaunchSync.WORK_FOLLOW_UP)
        assertNotEquals("questa-reminder-sync-periodic", LaunchSync.WORK_FOLLOW_UP)
        assertEquals(ExistingWorkPolicy.KEEP, LaunchSync.POLICY)
    }
}
