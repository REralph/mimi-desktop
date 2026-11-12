package com.example.a2desktop

import com.example.a2desktop.logic.Slot
import com.example.a2desktop.logic.Supplement
import com.example.a2desktop.logic.VitaminSettings
import com.example.a2desktop.logic.planZinc
import com.example.a2desktop.logic.vitaminEntries
import com.example.a2desktop.ui.supplementRowText
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the Supplements card says on each row: the supplement's name and its time of day, never how many to take. */
class SupplementsCardTextTest {
    private val monday = LocalDate.of(2026, 11, 9) // winter: the vitamin D season; a softgel day; a zinc day

    private fun entries(settings: VitaminSettings = VitaminSettings()) =
        vitaminEntries(monday, monday, settings, trained = true, zinc = planZinc(monday, null, emptySet(), settings), marks = emptyMap())

    @Test fun everyRowIsJustTheSupplementsName() {
        entries().forEach { entry -> assertEquals(entry.supplement.short, supplementRowText(entry)) }
    }

    @Test fun noRowSaysHowManyToTakeWhateverTheSettings() {
        val words = Regex("""(?i)\b(tablets?|capsules?|softgels?|servings?|\d+\s?(mg|g|µg|ug))\b""")
        listOf(VitaminSettings(), VitaminSettings(magnesiumTablets = 3, organCapsules = 6, softgelDays = 1), VitaminSettings(zincEveryDay = true)).forEach { settings ->
            entries(settings).forEach { entry ->
                val text = supplementRowText(entry)
                assertFalse(words.containsMatchIn(text), "“$text” says an amount")
                // The doses themselves are still worked out (the safety check and the tracking use them): only the row leaves them out.
                if (entry.line.taken) assertTrue(entry.line.amount.isNotEmpty(), "${entry.supplement} still has its amount in the data")
            }
        }
    }

    @Test fun theNamesAndTheTimesOfDayAreTheOnesTheCardHadBefore() {
        assertEquals(
            listOf("Vitamin D3 4,000 IU", "Vitamin K2", "Creatine", "Beef organs", "Zinc", "Magnesium + B6", "Omega-3", "L-Theanine + Lemon Balm", "Amino acids"),
            entries().filter { it.state.isOnTheDay }.map { supplementRowText(it) },
            "on a softgel, zinc, training Monday: the 400 IU tablet gives way to the softgel",
        )
        assertEquals(
            listOf(Slot.MORNING, Slot.MORNING, Slot.MORNING, Slot.MORNING, Slot.MIDDAY, Slot.EVENING, Slot.EVENING, Slot.EVENING, Slot.TRAINING),
            entries().filter { it.state.isOnTheDay }.map { it.supplement.slot },
        )
        assertEquals("Vitamin D3", Supplement.VITAMIN_D3.short)
    }
}
