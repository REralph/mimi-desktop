package com.example.a2desktop.logic

/*
 * Ticking jobs on the Jobs page, as plain data so it can be tested without a screen: a tick box beside each job's number, a select-all box in
 * the table header, and click-and-drag down (or up) the boxes to tick or untick a run of them at once.
 */

/** How the select-all box in a table header looks: none of the shown jobs ticked, some, or all. */
enum class Ticks { NONE, SOME, ALL }

/** The state of the select-all box for the jobs [shown] (an empty table is NONE). */
fun ticksOf(shown: List<String>, selected: Set<String>): Ticks {
    val n = shown.count { it in selected }
    return when {
        n == 0 -> Ticks.NONE
        n == shown.size -> Ticks.ALL
        else -> Ticks.SOME
    }
}

/** Pressing the select-all box: unticks every shown job if they are all ticked, otherwise ticks them all. Jobs not shown keep what they had. */
fun toggleAll(shown: List<String>, selected: Set<String>): Set<String> =
    if (ticksOf(shown, selected) == Ticks.ALL) selected - shown.toSet() else selected + shown

/**
 * A press on one box, dragged to another: every job from [from] to [to] (positions in [order], either direction — dragging up works as well as
 * down) is set to [tick], and every other job keeps what it had in [base]. [base] is the selection as it was when the press began, so dragging
 * back up the way you came gives those boxes back as they were.
 */
fun selectRange(base: Set<String>, order: List<String>, from: Int, to: Int, tick: Boolean): Set<String> {
    if (order.isEmpty()) return base
    val a = minOf(from, to).coerceIn(0, order.lastIndex)
    val b = maxOf(from, to).coerceIn(0, order.lastIndex)
    val span = order.subList(a, b + 1)
    return if (tick) base + span else base - span.toSet()
}

/** Which job a pointer at height [y] is over, given where each shown job starts (top) — above the first is the first, below the last is the last. */
fun rowIndexAt(tops: List<Float?>, y: Float): Int {
    if (tops.isEmpty()) return 0
    val index = tops.indexOfLast { it != null && it <= y }
    return index.coerceAtLeast(0)
}

/** The ticked jobs that are actually on screen (a filter can hide ticked ones; they must never be changed by an action the person can't see). */
fun visibleSelection(selected: Set<String>, visible: List<String>): List<String> = visible.filter { it in selected }
