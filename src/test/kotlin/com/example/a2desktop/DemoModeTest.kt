package com.example.a2desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

class DemoModeTest {
    @Test fun theDemoSwitchIsRecognised() {
        assertTrue(isDemo(arrayOf("--demo")))
        assertTrue(isDemo(arrayOf("--DEMO")), "case doesn't matter")
        assertTrue(isDemo(arrayOf("--other", "--demo")))
        assertFalse(isDemo(emptyArray()), "a normal launch is the real app")
        assertFalse(isDemo(arrayOf("demo")))
    }

    @Test fun theDemoShowsTheSampleDataAndKeepsItsPairingInATemporaryFolder() {
        val scope = CoroutineScope(Job())
        val demo = DemoSetup(scope, Dispatchers.Unconfined)
        try {
            assertNotNull(demo.link.pairing, "the demo starts paired with the pretend phone, so it opens on the Calendar")
            assertEquals("Sample phone", demo.store.deviceName)
            assertTrue(demo.store.habits.isNotEmpty(), "the sample habits arrived from the pretend phone")
            assertTrue(demo.store.sessions.isNotEmpty(), "and the sample workouts")
            assertTrue(demo.store.readOnly, "it behaves like a phone-backed copy")
            assertTrue(demo.jobs.accounts.isNotEmpty(), "the Jobs page has its invented mailbox")

            val temp = File(System.getProperty("java.io.tmpdir")).canonicalFile
            assertTrue(demo.storageDir.canonicalFile.startsWith(temp), "the pretend pairing lives in a temporary folder")
            System.getenv("APPDATA")?.let { appData ->
                assertFalse(demo.storageDir.canonicalFile.startsWith(File(appData, "a2-desktop").canonicalFile), "never the real data folder")
            }
        } finally {
            scope.cancel()
            demo.storageDir.deleteRecursively()
        }
    }
}
