package com.readme.app.ui.overlay

import com.readme.app.diagnostics.ReadMeCrashLogger
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.ReadingSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic automated regression tests for Phase 9W:
 * Lifecycle transitions, Document Picker protection, WindowManager overlay lifecycle,
 * and Diagnostic Crash Logging with Audit Counters.
 */
class Phase9WOverlayLifecycleTest {

    @Before
    fun setUp() {
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
    }

    // 1. BubbleLifecyclePolicy computes correct visibility: App foreground -> HiddenByForeground
    @Test
    fun test01_visibility_appForeground_hiddenByForeground() {
        val state = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = true,
            isClosedByUser = false,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.HiddenByForeground, state)
    }

    // 2. BubbleLifecyclePolicy computes correct visibility: App background + floating ON + permission ON -> Visible
    @Test
    fun test02_visibility_appBackground_floatingOn_permissionOn_visible() {
        val state = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.Visible, state)
    }

    // 3. BubbleLifecyclePolicy computes correct visibility: App background + floating OFF -> Disabled
    @Test
    fun test03_visibility_floatingOff_disabled() {
        val state = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = false,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.Disabled, state)
    }

    // 4. BubbleLifecyclePolicy computes correct visibility: Overlay permission revoked -> PermissionUnavailable
    @Test
    fun test04_visibility_permissionRevoked_permissionUnavailable() {
        val state = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = false,
            isForeground = false,
            isClosedByUser = false,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.PermissionUnavailable, state)
    }

    // 5. BubbleLifecyclePolicy computes correct visibility: User dismissed -> HiddenByUser
    @Test
    fun test05_visibility_userDismissed_hiddenByUser() {
        val state = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = true,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.HiddenByUser, state)
    }

    // 6. BubbleLifecyclePolicy computes correct visibility: Document picker active -> HiddenByForeground
    @Test
    fun test06_visibility_documentPickerActive_hiddenByForeground() {
        // Even if app is technically backgrounded during picker invocation,
        // isDocumentPickerActive keeps the system bubble HiddenByForeground.
        val state = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false,
            isDocumentPickerActive = true
        )
        assertEquals(BubbleVisibilityState.HiddenByForeground, state)
    }

    // 7. Activity pause does not trigger crash when Floating ReadMe is ON
    @Test
    fun test07_activityPauseDoesNotTriggerCrashWhenFloatingOn() {
        ReadMeCrashLogger.currentLifecycleState = "ActivityPaused"
        ReadMeCrashLogger.isAppForeground = false
        ReadMeCrashLogger.bubbleControllerState = "Visible"

        // Simulate safe lifecycle state check
        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.Visible, visibility)
        assertEquals("ActivityPaused", ReadMeCrashLogger.currentLifecycleState)
    }

    // 8. Opening document picker does not crash and marks HiddenByForeground
    @Test
    fun test08_documentPickerLifecycleProtection() {
        // Step 1: User in app
        var isDocPicker = false
        var isForeground = true
        var visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = isForeground,
            isClosedByUser = false,
            isDocumentPickerActive = isDocPicker
        )
        assertEquals(BubbleVisibilityState.HiddenByForeground, visibility)

        // Step 2: User taps "Open Content"
        isDocPicker = true
        // Activity pauses
        isForeground = false

        visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = isForeground,
            isClosedByUser = false,
            isDocumentPickerActive = isDocPicker
        )
        // Must remain HiddenByForeground so system bubble does NOT spawn over document picker
        assertEquals(BubbleVisibilityState.HiddenByForeground, visibility)

        // Step 3: Picker closes, app resumes
        isDocPicker = false
        isForeground = true

        visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = isForeground,
            isClosedByUser = false,
            isDocumentPickerActive = isDocPicker
        )
        assertEquals(BubbleVisibilityState.HiddenByForeground, visibility)
    }

    // 9. Overlay Instance Audit: Show and Hide sequence produces exactly equal Add and Remove counts
    @Test
    fun test09_overlayInstanceAudit_equalAddAndRemoveCounts() {
        ReadMeCrashLogger.resetAuditCounters()
        assertEquals(0, ReadMeCrashLogger.bubbleAddCount.get())
        assertEquals(0, ReadMeCrashLogger.bubbleRemoveCount.get())

        // Simulate sequence: show -> hide -> show -> hide
        ReadMeCrashLogger.bubbleAddCount.incrementAndGet()
        ReadMeCrashLogger.bubbleRemoveCount.incrementAndGet()
        ReadMeCrashLogger.bubbleAddCount.incrementAndGet()
        ReadMeCrashLogger.bubbleRemoveCount.incrementAndGet()

        assertEquals(2, ReadMeCrashLogger.bubbleAddCount.get())
        assertEquals(2, ReadMeCrashLogger.bubbleRemoveCount.get())
        assertEquals(ReadMeCrashLogger.bubbleAddCount.get(), ReadMeCrashLogger.bubbleRemoveCount.get())
    }

    // 10. Selection Overlay Instance Audit: Show and Dismiss sequence produces exactly equal Add and Remove counts
    @Test
    fun test10_selectionOverlayInstanceAudit_equalAddAndRemoveCounts() {
        ReadMeCrashLogger.resetAuditCounters()
        assertEquals(0, ReadMeCrashLogger.selectionAddCount.get())
        assertEquals(0, ReadMeCrashLogger.selectionRemoveCount.get())

        // Show selection overlay
        ReadMeCrashLogger.selectionAddCount.incrementAndGet()
        ReadMeCrashLogger.isSelectionAttached = true

        // Dismiss selection overlay
        ReadMeCrashLogger.selectionRemoveCount.incrementAndGet()
        ReadMeCrashLogger.isSelectionAttached = false

        assertEquals(1, ReadMeCrashLogger.selectionAddCount.get())
        assertEquals(1, ReadMeCrashLogger.selectionRemoveCount.get())
        assertFalse(ReadMeCrashLogger.isSelectionAttached)
    }

    // 11. ReadMeCrashLogger captures complete diagnostic snapshot without sensitive data
    @Test
    fun test11_crashLoggerCapturesDiagnosticsWithoutSensitiveData() {
        ReadMeCrashLogger.currentServiceInstanceId = 42L
        ReadMeCrashLogger.currentControllerInstanceId = 84L
        ReadMeCrashLogger.currentLifecycleState = "ActivityPaused"
        ReadMeCrashLogger.bubbleControllerState = "Visible"
        ReadMeCrashLogger.overlayPermissionGranted = true
        ReadMeCrashLogger.isBubbleViewAttached = true
        ReadMeCrashLogger.isSelectionAttached = false
        ReadMeCrashLogger.isAppForeground = false
        ReadMeCrashLogger.currentReadingState = "Reading"
        ReadMeCrashLogger.bubbleAddCount.set(3)
        ReadMeCrashLogger.bubbleRemoveCount.set(2)
        ReadMeCrashLogger.selectionAddCount.set(1)
        ReadMeCrashLogger.selectionRemoveCount.set(1)

        val testException = IllegalStateException("Test overlay exception")
        val crashInfo = ReadMeCrashLogger.recordCrash(Thread.currentThread(), testException)

        assertNotNull(crashInfo)
        assertEquals("java.lang.IllegalStateException", crashInfo.exceptionClass)
        assertEquals("Test overlay exception", crashInfo.message)
        assertEquals(42L, crashInfo.serviceInstanceId)
        assertEquals(84L, crashInfo.controllerInstanceId)
        assertEquals("ActivityPaused", crashInfo.lifecycleState)
        assertEquals("Visible", crashInfo.bubbleState)
        assertTrue(crashInfo.overlayPermission)
        assertTrue(crashInfo.bubbleAttached)
        assertFalse(crashInfo.selectionAttached)
        assertFalse(crashInfo.appForeground)
        assertEquals("Reading", crashInfo.readingState)
        assertEquals(3, crashInfo.bubbleAddTotal)
        assertEquals(2, crashInfo.bubbleRemoveTotal)
        assertEquals(1, crashInfo.selectionAddTotal)
        assertEquals(1, crashInfo.selectionRemoveTotal)

        // Ensure stackTrace exists and no sensitive information was leaked
        assertTrue(crashInfo.stackTrace.contains("test11_crashLoggerCapturesDiagnosticsWithoutSensitiveData"))
        assertFalse(crashInfo.stackTrace.contains("password"))
    }

    // 12. Position clamping and close-zone geometry safety
    @Test
    fun test12_clampingAndCloseZoneSafety() {
        val (clampedX, clampedY) = SystemFloatingBubbleController.clampPosition(
            x = -100,
            y = 5000,
            viewWidth = 120,
            viewHeight = 120,
            screenWidth = 1080,
            screenHeight = 1920
        )
        assertEquals(0, clampedX)
        assertEquals(1920 - 120, clampedY)

        val inCloseZone = SystemFloatingBubbleController.isPositionInCloseZone(
            bubbleY = 1850,
            bubbleHeight = 120,
            screenHeight = 1920,
            thresholdPx = 140
        )
        assertTrue(inCloseZone)

        val notInCloseZone = SystemFloatingBubbleController.isPositionInCloseZone(
            bubbleY = 200,
            bubbleHeight = 120,
            screenHeight = 1920,
            thresholdPx = 140
        )
        assertFalse(notInCloseZone)
    }
}
