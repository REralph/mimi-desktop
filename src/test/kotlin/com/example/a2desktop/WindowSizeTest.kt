package com.example.a2desktop

import androidx.compose.ui.unit.dp
import com.example.a2desktop.ui.LEFT_COLUMN_DP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The size the window opens at, and the room the Calendar's three columns leave the month grid inside it. */
class WindowSizeTest {
    @Test fun theWindowAlwaysOpensAt1360By860() {
        assertEquals(1360, WINDOW_WIDTH_DP)
        assertEquals(860, WINDOW_HEIGHT_DP)
        assertEquals(1360.dp, openingWindowSize().width)
        assertEquals(860.dp, openingWindowSize().height)
    }

    @Test fun theLeftColumnIsNarrowSoTheMonthGridHasTheRoom() {
        assertEquals(300, LEFT_COLUMN_DP, "the Skin care and Supplements column was 360")
        // At the opening size: the page has 24 margins, the left column, the Habits/Tasks column (410), 24 gaps between the three; the rest is the grid.
        val gridWidth = WINDOW_WIDTH_DP - 24 - LEFT_COLUMN_DP - 24 - 24 - 410 - 24
        val cell = (gridWidth - 6 * 6) / 7f
        assertEquals(554, gridWidth)
        assertTrue(cell > 73f, "the day cells are about 74 wide (they were 65): $cell")
    }
}
