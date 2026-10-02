// HAND-OWNED (quick-log). JVM tests for the local tap queue (TWA-C4): tmp sweep, rejected folder, failure counter.
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InboxStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1790000000000L
    private val hour = 60L * 60L * 1000L

    private fun rec(id: String) = InboxRecord(id = id, habitId = "h", dir = 1, ts = now, tzOffsetMin = -120)

    private fun fileAt(dir: File, name: String, text: String, ageMs: Long): File {
        dir.mkdirs()
        val f = File(dir, name)
        f.writeText(text, Charsets.UTF_8)
        assertTrue(f.setLastModified(now - ageMs))
        return f
    }

    @Test fun sweepMovesOnlyOldTmpOutOfQueue() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        store.write(rec("abcdefgh-0001"))
        val old = fileAt(dir, "abcdefgh-0002.json.tmp", "old-tap", 2 * hour)
        val young = fileAt(dir, "abcdefgh-0003.json.tmp", "young-tap", 5 * 60 * 1000L)
        val oldCounterTmp = fileAt(File(dir, ".fail"), "abcdefgh-0001.json.tmp", "7", 2 * hour)

        store.sweepTmp(now)

        assertFalse("old tmp must leave the queue folder", old.exists())
        // Never delete a tap: an old tmp is parked in rejected/ with its bytes unchanged.
        assertEquals("old-tap", File(dir, "rejected/abcdefgh-0002.json.tmp").readText(Charsets.UTF_8))
        assertTrue("young tmp may be a write in progress", young.exists())
        assertFalse("stale counter tmp is not a tap and goes", oldCounterTmp.exists())
        assertEquals(1, store.count())
        assertEquals(1, store.rejectedCount())
    }

    /** Review minor: kills the mutation that drops the `.tmp` filter in sweepTmp. */
    @Test fun sweepKeepsOldQueuedJson() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        val queued = fileAt(dir, "abcdefgh-0009.json", "queued-tap", 48 * hour)

        store.sweepTmp(now)

        assertTrue("an old queued tap must stay in the queue", queued.exists())
        assertEquals(listOf("abcdefgh-0009.json"), store.list().map { it.name })
        assertEquals(0, store.rejectedCount())
    }

    @Test fun rejectMovesOutOfQueue() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        store.write(rec("abcdefgh-0001"))
        store.write(rec("abcdefgh-0002"))
        val f = File(dir, "abcdefgh-0001.json")
        val bytes = f.readBytes()

        assertTrue(store.reject(f))

        assertEquals(listOf("abcdefgh-0002.json"), store.list().map { it.name })
        assertEquals(1, store.count())
        assertEquals(1, store.rejectedCount())
        assertTrue(bytes.contentEquals(File(dir, "rejected/abcdefgh-0001.json").readBytes()))
    }

    @Test fun rejectNeverOverwritesAnEarlierRejectedFile() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        fileAt(File(dir, "rejected"), "bad name.json", "first", 0)
        val second = fileAt(dir, "bad name.json", "second", 0)

        assertTrue(store.reject(second))

        assertEquals(2, store.rejectedCount())
        assertEquals("first", File(dir, "rejected/bad name.json").readText(Charsets.UTF_8))
        assertEquals(0, store.count())
    }

    @Test fun failureCounterSurvivesAndClears() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        store.write(rec("abcdefgh-0001"))
        store.write(rec("abcdefgh-0002"))
        val a = File(dir, "abcdefgh-0001.json")
        val b = File(dir, "abcdefgh-0002.json")

        assertEquals(1, store.recordFailure(a))
        assertEquals(2, store.recordFailure(a))
        assertEquals("survives a new store (next worker run)", 3, InboxStore(dir).recordFailure(a))
        assertEquals(1, store.recordFailure(b))
        assertEquals("counters do not show up as queued taps", 2, store.count())

        store.delete(a)
        assertFalse(File(dir, ".fail/abcdefgh-0001.json").exists())
        store.reject(b)
        assertFalse(File(dir, ".fail/abcdefgh-0002.json").exists())
        assertEquals(0, store.count())
    }

    /** DL-25 "Try again": a rejected tap goes back into the queue with a fresh retry budget. */
    @Test fun requeueMovesRejectedBackAndResetsCounter() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        store.write(rec("abcdefgh-0001"))
        val f = File(dir, "abcdefgh-0001.json")
        val bytes = f.readBytes()
        store.recordFailure(f)
        assertTrue(store.reject(f))
        // A counter left behind (e.g. written after the reject) must not shorten the new retry budget.
        fileAt(File(dir, ".fail"), "abcdefgh-0001.json", "29", 0)

        assertEquals(1, store.requeueRejected())

        assertEquals(listOf("abcdefgh-0001.json"), store.list().map { it.name })
        assertTrue(bytes.contentEquals(File(dir, "abcdefgh-0001.json").readBytes()))
        assertEquals(0, store.rejectedCount())
        assertEquals("counter restarts", 1, store.recordFailure(File(dir, "abcdefgh-0001.json")))
    }

    @Test fun requeueStripsClashSuffixAndNeverOverwrites() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        val rej = File(dir, "rejected")
        fileAt(rej, "abcdefgh-0001.json", "first", 0)
        fileAt(rej, "abcdefgh-0001.json.1", "second", 0)
        fileAt(rej, "abcdefgh-0002.json.1", "only-suffixed", 0)
        fileAt(dir, "abcdefgh-0003.json", "in-queue", 0)
        fileAt(rej, "abcdefgh-0003.json", "rejected-copy", 0)

        assertEquals(2, store.requeueRejected())

        assertEquals("first", File(dir, "abcdefgh-0001.json").readText(Charsets.UTF_8))
        assertEquals("only-suffixed", File(dir, "abcdefgh-0002.json").readText(Charsets.UTF_8))
        assertEquals("queue file never overwritten", "in-queue", File(dir, "abcdefgh-0003.json").readText(Charsets.UTF_8))
        assertEquals("second", File(rej, "abcdefgh-0001.json.1").readText(Charsets.UTF_8))
        assertEquals("rejected-copy", File(rej, "abcdefgh-0003.json").readText(Charsets.UTF_8))
        assertEquals(2, store.rejectedCount())

        // Once the queued copy is gone, the next Try again moves the clashing one.
        store.delete(File(dir, "abcdefgh-0001.json"))
        assertEquals(1, store.requeueRejected())
        assertEquals("second", File(dir, "abcdefgh-0001.json").readText(Charsets.UTF_8))
    }

    /** A crash-left .tmp may be half-written: it stays parked, never uploaded. */
    @Test fun requeueSkipsTmpLeftovers() {
        val dir = tmp.newFolder("inbox")
        val store = InboxStore(dir)
        val rej = File(dir, "rejected")
        fileAt(rej, "abcdefgh-0001.json.tmp", "half", 0)
        fileAt(rej, "abcdefgh-0002.json.tmp.1", "half2", 0)
        fileAt(rej, "abcdefgh-0003.json", "good", 0)

        assertEquals(1, store.requeueRejected())

        assertEquals(listOf("abcdefgh-0003.json"), store.list().map { it.name })
        assertTrue(File(rej, "abcdefgh-0001.json.tmp").exists())
        assertTrue(File(rej, "abcdefgh-0002.json.tmp.1").exists())
        assertFalse(File(dir, "abcdefgh-0001.json.tmp").exists())
        assertEquals(2, store.rejectedCount())
    }
}
