// HAND-OWNED (quick-log). JVM tests for P9 / P6-m1: "Questa last synced" also on a NotFound answer.
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSyncOutcomeTest {
    @Test fun successfulDownloadRecordsStateSync() {
        assertTrue(recordsStateSync(ReminderDownload.OK))
        assertTrue(recordsStateSync(ReminderDownload.UNREADABLE))
    }

    @Test fun notFoundIsStillASuccessfulSync() {
        assertTrue(recordsStateSync(ReminderDownload.NOT_FOUND))
    }

    @Test fun errorDoesNotRecordStateSync() {
        assertFalse(recordsStateSync(ReminderDownload.ERROR))
    }
}
