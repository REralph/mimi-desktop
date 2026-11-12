package com.example.a2desktop

import com.example.a2desktop.ui.NEON_BRIGHTNESS
import com.example.a2desktop.ui.SHADED_EMOJI
import kotlin.test.Test
import kotlin.test.assertEquals

/** The two brightness settings the user tunes by small steps on the Calendar, pinned to where they were last set. */
class BrightnessSettingsTest {
    @Test fun theEmojiStillToComeAreFivePercentBrighterThanTheyWere() {
        assertEquals(0.32f * 1.05f, SHADED_EMOJI, 1e-6f, "0.32 raised by 5%")
        assertEquals(0.336f, SHADED_EMOJI, 1e-6f)
    }

    @Test fun theNeonOnTodayIsTenPercentDimmerThanWhenItWasHalf() {
        assertEquals(0.5f * 0.9f, NEON_BRIGHTNESS, 1e-6f, "half, then 10% lower")
        assertEquals(0.45f, NEON_BRIGHTNESS, 1e-6f)
    }
}
