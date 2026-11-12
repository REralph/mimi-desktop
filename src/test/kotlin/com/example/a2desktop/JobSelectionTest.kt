package com.example.a2desktop

import com.example.a2desktop.logic.Ticks
import com.example.a2desktop.logic.rowIndexAt
import com.example.a2desktop.logic.selectRange
import com.example.a2desktop.logic.ticksOf
import com.example.a2desktop.logic.toggleAll
import com.example.a2desktop.logic.visibleSelection
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ticking jobs as plain data: the select-all box, dragging a run of ticks down or up, and which ticked jobs an action may touch. */
class JobSelectionTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test fun theSelectAllBoxIsEmptyHalfOrFullDependingOnHowManyShownJobsAreTicked() {
        assertEquals(Ticks.NONE, ticksOf(order, emptySet()))
        assertEquals(Ticks.SOME, ticksOf(order, setOf("b")))
        assertEquals(Ticks.SOME, ticksOf(order, setOf("a", "b", "c", "d")))
        assertEquals(Ticks.ALL, ticksOf(order, order.toSet()))
        assertEquals(Ticks.NONE, ticksOf(order, setOf("zzz")), "a tick on a job that isn't shown doesn't count")
        assertEquals(Ticks.ALL, ticksOf(listOf("a", "b"), setOf("a", "b", "zzz")), "and doesn't spoil 'all' either")
        assertEquals(Ticks.NONE, ticksOf(emptyList(), setOf("a")), "an empty table has nothing ticked")
    }

    @Test fun pressingTheSelectAllBoxTicksAllUnlessTheyAreAlreadyAllTickedAndLeavesOtherJobsAlone() {
        assertEquals(order.toSet(), toggleAll(order, emptySet()))
        assertEquals(order.toSet(), toggleAll(order, setOf("b")), "with only some ticked it ticks the rest")
        assertEquals(emptySet(), toggleAll(order, order.toSet()), "with all ticked it clears them")
        assertEquals(setOf("x"), toggleAll(order, order.toSet() + "x"), "a tick in another table stays")
        assertEquals(setOf("x") + order, toggleAll(order, setOf("x")), "and is kept when ticking all")
        assertEquals(setOf("a"), toggleAll(listOf("a"), emptySet()))
    }

    @Test fun aDragTicksEveryJobFromWhereItStartedToWhereItIsNowInEitherDirection() {
        assertEquals(setOf("b", "c", "d"), selectRange(emptySet(), order, 1, 3, tick = true))
        assertEquals(setOf("b", "c", "d"), selectRange(emptySet(), order, 3, 1, tick = true), "up is the same as down")
        assertEquals(setOf("c"), selectRange(emptySet(), order, 2, 2, tick = true), "a click is a run of one")
        assertEquals(order.toSet(), selectRange(emptySet(), order, 0, 4, tick = true))
    }

    @Test fun aDragThatIsUntickingClearsTheRunAndKeepsTheRestAsTheyWere() {
        val base = setOf("a", "b", "c", "d")
        assertEquals(setOf("a", "d"), selectRange(base, order, 1, 2, tick = false))
        assertEquals(setOf("a", "b", "c", "d", "e"), selectRange(base, order, 4, 3, tick = true), "ticking keeps what was already ticked")
    }

    @Test fun theBaseIsNeverChangedSoDraggingBackGivesTheBoxesBack() {
        val base = setOf("a")
        val down = selectRange(base, order, 1, 4, tick = true)
        assertEquals(setOf("a", "b", "c", "d", "e"), down)
        val backUp = selectRange(base, order, 1, 2, tick = true)
        assertEquals(setOf("a", "b", "c"), backUp, "e and d are back to unticked, and a is still ticked from before")
        assertEquals(setOf("a"), base)
    }

    @Test fun aDragPastTheEndsStopsAtTheFirstAndLastJobAndNoJobsMeansNothingToDo() {
        assertEquals(order.toSet(), selectRange(emptySet(), order, -3, 99, tick = true))
        assertEquals(setOf("a", "b"), selectRange(emptySet(), order, 1, -5, tick = true))
        assertEquals(setOf("x"), selectRange(setOf("x"), emptyList(), 0, 3, tick = true))
    }

    @Test fun thePointerIsOverTheLastRowThatHasStartedAndAboveTheFirstIsTheFirst() {
        val tops = listOf(0f, 50f, 100f, 160f)
        assertEquals(0, rowIndexAt(tops, 10f))
        assertEquals(1, rowIndexAt(tops, 50f), "a row starts at its top")
        assertEquals(1, rowIndexAt(tops, 99f))
        assertEquals(2, rowIndexAt(tops, 100f))
        assertEquals(3, rowIndexAt(tops, 9999f), "below the last is the last")
        assertEquals(0, rowIndexAt(tops, -40f), "above the first is the first")
        assertEquals(0, rowIndexAt(emptyList(), 5f), "no rows at all")
        assertEquals(1, rowIndexAt(listOf(0f, 50f, null), 500f), "a row that hasn't been laid out yet is skipped")
        assertEquals(0, rowIndexAt(listOf(null, null), 500f), "none laid out yet")
    }

    @Test fun onlyTickedJobsThatAreOnScreenCountInTheOrderTheyAreShown() {
        assertEquals(listOf("b", "d"), visibleSelection(setOf("d", "b", "gone"), order))
        assertEquals(emptyList(), visibleSelection(setOf("x"), order), "ticks on jobs a filter hides are not acted on")
        assertEquals(emptyList(), visibleSelection(emptySet(), order))
    }
}
