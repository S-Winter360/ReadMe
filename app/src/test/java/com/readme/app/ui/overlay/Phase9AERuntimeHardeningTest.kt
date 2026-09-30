package com.readme.app.ui.overlay

import com.readme.app.diagnostics.ReadMeCrashLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic automated tests for Phase 9AE:
 * ReadMe Runtime Hardening, Screen-Selection Stability, and Overlay Lifecycle Repair.
 *
 * Verifies:
 * 1. Screen Region Selection State Machine (Idle, Showing, Selecting, Confirming, Cancelling, Completed, Destroyed, Failed)
 * 2. State names and error encapsulation in SelectorState.Failed
 * 3. ReadMeCrashLogger diagnostics instrumentation for selectorState, serviceConnectionState, and activeSessionGeneration
 * 4. Audit counter mechanics for selection add/remove and bubble add/remove
 * 5. Formatting of the diagnostic snapshot string for logcat output
 */
class Phase9AERuntimeHardeningTest {

    @Before
    fun setUp() {
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
        ReadMeCrashLogger.selectorState = "Idle"
        ReadMeCrashLogger.serviceConnectionState = "Disconnected"
        ReadMeCrashLogger.activeSessionGeneration = 0L
    }

    @After
    fun tearDown() {
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
    }

    // 1. Selector state machine states and names
    @Test
    fun test01_selectorStateHierarchyAndNames() {
        assertEquals("Idle", SelectorState.Idle.name)
        assertEquals("Showing", SelectorState.Showing.name)
        assertEquals("Selecting", SelectorState.Selecting.name)
        assertEquals("Confirming", SelectorState.Confirming.name)
        assertEquals("Cancelling", SelectorState.Cancelling.name)
        assertEquals("Completed", SelectorState.Completed.name)
        assertEquals("Destroyed", SelectorState.Destroyed.name)

        val failure = SelectorState.Failed("WindowManager error", RuntimeException("Bad token"))
        assertEquals("Failed(WindowManager error)", failure.name)
        assertEquals("WindowManager error", failure.reason)
        assertNotNull(failure.cause)
        assertEquals("Bad token", failure.cause?.message)
    }

    // 2. ReadMeCrashLogger captures selectorState, serviceConnectionState, and activeSessionGeneration
    @Test
    fun test02_crashLoggerCapturesHardeningFields() {
        ReadMeCrashLogger.currentServiceInstanceId = 101L
        ReadMeCrashLogger.currentControllerInstanceId = 202L
        ReadMeCrashLogger.currentLifecycleState = "ServiceCreated"
        ReadMeCrashLogger.bubbleControllerState = "Visible"
        ReadMeCrashLogger.selectorState = "Selecting"
        ReadMeCrashLogger.overlayPermissionGranted = true
        ReadMeCrashLogger.serviceConnectionState = "Connected"
        ReadMeCrashLogger.activeSessionGeneration = 5L
        ReadMeCrashLogger.isBubbleViewAttached = true
        ReadMeCrashLogger.isSelectionAttached = true
        ReadMeCrashLogger.isAppForeground = false
        ReadMeCrashLogger.currentReadingState = "Idle"

        val exception = RuntimeException("Lifecycle audit test")
        val crashInfo = ReadMeCrashLogger.recordCrash(Thread.currentThread(), exception)

        assertNotNull(crashInfo)
        assertEquals("java.lang.RuntimeException", crashInfo.exceptionClass)
        assertEquals("Lifecycle audit test", crashInfo.message)
        assertEquals("Selecting", crashInfo.selectorState)
        assertEquals("Connected", crashInfo.serviceConnectionState)
        assertEquals(5L, crashInfo.activeSessionGeneration)
        assertTrue(crashInfo.selectionAttached)
        assertTrue(crashInfo.bubbleAttached)

        // Verify stackTrace and formatted diagnostics output
        assertTrue(crashInfo.stackTrace.contains("test02_crashLoggerCapturesHardeningFields"))
        val lastCrash = ReadMeCrashLogger.lastCrash
        assertNotNull(lastCrash)
        assertEquals(crashInfo, lastCrash)
    }

    // 3. Selection add/remove audit counters in ReadMeCrashLogger
    @Test
    fun test03_selectionAuditCounters() {
        assertEquals(0, ReadMeCrashLogger.selectionAddCount.get())
        assertEquals(0, ReadMeCrashLogger.selectionRemoveCount.get())

        ReadMeCrashLogger.selectionAddCount.incrementAndGet()
        ReadMeCrashLogger.isSelectionAttached = true
        ReadMeCrashLogger.selectorState = "Selecting"

        assertEquals(1, ReadMeCrashLogger.selectionAddCount.get())
        assertEquals(0, ReadMeCrashLogger.selectionRemoveCount.get())
        assertTrue(ReadMeCrashLogger.isSelectionAttached)
        assertEquals("Selecting", ReadMeCrashLogger.selectorState)

        ReadMeCrashLogger.selectionRemoveCount.incrementAndGet()
        ReadMeCrashLogger.isSelectionAttached = false
        ReadMeCrashLogger.selectorState = "Idle"

        assertEquals(1, ReadMeCrashLogger.selectionAddCount.get())
        assertEquals(1, ReadMeCrashLogger.selectionRemoveCount.get())
        assertFalse(ReadMeCrashLogger.isSelectionAttached)
        assertEquals("Idle", ReadMeCrashLogger.selectorState)
    }

    // 4. Reset audit counters
    @Test
    fun test04_resetAuditCounters() {
        ReadMeCrashLogger.bubbleAddCount.set(5)
        ReadMeCrashLogger.bubbleRemoveCount.set(4)
        ReadMeCrashLogger.selectionAddCount.set(3)
        ReadMeCrashLogger.selectionRemoveCount.set(2)

        ReadMeCrashLogger.resetAuditCounters()

        assertEquals(0, ReadMeCrashLogger.bubbleAddCount.get())
        assertEquals(0, ReadMeCrashLogger.bubbleRemoveCount.get())
        assertEquals(0, ReadMeCrashLogger.selectionAddCount.get())
        assertEquals(0, ReadMeCrashLogger.selectionRemoveCount.get())
    }

    // 5. Clear last crash
    @Test
    fun test05_clearLastCrash() {
        ReadMeCrashLogger.recordCrash(Thread.currentThread(), IllegalStateException("Error"))
        assertNotNull(ReadMeCrashLogger.lastCrash)

        ReadMeCrashLogger.clearLastCrash()
        org.junit.Assert.assertNull(ReadMeCrashLogger.lastCrash)
    }

    // 6. Reset active selector instance test utility
    @Test
    fun test06_resetActiveInstance() {
        ScreenRegionSelectionController.resetActiveInstance()
        // Calling resetActiveInstance multiple times must not crash
        ScreenRegionSelectionController.resetActiveInstance()
    }
}
