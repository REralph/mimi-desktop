import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.link.LinkSnapshot
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The desktop must show exactly what the phone shows. The phone project's HabitParityGoldenTest runs
 * the phone's REAL habit code (repository, stats tracker, Information lines, Calendar dots) over
 * several awkward data sets and writes down what the phone displays, together with the snapshot the
 * phone would send for that data. This test loads each snapshot into the desktop's store and requires
 * the same Information lines and the same Calendar dots, day by day.
 *
 * It reads the file the phone's unit tests write (`gradlew testDebugUnitTest` in the phone project,
 * which install/verify steps already run), so a stale file can only ever mean "the phone's logic
 * changed and nobody re-ran its tests" — re-run them and this test follows.
 */
class ParityTest {
    @Serializable
    data class Scenario(
        val name: String,
        val todayEpochDay: Long,
        val snapshot: LinkSnapshot,
        val infoLines: List<String>,
        val dots: Map<Long, List<Long>>,
        val dotsFrom: Long,
        val dotsTo: Long,
    )

    @Serializable
    data class ParityFile(val scenarios: List<Scenario>)

    @Test fun theDesktopShowsEveryHabitLineAndCalendarDotTheSamePhoneDoes() {
        val file = File("../../AndroidStudioProjects/a2/app/build/parity/habit-parity.json")
        assertTrue(
            file.exists(),
            "Missing ${file.path}: run the phone project's unit tests first (gradlew testDebugUnitTest in AndroidStudioProjects/a2).",
        )
        val scenarios = Json { ignoreUnknownKeys = true }.decodeFromString<ParityFile>(file.readText()).scenarios
        assertTrue(scenarios.size >= 5, "the phone wrote only ${scenarios.size} scenarios")

        val problems = mutableListOf<String>()
        for (scenario in scenarios) {
            val store = DesktopStore(LocalDate.ofEpochDay(scenario.todayEpochDay))
            store.applySnapshot(scenario.snapshot)

            val actualLines = store.infoLines().map { it.text }
            if (actualLines != scenario.infoLines) {
                problems += "[${scenario.name}] Information lines differ:\n   phone  : ${scenario.infoLines}\n   desktop: $actualLines"
            }

            var wrongDays = 0
            for (epochDay in scenario.dotsFrom..scenario.dotsTo) {
                val date = LocalDate.ofEpochDay(epochDay)
                val actual = store.habits.filter { it.linkedToCalendar && it.id in store.habitsOn(date) }.map { it.id }
                val expected = scenario.dots[epochDay].orEmpty()
                if (actual != expected) {
                    if (wrongDays++ < 3) problems += "[${scenario.name}] dots on $date: phone $expected, desktop $actual"
                }
            }
            if (wrongDays > 3) problems += "[${scenario.name}] … and ${wrongDays - 3} more days with different dots"
        }
        assertTrue(problems.isEmpty(), "The desktop disagrees with the phone:\n" + problems.joinToString("\n"))
    }
}
