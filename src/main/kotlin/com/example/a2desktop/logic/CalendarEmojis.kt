package com.example.a2desktop.logic

import com.example.a2desktop.model.UpcomingItem
import java.util.Locale

// The emoji on the Calendar's events and tasks. The phone has no emoji for them, so the desktop keeps its own (by title) and
// also reads the emoji already typed into a title, as in "HairCut, 🐠🚰". Plain functions of plain text, unit-tested.

/** How two titles are recognised as the same thing: trimmed, lower-case, single-spaced ("Haircut", " haircut "). */
fun titleKey(title: String): String = title.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

private fun inRanges(cp: Int, vararg ranges: IntRange): Boolean = ranges.any { cp in it }

/** Whether [cp] begins an emoji. Leaves out the plain-text symbols (© ® ™ ✓ ★ …) that only become emoji with a variation selector. */
private fun startsEmoji(cp: Int): Boolean = inRanges(
    cp,
    0x1F300..0x1FAFF, // pictographs, emoticons, transport, supplemental symbols
    0x1F170..0x1F171, 0x1F17E..0x1F17F, 0x1F18E..0x1F18E, 0x1F191..0x1F19A, 0x1F1E6..0x1F1FF, 0x1F201..0x1F251, 0x1F004..0x1F004, 0x1F0CF..0x1F0CF,
    0x2600..0x2712, 0x2714..0x2716, 0x2718..0x27BF, // misc symbols and dingbats, without the plain ✓ and ✗
    0x2B05..0x2B07, 0x2B1B..0x2B1C, 0x2B50..0x2B50, 0x2B55..0x2B55,
    0x2194..0x2199, 0x21A9..0x21AA, 0x231A..0x231B, 0x23E9..0x23F3, 0x23F8..0x23FA, 0x25FD..0x25FE, 0x2934..0x2935,
    0x3297..0x3297, 0x3299..0x3299,
).let { it && cp != 0x2605 && cp != 0x2606 } // ★ and ☆ are ordinary text; ⭐ is 0x2B50

private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

/**
 * Every emoji in [text], in order. A sequence that is one picture — a flag, a family, an emoji with a skin tone or its
 * variation selector (✂️) — is one entry. "HairCut, 🐠🚰" gives 🐠 and 🚰; "Water change" gives none.
 */
fun emojisIn(text: String): List<String> {
    val found = ArrayList<String>()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (!startsEmoji(cp)) {
            i += Character.charCount(cp)
            continue
        }
        val start = i
        i += Character.charCount(cp)
        if (isRegionalIndicator(cp)) {
            // A flag is two regional indicators.
            if (i < text.length && isRegionalIndicator(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
        } else {
            while (i < text.length) {
                val next = text.codePointAt(i)
                val width = Character.charCount(next)
                if (next == 0xFE0F || next in 0x1F3FB..0x1F3FF) {
                    i += width // variation selector, skin tone
                } else if (next == 0x200D && i + width < text.length && startsEmoji(text.codePointAt(i + width))) {
                    i += width + Character.charCount(text.codePointAt(i + width)) // joined to the next one: a family, a person with a job
                } else {
                    break
                }
            }
        }
        found += text.substring(start, i)
    }
    return found
}

/**
 * The emoji to show for something called [title]: the one chosen for that title ([chosen]), otherwise whatever emoji the
 * title itself already contains (nothing if it has none).
 */
fun displayEmojis(title: String, chosen: String?): List<String> = chosen?.let { listOf(it) } ?: emojisIn(title)

/**
 * The emoji to offer first, most used first: [fromTitles] counts how often each appears in the titles already on the phone,
 * [chosen] how often each was picked on this PC (oldest first, so on a tie the one picked most recently wins). At most [limit].
 */
fun frequentEmojis(fromTitles: Map<String, Int>, chosen: Map<String, Int>, limit: Int = 12): List<String> {
    val recency = chosen.keys.toList() // oldest first
    return (fromTitles.keys + chosen.keys).toSet()
        .sortedWith(
            compareByDescending<String> { (fromTitles[it] ?: 0) + (chosen[it] ?: 0) }
                .thenByDescending { recency.indexOf(it) },
        )
        .take(limit)
}

/** How many times each emoji appears across [titles]. */
fun emojiCounts(titles: List<String>): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    titles.forEach { title -> emojisIn(title).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
    return counts
}

/** What on the Upcoming list already has the title [title] (the same thing whatever the capitals or spacing), soonest first. */
fun upcomingWithTitle(title: String, upcoming: List<UpcomingItem>): List<UpcomingItem> =
    if (titleKey(title).isEmpty()) emptyList() else upcoming.filter { titleKey(it.title) == titleKey(title) }.sortedBy { it.due }
