package com.example.a2desktop

import com.example.a2desktop.logic.EMOJI_CATALOG
import com.example.a2desktop.logic.EMOJI_CATEGORIES
import com.example.a2desktop.logic.emojisIn
import com.example.a2desktop.logic.searchEmojis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The emoji the picker lists and how a search finds them. */
class EmojiCatalogTest {
    @Test fun everyEmojiIsDistinctAndReadAsASingleEmoji() {
        assertEquals(EMOJI_CATALOG.size, EMOJI_CATALOG.map { it.emoji }.toSet().size, "no emoji is listed twice")
        EMOJI_CATALOG.forEach { assertEquals(listOf(it.emoji), emojisIn(it.emoji), "${it.emoji} is not one emoji") }
    }

    @Test fun everyEmojiHasANameAndAGroup() {
        EMOJI_CATALOG.forEach { assertTrue(it.name.isNotBlank() && it.category.isNotBlank(), it.emoji) }
        assertEquals(EMOJI_CATALOG.size, EMOJI_CATEGORIES.sumOf { it.second.size }, "the groups hold exactly the catalog")
        assertTrue(EMOJI_CATEGORIES.size in 5..12, "a handful of groups to browse")
    }

    @Test fun theThingsOnYourCalendarAreThere() {
        val all = EMOJI_CATALOG.map { it.emoji }.toSet()
        listOf("✂️", "💇", "🛏️", "🐠", "🚰", "💧", "🦷", "🚗", "🧺").forEach { assertTrue(it in all, "$it is in the picker") }
    }

    @Test fun aBlankSearchFindsNothing() {
        assertTrue(searchEmojis("").isEmpty())
        assertTrue(searchEmojis("   ").isEmpty())
    }

    @Test fun aSearchFindsAnEmojiByItsNameOrAnyOfItsWords() {
        assertEquals("✂️", searchEmojis("haircut").first().emoji)
        assertTrue(searchEmojis("hair").any { it.emoji == "💇" })
        assertTrue(searchEmojis("dentist").any { it.emoji == "🦷" })
        assertTrue(searchEmojis("duvet").any { it.emoji == "🛏️" })
        assertTrue(searchEmojis("AQUARIUM").any { it.emoji == "🐠" }, "capitals don't matter")
    }

    @Test fun everyWordTypedMustFit() {
        assertEquals("✂️", searchEmojis("hair cut").first().emoji, "both words fit the scissors best")
        assertTrue(searchEmojis("hair xylophone").isEmpty())
    }

    @Test fun theStartOfAWordFindsItButTheMiddleOfOneDoesNotAndAWholeWordComesFirst() {
        assertTrue(searchEmojis("dent").any { it.emoji == "🦷" }, "the start of a word")
        assertTrue(searchEmojis("ntis").isEmpty(), "not the middle of one")
        assertTrue(searchEmojis("hair").none { it.emoji == "🪑" }, "a chair is not hair")
        // The laundry basket comes first in the picker and is found by "washing"; soap and the shower have "wash" itself.
        val order = searchEmojis("wash").map { it.emoji }
        assertTrue(order.indexOf("🧼") < order.indexOf("🧺") && order.indexOf("🚿") < order.indexOf("🧺"), "whole-word matches come first: $order")
    }

    @Test fun aWordNothingMatchesFindsNothing() {
        assertTrue(searchEmojis("zzzz").isEmpty())
    }
}
