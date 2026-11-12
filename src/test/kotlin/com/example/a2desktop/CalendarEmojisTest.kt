package com.example.a2desktop

import com.example.a2desktop.logic.displayEmojis
import com.example.a2desktop.logic.emojiCounts
import com.example.a2desktop.logic.emojisIn
import com.example.a2desktop.logic.frequentEmojis
import com.example.a2desktop.logic.titleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Finding the emoji already typed into a title, matching titles, and ranking the emoji used most. */
class CalendarEmojisTest {
    @Test fun emojiTypedIntoATitleAreFoundInOrder() {
        // The way the phone's own events are titled: "HairCut, 🐠🚰", "👽".
        assertEquals(listOf("🐠", "🚰"), emojisIn("HairCut, 🐠🚰"))
        assertEquals(listOf("👽"), emojisIn("👽"))
        assertEquals(listOf("🏋️", "🍷"), emojisIn("Gym 🏋️ then 🍷"))
    }

    @Test fun aTitleWithNoEmojiHasNone() {
        listOf("Water change", "Cement | Basil and John", "Payed Karen £20", "SweatX, Spoke to Raj", "Fri @15", "10% Water Change").forEach {
            assertTrue(emojisIn(it).isEmpty(), it)
        }
    }

    @Test fun oneEmojiMadeOfSeveralPartsIsOneEntry() {
        assertEquals(listOf("✂️"), emojisIn("✂️ Haircut"), "with its variation selector")
        assertEquals(listOf("✂"), emojisIn("✂ Haircut"), "without it")
        assertEquals(listOf("🛏️"), emojisIn("🛏️"))
        assertEquals(listOf("👨‍👩‍👧"), emojisIn("Family 👨‍👩‍👧 day"), "a family joined by zero-width joiners")
        assertEquals(listOf("🇬🇧"), emojisIn("🇬🇧"), "a flag is two regional indicators")
        assertEquals(listOf("👍🏽"), emojisIn("👍🏽"), "with a skin tone")
        assertEquals(listOf("🏋️‍♂️"), emojisIn("🏋️‍♂️"), "a person with a job")
    }

    @Test fun plainSymbolsAreNotTakenForEmoji() {
        assertTrue(emojisIn("✓ ★ ☆ © ® ™ ✗ 1 # *").isEmpty())
    }

    @Test fun titlesAreMatchedWhateverTheCapitalsOrSpacing() {
        assertEquals(titleKey("Haircut"), titleKey("  haircut "))
        assertEquals("water change", titleKey("Water   CHANGE"))
        assertEquals("", titleKey("   "))
    }

    @Test fun theChosenEmojiBeatsTheOneInTheTitleAndWithNeitherThereIsNothing() {
        assertEquals(listOf("✂️"), displayEmojis("HairCut, 🐠🚰", "✂️"))
        assertEquals(listOf("🐠", "🚰"), displayEmojis("HairCut, 🐠🚰", null))
        assertTrue(displayEmojis("Haircut", null).isEmpty())
    }

    @Test fun theMostUsedEmojiComeFirstAndTheMostRecentWinsATie() {
        val fromTitles = linkedMapOf("🐠" to 3, "🚰" to 3, "👽" to 1)
        val chosen = linkedMapOf("✂️" to 2, "💧" to 2) // oldest first: 💧 was picked last
        assertEquals(listOf("🐠", "🚰", "💧", "✂️", "👽"), frequentEmojis(fromTitles, chosen))
        assertEquals(listOf("🐠", "🚰"), frequentEmojis(fromTitles, chosen, limit = 2))
        assertTrue(frequentEmojis(emptyMap(), emptyMap()).isEmpty())
    }

    @Test fun anEmojiBothInTitlesAndPickedAddsUp() {
        val ranked = frequentEmojis(mapOf("🐠" to 1, "💧" to 1), mapOf("💧" to 2))
        assertEquals("💧", ranked.first())
    }

    @Test fun countsTheEmojiAcrossTitles() {
        assertEquals(mapOf("🐠" to 2, "🚰" to 2, "👽" to 1), emojiCounts(listOf("HairCut, 🐠🚰", "🐠🚰", "Wedding", "👽")))
    }
}
