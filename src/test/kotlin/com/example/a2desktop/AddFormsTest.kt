package com.example.a2desktop

import com.example.a2desktop.ui.parseRepeatCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Add event / Add task forms accept in their repeat box. */
class AddFormsTest {
    @Test fun aRepeatCountIsWithinItsRangeOrBlank() {
        assertEquals(0, parseRepeatCount("", 3650))
        assertEquals(14, parseRepeatCount(" 14 ", 3650))
        assertEquals(3650, parseRepeatCount("3650", 3650))
        assertNull(parseRepeatCount("0", 3650))
        assertNull(parseRepeatCount("3651", 3650))
        assertNull(parseRepeatCount("two", 3650))
        assertNull(parseRepeatCount("-3", 3650))
    }
}
