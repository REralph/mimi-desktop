package com.example.a2desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import com.example.a2desktop.data.DesktopStore
import com.example.a2desktop.data.GmailClientFile
import com.example.a2desktop.data.JobsActions
import com.example.a2desktop.data.JobsStore
import com.example.a2desktop.data.SampleMail
import com.example.a2desktop.data.sampleJobFolders
import com.example.a2desktop.data.sampleJobsStore
import com.example.a2desktop.link.LinkAddress
import com.example.a2desktop.link.LinkController
import com.example.a2desktop.link.LinkResult
import com.example.a2desktop.link.LinkStorage
import com.example.a2desktop.link.SnapshotSource
import com.example.a2desktop.logic.JobStatus
import com.example.a2desktop.theme.A2Theme
import com.example.a2desktop.ui.A2App
import com.example.a2desktop.ui.DevMode
import com.example.a2desktop.ui.Page
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.jetbrains.skia.Bitmap

/**
 * The Jobs page on screen, with the invented jobs and a fake mailbox: it needs no phone, the status menu does what it says, the setup
 * buttons ask the desktop to open the right things, filtering changes what is shown, and clicking about at random breaks nothing.
 * The page is 1360 x 1500 here so everything is on screen; positions are those of the layout previews.
 */
@OptIn(ExperimentalComposeUiApi::class)
class JobsPageUiTest {
    private val width = 1360
    private val height = 1500

    private class Rig(val scene: ImageComposeScene, val store: DesktopStore, val dev: DevMode) {
        var time = 0L
        fun settle(frames: Int = 4) = repeat(frames) { time += 50_000_000; scene.render(time) }
        fun click(x: Float, y: Float, shift: Boolean = false) = dragThrough(listOf(x to y), shift = shift)

        /** Presses at [from], moves to [to] with the button down, and lets go there. */
        fun drag(from: Pair<Float, Float>, to: Pair<Float, Float>, shift: Boolean = false) = dragThrough(listOf(from, to), shift = shift)

        /**
         * Presses at the first point and moves through the others in turn (several small steps between each, as a hand does) with the button
         * down, letting go at the last — so a drag can go down and come back up in one gesture. One point is a plain click.
         */
        fun dragThrough(points: List<Pair<Float, Float>>, steps: Int = 8, shift: Boolean = false) {
            val down = PointerButtons(isPrimaryPressed = true)
            val keys = PointerKeyboardModifiers(isShiftPressed = shift)
            val start = Offset(points.first().first, points.first().second)
            scene.sendPointerEvent(PointerEventType.Move, start, keyboardModifiers = keys)
            scene.sendPointerEvent(PointerEventType.Press, start, buttons = down, keyboardModifiers = keys, button = PointerButton.Primary)
            time += 50_000_000; scene.render(time)
            points.zipWithNext().forEach { (a, b) ->
                for (i in 1..steps) {
                    val p = Offset(a.first + (b.first - a.first) * i / steps, a.second + (b.second - a.second) * i / steps)
                    scene.sendPointerEvent(PointerEventType.Move, p, buttons = down, keyboardModifiers = keys)
                    time += 50_000_000; scene.render(time)
                }
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(points.last().first, points.last().second), keyboardModifiers = keys, button = PointerButton.Primary)
            settle()
        }
        fun picture(): Bitmap { time += 50_000_000; return Bitmap.makeFromImage(scene.render(time)) }
    }

    private fun rig(jobs: JobsStore, store: DesktopStore = DesktopStore(), dev: DevMode = DevMode(), page: Page = Page.JOBS, connect: Boolean = false, pageHeight: Int = height): Rig {
        val link = LinkController(store, LinkStorage(Files.createTempDirectory("a2-jobs-ui").toFile()), object : SnapshotSource {
            override fun fetch(address: LinkAddress, pairingKey: String) = LinkResult.Failed("no phone in tests")
        }, CoroutineScope(Job()), Dispatchers.Unconfined)
        val scene = ImageComposeScene(width = width, height = pageHeight, density = Density(1f)) { A2Theme { A2App(store, link, page, jobs = jobs, dev = dev, initialConnectOpen = connect) } }
        return Rig(scene, store, dev).also { it.settle() }
    }

    private fun JobsStore.statusOf(title: String) = rows.single { it.title.startsWith(title) }

    // The Riverton job is number 1, the first row (the one applied for most recently): its status pill is at (582, 413); the menu then lists No email
    // yet, Applied, Interview, Offer, Accepted, Rejected and Hide at x = 570, y = 461, 509, 557, 605, 653, 701, 749.
    private val chip = 582f to 413f

    @Test fun theJobsPageNeedsNoPhoneAtAll() {
        val dev = DevMode().also { it.enabled = true }
        val r = rig(sampleJobsStore(), store = DesktopStore(), dev = dev) // nothing has ever come from a phone
        try {
            assertTrue("7.1" in r.dev.groups.keys, "the Jobs page itself is shown, not the 'no data from the phone' card: ${r.dev.groups.keys.sorted()}")
            assertTrue("7.1.1" in r.dev.groups.keys && "7.8" in r.dev.groups.keys, "the header's Connect button and the one table of jobs are there")
            assertTrue("7.9" !in r.dev.groups.keys, "there is no second table for jobs found only in email any more: they are in the one list")
        } finally {
            r.scene.close()
        }
    }

    // ---- ticking jobs: the boxes beside the numbers, dragging over them, and the bar that acts on every ticked job ----
    // The sample jobs in the order the page lists them — the one applied for most recently first: 1 Riverton (applied), 2 Warehouse Operative (found only in email,
    // applied), 3 Meadow (interview), 4 Northgate (interview), 5 Lakeside (rejected), 6 Eastbury (rejected), 7 Deskside (no email, no date, so last).
    // The boxes are at x = 55: the select-all box in the table's header at y = 375, then jobs 1 to 7 at y = 409, 458, 511, 564, 629, 702, 755. The bar
    // (PC7.10) floats at the bottom of the page, buttons at y = 1447: No email yet x = 359, Applied 456, Interview 545, Offer 624, Accepted 703, Rejected 794,
    // Use what the emails say 931, Delete 1061, Clear 1132. The status filter chips are at y = 221: Accepted x = 145 ... Rejected x = 686.
    private val headerBox = 55f to 375f
    private val box = listOf(409f, 458f, 511f, 564f, 629f, 702f, 755f).map { 55f to it } // jobs 1 to 7
    private val titles = listOf("IT Support Technician Riverton", "Warehouse Operative", "IT Technician Meadow", "Northgate College", "Lakeside and Hillcrest", "Eastbury and Westbury", "Deskside Support")
    private val noEmailYet = 359f
    private val applied = 456f
    private val offer = 624f
    private val accepted = 703f
    private val rejected = 794f
    private val emailsDecide = 931f
    private val delete = 1061f
    private val clear = 1132f
    // Pressing Delete swaps the bar for a question: "Delete these N jobs from Mimi's list for good? Your folders are not touched." — Yes, delete is at x = 892, Cancel at x = 980.
    private val yesDelete = 892f
    private val cancel = 980f

    private fun withJobs(block: Rig.(JobsStore) -> Unit) {
        val jobs = sampleJobsStore()
        val r = rig(jobs, dev = DevMode().also { it.enabled = true })
        try {
            r.block(jobs)
        } finally {
            r.scene.close()
        }
    }

    private fun Rig.barShowing() = "7.10" in dev.groups.keys
    private fun Rig.pressBar(x: Float) = click(x, 1447f)
    private fun JobsStore.overriddenTitles() = titles.filter { t -> rows.any { it.title.startsWith(t) && it.overridden } }
    private fun JobsStore.shownTitles() = titles.filter { t -> rows.any { it.title.startsWith(t) } }

    /** Whether the tick box centred at [at] is filled (blue-ish) rather than an empty outline: sampled just inside its left edge, left of the tick mark. */
    private fun Bitmap.filledAt(at: Pair<Float, Float>): Boolean {
        val c = getColor(at.first.toInt() - 8, at.second.toInt())
        return (c and 0xFF) > 200 && ((c shr 16) and 0xFF) > 140
    }

    @Test fun pressingABoxTicksTheJobAndPressingItAgainUnticksIt() = withJobs { jobs ->
        assertTrue(!barShowing(), "nothing is ticked at first, so there is no bar")
        assertTrue(!picture().filledAt(box[0]), "the box starts empty")
        click(box[0].first, box[0].second)
        assertTrue(barShowing(), "ticking a job brings up the bar")
        assertTrue(picture().filledAt(box[0]), "and its box is filled")
        assertTrue(!picture().filledAt(box[1]), "the job below is not")
        click(box[0].first, box[0].second)
        assertTrue(!barShowing(), "unticking the last one takes the bar away again")
        assertTrue(!picture().filledAt(box[0]), "and the box is empty")
        assertEquals(emptyList(), jobs.overriddenTitles(), "ticking changes no job by itself")
    }

    @Test fun draggingDownOverTheBoxesTicksEveryRowItCrossesAndOneButtonSetsThemAll() = withJobs { jobs ->
        drag(box[0], box[3])
        assertTrue(barShowing())
        assertTrue(listOf(0, 1, 2, 3).all { picture().filledAt(box[it]) } && listOf(4, 5, 6).none { picture().filledAt(box[it]) }, "jobs 1 to 4 are ticked, 5 to 7 are not")
        pressBar(offer)
        assertEquals(titles.take(4), jobs.overriddenTitles(), "exactly the four dragged over were changed")
        titles.take(4).forEach { assertEquals(JobStatus.OFFER, jobs.statusOf(it).status, it) }
        assertEquals(JobStatus.REJECTED, jobs.statusOf(titles[4]).status, "the others kept their status")
        assertTrue(!barShowing(), "the bar goes once it has done its job")
    }

    @Test fun draggingUpwardsTicksJustAsWell() = withJobs { jobs ->
        drag(box[3], box[1])
        pressBar(delete)
        pressBar(yesDelete)
        assertEquals(listOf(titles[0], titles[4], titles[5], titles[6]), jobs.shownTitles(), "jobs 2, 3 and 4 went")
        assertEquals(0, jobs.hiddenCount, "deleted is not hidden: Show hidden has nothing to bring back")
    }

    @Test fun draggingBackTheWayYouCameGivesTheBoxesBackAsTheyWere() = withJobs { jobs ->
        dragThrough(listOf(box[0], box[4], box[1]))
        assertTrue(listOf(0, 1).all { picture().filledAt(box[it]) } && listOf(2, 3, 4, 5, 6).none { picture().filledAt(box[it]) }, "only 1 and 2 stay ticked")
        pressBar(applied)
        assertEquals(titles.take(2), jobs.overriddenTitles())
    }

    @Test fun aDragThatStartsOnATickedBoxUntickesWhatItCrosses() = withJobs { jobs ->
        drag(box[0], box[3]) // 1 to 4 ticked
        drag(box[3], box[2]) // pressing the ticked 4 and dragging up to 3 unticks both
        pressBar(delete)
        pressBar(yesDelete)
        assertEquals(titles.drop(2), jobs.shownTitles(), "only jobs 1 and 2 were still ticked, so only they went")
    }

    @Test fun theSelectAllBoxTicksEveryJobInTheOneTableEmailOnlyJobsIncluded() = withJobs { jobs ->
        assertTrue(!picture().filledAt(headerBox), "empty while nothing is ticked")
        click(headerBox.first, headerBox.second)
        assertTrue(picture().filledAt(headerBox) && titles.indices.all { picture().filledAt(box[it]) }, "all seven boxes and the header's are filled")
        pressBar(rejected)
        assertEquals(titles, jobs.overriddenTitles(), "every job, the one found only in email (Warehouse Operative) among them")
        assertTrue(jobs.rows.single { it.title.startsWith("Warehouse Operative") }.overridden)
    }

    @Test fun pressingTheSelectAllBoxAgainUntickEverythingAndSomeTicksShowAsADash() = withJobs {
        click(box[1].first, box[1].second)
        assertTrue(picture().filledAt(headerBox), "with some ticked the header box is filled (it shows a dash)")
        click(headerBox.first, headerBox.second)
        assertTrue(titles.indices.all { picture().filledAt(box[it]) }, "pressing it with only some ticked ticks all")
        click(headerBox.first, headerBox.second)
        assertTrue(titles.indices.none { picture().filledAt(box[it]) } && !picture().filledAt(headerBox), "and pressing it with all ticked clears them")
        assertTrue(!barShowing())
    }

    @Test fun shiftPressingAnotherBoxTicksEverythingBetweenWithoutDragging() = withJobs { jobs ->
        click(box[0].first, box[0].second)
        click(box[4].first, box[4].second, shift = true)
        pressBar(applied)
        assertEquals(titles.take(5), jobs.overriddenTitles(), "jobs 1 to 5, and not 6")
    }

    @Test fun shiftPressingWithNoEarlierBoxJustTicksThatOne() = withJobs { jobs ->
        click(box[2].first, box[2].second, shift = true)
        pressBar(applied)
        assertEquals(listOf(titles[2]), jobs.overriddenTitles())
    }

    @Test fun anActionOnlyReachesTheJobsYouCanSeeUnderTheFilter() = withJobs { jobs ->
        drag(box[0], box[3]) // jobs 1 to 4 ticked
        click(686f, 221f) // the Rejected filter: only jobs 5 and 6 are shown now
        assertTrue(!barShowing(), "the four ticked jobs are out of sight, so there is nothing to act on")
        click(headerBox.first, headerBox.second) // ticks the two shown
        pressBar(offer)
        assertEquals(listOf(titles[4], titles[5]), jobs.overriddenTitles(), "only the two on screen changed — the ticked ones hidden by the filter did not")
        click(686f, 221f) // the filter off again
        assertTrue(!barShowing(), "and the unseen ticks were dropped rather than left to catch you out later")
    }

    @Test fun aFolderJobAndAJobFoundOnlyInEmailTickedTogetherAreDeletedTogetherAndTheNumbersCloseUp() = withJobs { jobs ->
        click(box[0].first, box[0].second) // Riverton: a folder job
        click(box[1].first, box[1].second) // Warehouse Operative: found only in email, no folder
        pressBar(delete)
        pressBar(yesDelete)
        assertTrue(jobs.rows.none { it.title.startsWith("Warehouse Operative") || it.title.startsWith("IT Support Technician Riverton") }, "the folder job and the email-only job both went")
        assertEquals(5, jobs.rows.size)
        assertEquals((1..5).map { it.toString() }, jobs.rows.sortedBy { it.order }.map { it.label }, "the numbers close up: 1 to 5")
    }

    @Test fun deleteAsksFirstAndCancelLeavesEverythingAsItWasWithTheFoldersUntouched() = withJobs { jobs ->
        // Real folders on disk, standing in for the person's job folders, which Delete must never touch.
        listOf("Northgate College", "Riverton", "Deskside").forEach { File(jobs.jobsFolder!!, it).also { f -> f.mkdirs(); File(f, "cv.txt").writeText("my cv") } }
        val folders = { jobs.jobsFolder!!.walkTopDown().map { it.relativeTo(jobs.jobsFolder!!).path }.sorted().toList() }
        val before = folders()
        assertTrue(before.size >= 6, "the folders and their files are there to be left alone: $before")
        drag(box[0], box[2])
        pressBar(delete)
        assertEquals(titles, jobs.shownTitles(), "pressing Delete only asks: nothing has gone yet")
        assertTrue(barShowing(), "the question is on the bar")
        pressBar(cancel)
        assertEquals(titles, jobs.shownTitles(), "Cancel: nothing gone")
        assertTrue(barShowing(), "and the three are still ticked")
        pressBar(applied) // the ordinary bar is back and works on the same three
        assertEquals(titles.take(3), jobs.overriddenTitles())
        drag(box[0], box[1])
        pressBar(delete)
        pressBar(yesDelete)
        assertEquals(titles.drop(2), jobs.shownTitles(), "Yes, delete: those two are gone from Mimi's list")
        assertEquals(before, folders(), "and no folder on disk was touched")
        assertTrue(!barShowing())
    }

    @Test fun aQuestionAskedAboutOneSelectionIsNeverAppliedToAnother() = withJobs { jobs ->
        drag(box[0], box[1])
        pressBar(delete) // "Delete these 2 jobs…?"
        click(box[2].first, box[2].second) // a third job is ticked while the question is up
        assertEquals(titles, jobs.shownTitles(), "nothing deleted")
        pressBar(yesDelete) // where "Yes, delete" was: now it is part of the ordinary bar, which must not delete anything
        assertEquals(titles, jobs.shownTitles(), "the old question is forgotten when the selection changes")
    }

    @Test fun clearUntickesEverythingAndChangesNothing() = withJobs { jobs ->
        drag(box[0], box[2])
        pressBar(clear)
        assertTrue(!barShowing() && titles.indices.none { picture().filledAt(box[it]) })
        assertEquals(emptyList(), jobs.overriddenTitles())
        assertEquals(0, jobs.hiddenCount)
    }

    @Test fun useWhatTheEmailsSaySendsTickedJobsBackToTheirAutomaticStatus() = withJobs { jobs ->
        jobs.setStatus(jobs.statusOf(titles[0]).key, JobStatus.OFFER)
        assertTrue(jobs.statusOf(titles[0]).overridden)
        click(box[0].first, box[0].second)
        pressBar(emailsDecide)
        assertTrue(!jobs.statusOf(titles[0]).overridden, "no longer set by hand")
        assertEquals(JobStatus.APPLIED, jobs.statusOf(titles[0]).status, "it says what the emails say again (its application was acknowledged)")
    }

    @Test fun theStatusPillAndTheRestOfTheRowDoNotTick() = withJobs { jobs ->
        click(chip.first, chip.second) // opens the menu
        click(700f, 300f) // a press elsewhere closes it (the menu's scrim)
        click(200f, box[1].second) // the job's title
        click(80f, box[2].second) // the number itself
        assertTrue(!barShowing(), "only the box column ticks")
        assertEquals(emptyList(), jobs.overriddenTitles())
    }

    @Test fun theMouseWheelStillScrollsThePageInTheMiddleOfADrag() {
        val jobs = sampleJobsStore()
        val r = rig(jobs, dev = DevMode().also { it.enabled = true }, pageHeight = 600) // a short window: the page can scroll
        try {
            val held = PointerButtons(isPrimaryPressed = true)
            val start = Offset(box[0].first, box[0].second)
            r.scene.sendPointerEvent(PointerEventType.Move, start)
            r.scene.sendPointerEvent(PointerEventType.Press, start, buttons = held, button = PointerButton.Primary)
            r.settle()
            val before = r.picture()
            r.scene.sendPointerEvent(PointerEventType.Scroll, Offset(700f, 300f), scrollDelta = Offset(0f, 6f), buttons = held)
            r.settle()
            val after = r.picture()
            val changed = (24 until 120).sumOf { x -> (95 until 125).count { y -> before.getColor(x, y) != after.getColor(x, y) } }
            assertTrue(changed > 200, "the page title moved off the top: the wheel scrolled the page while the button was down ($changed pixels changed)")
            r.scene.sendPointerEvent(PointerEventType.Release, start, button = PointerButton.Primary)
            r.settle()
        } finally {
            r.scene.close()
        }
    }

    @Test fun aRightPressOnABoxDoesNotTick() = withJobs {
        val at = Offset(box[0].first, box[0].second)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isSecondaryPressed = true), button = PointerButton.Secondary)
        settle()
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Secondary)
        settle()
        assertTrue(!barShowing())
    }

    @Test fun theStatusMenuSetsAStatusOfYourOwn() {
        val jobs = sampleJobsStore()
        val r = rig(jobs)
        try {
            assertEquals(JobStatus.APPLIED, jobs.statusOf("IT Support Technician Riverton").status)
            r.click(chip.first, chip.second)
            r.click(570f, 605f) // Set to Offer
            val riverton = jobs.statusOf("IT Support Technician Riverton")
            assertEquals(JobStatus.OFFER, riverton.status)
            assertTrue(riverton.overridden)
            assertEquals(JobStatus.APPLIED, riverton.auto, "what the emails say is still known")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theStatusMenuHasAcceptedForAJobYouGotAndOnlyYouCanSetIt() {
        val jobs = sampleJobsStore()
        val r = rig(jobs)
        try {
            r.click(chip.first, chip.second)
            r.click(570f, 653f) // Set to Accepted
            val riverton = jobs.statusOf("IT Support Technician Riverton")
            assertEquals(JobStatus.ACCEPTED, riverton.status)
            assertTrue(riverton.overridden, "it is your choice, shown as set by you")
            assertEquals(JobStatus.APPLIED, riverton.auto, "the emails never say accepted: they still say what they said")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theBarCanMarkSeveralJobsAcceptedAtOnce() = withJobs { jobs ->
        drag(box[0], box[1])
        pressBar(accepted)
        assertEquals(titles.take(2), jobs.overriddenTitles())
        titles.take(2).forEach { assertEquals(JobStatus.ACCEPTED, jobs.statusOf(it).status, it) }
    }

    @Test fun theStatusMenuCanHideAJobAndTheOtherJobsStay() {
        val jobs = sampleJobsStore()
        val r = rig(jobs)
        try {
            val before = jobs.rows.size
            r.click(chip.first, chip.second)
            r.click(570f, 749f) // Hide this job
            assertEquals(before - 1, jobs.rows.size)
            assertEquals(1, jobs.hiddenCount)
            assertTrue(jobs.rows.none { it.title.startsWith("IT Support Technician Riverton") })
        } finally {
            r.scene.close()
        }
    }

    @Test fun aStatusFilterShowsOnlyThoseJobs() {
        val jobs = sampleJobsStore()
        val r = rig(jobs)
        try {
            val all = r.picture()
            r.click(686f, 221f) // the "Rejected" chip
            val filtered = r.picture()
            // The first row was the blue Applied pill; with only rejections shown it is a red Rejected one (less green in its tint).
            val g = { b: Bitmap -> (b.getColor(539, 413) shr 8) and 0xFF }
            assertTrue(g(filtered) < g(all), "the first row's pill went from blue to red (green ${g(all)} -> ${g(filtered)})")
            r.click(686f, 221f) // tapping it again clears the filter
            assertEquals(g(all), g(r.picture()), "and back")
        } finally {
            r.scene.close()
        }
    }

    @Test fun theSetupButtonsAskTheDesktopToOpenTheRightPages() {
        val opened = mutableListOf<String>()
        val appDir = Files.createTempDirectory("a2-jobs-app").toFile()
        val actions = object : JobsActions {
            override fun openUrl(url: String) { opened += url }
            override fun openFolder(folder: File) { opened += "folder:${folder.absolutePath}" }
        }
        val jobs = JobsStore(
            mail = SampleMail(clientReady = false), folders = ::sampleJobFolders, clientFile = GmailClientFile(appDir), actions = actions,
            scope = CoroutineScope(Job() + Dispatchers.Unconfined), jobsFolder = File("jobs"),
        )
        val r = rig(jobs, connect = true) // the setup steps live on the Connect page
        try {
            r.click(1219f, 379f) // Open Google Cloud
            r.click(1219f, 435f) // Open the Gmail API page
            r.click(1219f, 547f) // Open Credentials
            r.click(1219f, 603f) // Open Mimi's folder
            assertEquals(
                listOf("https://console.cloud.google.com/projectcreate", "https://console.cloud.google.com/apis/library/gmail.googleapis.com", "https://console.cloud.google.com/apis/credentials", "folder:${appDir.absolutePath}"),
                opened,
            )
        } finally {
            r.scene.close()
        }
    }

    @Test fun connectIsAskedForOnlyOnceTheClientFileIsInPlace() {
        val noClient = sampleJobsStore(connected = false, clientReady = false)
        assertTrue(!noClient.clientReady)
        val ready = sampleJobsStore(connected = false, clientReady = true)
        assertTrue(ready.clientReady)
        assertTrue(ready.accounts.single().connected.not())
        ready.connect(ready.accounts.single().email)
        assertTrue(ready.accounts.single().connected)
        assertNotNull(ready.accounts.single().lastScanMillis.takeIf { it > 0 })
    }

    private fun green(c: Int) = ((c shr 8) and 0xFF) - ((c shr 16) and 0xFF) // how much greener than red a pixel is

    @Test fun theConnectPageSaysWhetherYouAreConnectedAndToWhichMailbox() {
        val dev = DevMode().also { it.enabled = true }
        val on = rig(sampleJobsStore(), dev = dev, connect = true)
        try {
            assertTrue("8.2" in on.dev.groups.keys && "8.4.1" in on.dev.groups.keys, "the banner and the mailbox card are there: ${on.dev.groups.keys.sorted()}")
            val connected = on.picture()
            assertTrue(green(connected.getColor(47, 247)) > 40, "the banner's dot is green when connected")
        } finally {
            on.scene.close()
        }
        val off = rig(sampleJobsStore(connected = false), connect = true)
        try {
            val notConnected = off.picture()
            assertTrue(green(notConnected.getColor(47, 199)) < 20, "the banner's dot is grey when not connected")
        } finally {
            off.scene.close()
        }
    }

    @Test fun pressingConnectConnectsTheMailboxAndTheBannerShowsIt() {
        val jobs = sampleJobsStore(connected = false)
        val r = rig(jobs, connect = true)
        try {
            assertTrue(!jobs.accounts.single().connected)
            r.click(1256f, 303f) // the mailbox's Connect button
            assertTrue(jobs.accounts.single().connected, "the (fake) sign-in went through")
            assertEquals("robinson8@gmail.com", jobs.accounts.single().email)
            val after = r.picture()
            assertTrue(green(after.getColor(47, 247)) > 40, "the banner turns green")
        } finally {
            r.scene.close()
        }
    }

    @Test fun thereIsNoConnectButtonOfItsOwnInTheTopBar() {
        assertTrue(Page.entries.none { it.label.contains("Connect", ignoreCase = true) }, "connecting lives in the Jobs header, not in the navigation bar: ${Page.entries.map { it.label }}")
    }

    @Test fun theConnectButtonInTheJobsHeaderSaysWhetherYouAreConnectedAndToWhom() {
        val acc = { email: String, on: Boolean -> com.example.a2desktop.data.JobAccount(email, on, 0, 0) }
        assertEquals("Connect Gmail", com.example.a2desktop.ui.connectButtonText(listOf(acc("robinson8@gmail.com", false))))
        assertEquals("Connect Gmail", com.example.a2desktop.ui.connectButtonText(emptyList()))
        assertEquals("Connected · robinson8@gmail.com", com.example.a2desktop.ui.connectButtonText(listOf(acc("robinson8@gmail.com", true))))
        assertEquals("Connected · 2 mailboxes", com.example.a2desktop.ui.connectButtonText(listOf(acc("a@x.com", true), acc("b@x.com", true), acc("c@x.com", false))))
        val on = rig(sampleJobsStore())
        try {
            assertTrue(green(on.picture().getColor(803, 116)) > 40, "a green dot on the button when connected")
        } finally {
            on.scene.close()
        }
        val off = rig(sampleJobsStore(connected = false))
        try {
            assertTrue(green(off.picture().getColor(930, 116)) < 20, "a grey dot when not")
        } finally {
            off.scene.close()
        }
    }

    @Test fun theJobsHeaderConnectButtonOpensTheConnectViewAndBackReturnsToTheJobs() {
        val dev = DevMode().also { it.enabled = true }
        val r = rig(sampleJobsStore(connected = false), dev = dev)
        try {
            assertTrue("7.1" in r.dev.groups.keys && "8.1" !in r.dev.groups.keys)
            r.click(981f, 117f) // the header's "Connect Gmail" button (nothing has been read yet, so there is no status line above)
            assertTrue("8.1" in r.dev.groups.keys && "7.1" !in r.dev.groups.keys, "the Connect view is showing: ${r.dev.groups.keys.sorted()}")
            r.click(1251f, 117f) // ← Back to Jobs
            assertTrue("7.1" in r.dev.groups.keys && "8.1" !in r.dev.groups.keys, "and back on the Jobs table: ${r.dev.groups.keys.sorted()}")
            r.click(981f, 117f)
            assertTrue("8.1" in r.dev.groups.keys)
            r.click(545f, 30f) // the Jobs tab in the top bar goes back to the table too
            assertTrue("7.1" in r.dev.groups.keys && "8.1" !in r.dev.groups.keys, "${r.dev.groups.keys.sorted()}")
        } finally {
            r.scene.close()
        }
    }

    @Test fun clickingAboutAtRandomOnTheJobsPageBreaksNothing() {
        listOf(false, true).flatMap { connect ->
            listOf(sampleJobsStore(), sampleJobsStore(connected = false, clientReady = false), sampleJobsStore(connected = false), JobsStore()).map { connect to it }
        }.forEachIndexed { index, (connect, jobs) ->
            val r = rig(jobs, connect = connect)
            try {
                val rng = Random(900 + index)
                repeat(250) { i ->
                    val at = Offset(rng.nextInt(0, width).toFloat(), rng.nextInt(0, height).toFloat())
                    try {
                        if (i % 7 == 0) {
                            r.scene.sendPointerEvent(PointerEventType.Scroll, at, scrollDelta = Offset(0f, rng.nextInt(-6, 7).toFloat()))
                            r.settle(1)
                        } else r.click(at.x, at.y)
                    } catch (e: Throwable) {
                        throw AssertionError("${if (connect) "connect view" else "jobs table"} store #$index: crashed on action #$i at $at: ${e::class.simpleName}: ${e.message}", e)
                    }
                }
                assertTrue(jobs.rows.size <= 7, "hiding can only take jobs away")
            } finally {
                r.scene.close()
            }
        }
    }
}
