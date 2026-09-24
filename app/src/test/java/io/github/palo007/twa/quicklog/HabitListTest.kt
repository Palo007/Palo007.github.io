// HAND-OWNED (quick-log). JVM tests for /inbox-meta/habits.json handling.
package io.github.palo007.twa.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HabitListTest {
    @Test fun parsesV1() {
        val l = parseHabitList("""{"v":1,"updatedAt":5,"habits":[{"id":"a","title":"Run","quickLog":true},{"id":"b","title":" ","quickLog":false},{"title":"no id"}]}""")!!
        assertEquals(listOf(Habit("a", "Run", true, listOf(1)), Habit("b", "b", false, emptyList())), l)
    }

    @Test fun badFilesReturnNull() {
        assertNull(parseHabitList("not json"))
        assertNull(parseHabitList("""{"v":2,"habits":[]}"""))
        assertNull(parseHabitList("""{"v":1}"""))
    }

    @Test fun missingDirsWithQuickLogTrueResolvesToPlusOne() {
        val l = parseHabitList("""{"v":1,"habits":[{"id":"a","title":"A","quickLog":true}]}""")!!
        assertEquals(listOf(1), l[0].dirs)
    }

    @Test fun explicitDirsAreKept() {
        val l = parseHabitList("""{"v":1,"habits":[{"id":"a","title":"A","quickLog":true,"dirs":[1,-1]}]}""")!!
        assertEquals(listOf(1, -1), l[0].dirs)
    }

    @Test fun invalidDirValuesAreDroppedAndDeduped() {
        val l = parseHabitList("""{"v":1,"habits":[{"id":"a","title":"A","quickLog":true,"dirs":[0,2,"x",1,1,-1]}]}""")!!
        assertEquals(listOf(1, -1), l[0].dirs)
    }

    @Test fun missingDirsWithQuickLogFalseResolvesToEmpty() {
        val l = parseHabitList("""{"v":1,"habits":[{"id":"a","title":"A","quickLog":false}]}""")!!
        assertEquals(emptyList<Int>(), l[0].dirs)
    }

    @Test fun picksItemsInOrderUpToCapDedupedById() {
        val hs = listOf(
            Habit("a", "A", false, emptyList()),
            Habit("b", "B", true, listOf(1, -1)),
            Habit("c", "C", true, listOf(1)),
            Habit("b", "B again", true, listOf(1)),
            Habit("d", "D", true, listOf(1)),
            Habit("e", "E", true, listOf(1)),
        )
        // b contributes 2 items (+1 then -1), c contributes 1: cap of 3 is reached mid-way
        // through b and d/e never show up.
        val items = pickShortcutItems(hs, 3)
        assertEquals(listOf("b" to 1, "b" to -1, "c" to 1), items.map { it.habit.id to it.dir })
        assertEquals(emptyList<ShortcutItem>(), pickShortcutItems(hs, 0))
    }

    @Test fun capCountsItemsNotHabits() {
        val hs = listOf(
            Habit("a", "A", true, listOf(1, -1)),
            Habit("b", "B", true, listOf(1, -1)),
        )
        // Cap of 3 items: a's two items plus only the first of b's two.
        val items = pickShortcutItems(hs, 3)
        assertEquals(3, items.size)
        assertEquals(listOf("a" to 1, "a" to -1, "b" to 1), items.map { it.habit.id to it.dir })
    }

    @Test fun withinHabitPlusOneComesBeforeMinusOne() {
        val hs = listOf(Habit("a", "A", true, listOf(-1, 1)))
        val items = pickShortcutItems(hs, 2)
        assertEquals(listOf(1, -1), items.map { it.dir })
    }

    @Test fun habitsWithEmptyResolvedDirsAreSkipped() {
        val hs = listOf(Habit("a", "A", true, emptyList()), Habit("b", "B", true, listOf(1)))
        assertEquals(listOf("b"), pickShortcutItems(hs, 3).map { it.habit.id })
    }
}
