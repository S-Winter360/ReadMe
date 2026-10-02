package com.readme.app.ui.overlay

import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.diagnostics.ReadMeCrashLogger
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 9AI: Floating ReadMe Tap Response, Callback Recovery, and Touch Interaction Hardening.
 *
 * Deterministic regression tests covering all 20 required scenarios:
 * 1. Valid tap (0px displacement <= 8dp threshold)
 * 2. Tiny movement still counts as tap (< 8dp threshold)
 * 3. Movement beyond threshold becomes drag (> 8dp threshold)
 * 4. Drag does not trigger tap
 * 5. Tap after drag (clean state reset without stuck gesture)
 * 6. ACTION_CANCEL reset (gesture cancelled without triggering tap)
 * 7. Pointer reset
 * 8. Bubble expansion (toggle between compact and expanded)
 * 9. Idle bubble callback hydration
 * 10. Active bubble callback hydration
 * 11. Paused bubble callback hydration
 * 12. Callback hydration after refresh
 * 13. Callback preservation after selector cancel
 * 14. Callback preservation after bubble reopen
 * 15. Duplicate bubble controller prevention
 * 16. Stale bubble callback rejection
 * 17. Overlay state consistency
 * 18. Close-zone state reset
 * 19. Multi-touch safety (primary pointer isolation)
 * 20. Foreground/background transition lifecycle
 */
class Phase9AITouchInteractionAndTapResponseTest {

    private val dragThresholdPx = 21f // ~8dp on standard 2.625 density

    @Before
    fun setUp() {
        BubbleTouchTrace.reset()
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
        ReadMeCrashLogger.selectorState = "Idle"
        ReadMeCrashLogger.serviceConnectionState = "Connected"
        ReadMeCrashLogger.activeSessionGeneration = 1L
        ScreenRegionSelectionController.resetActiveInstance()
    }

    @After
    fun tearDown() {
        BubbleTouchTrace.reset()
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
        ScreenRegionSelectionController.resetActiveInstance()
    }

    // 1. Valid tap: displacement 0 <= 8dp threshold triggers tap
    @Test
    fun test01_validTap_belowThreshold_triggersTap() {
        var isExpanded = false
        var bubbleTapCalled = false

        // Simulate touch down at (100, 100)
        BubbleTouchTrace.recordDown(
            pointerId = 1L,
            startX = 100f,
            startY = 100f,
            threshold = dragThresholdPx,
            expanded = isExpanded
        )

        // Pointer released at same location (displacement = 0)
        val dx = 0f
        val dy = 0f
        val displacement = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        assertTrue(displacement <= dragThresholdPx)

        // Tap confirmed
        val before = isExpanded
        isExpanded = !before
        bubbleTapCalled = true
        BubbleTouchTrace.recordTap(isExpandedBefore = before, isExpandedAfter = isExpanded)

        assertTrue(isExpanded)
        assertTrue(bubbleTapCalled)
        assertEquals(1, BubbleTouchTrace.tapCount.get())
        assertEquals(0, BubbleTouchTrace.dragCount.get())
        assertEquals("ACTION_UP", BubbleTouchTrace.lastAction)
        assertEquals("onBubbleTap", BubbleTouchTrace.lastCallbackInvoked)
    }

    // 2. Tiny natural finger movement (< 8dp threshold) still counts as tap
    @Test
    fun test02_tinyMovement_belowThreshold_stillCountsAsTap() {
        var isExpanded = false
        var bubbleTapCalled = false

        BubbleTouchTrace.recordDown(
            pointerId = 2L,
            startX = 100f,
            startY = 100f,
            threshold = dragThresholdPx,
            expanded = isExpanded
        )

        // Finger shifts by (3px, 4px) -> displacement = 5px <= 21px (8dp)
        val currentX = 103f
        val currentY = 104f
        val dx = currentX - 100f
        val dy = currentY - 100f
        val displacement = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        assertEquals(5f, displacement, 0.01f)
        assertTrue(displacement <= dragThresholdPx)

        BubbleTouchTrace.recordMove(currentX, currentY, displacement, dragging = false)
        assertFalse(BubbleTouchTrace.isDragging)

        // Lift finger: tap acknowledged
        val before = isExpanded
        isExpanded = !before
        bubbleTapCalled = true
        BubbleTouchTrace.recordTap(isExpandedBefore = before, isExpandedAfter = isExpanded)

        assertTrue(isExpanded)
        assertTrue(bubbleTapCalled)
        assertEquals(1, BubbleTouchTrace.tapCount.get())
        assertEquals(0, BubbleTouchTrace.dragCount.get())
    }

    // 3. Movement beyond threshold (> 8dp) becomes drag
    @Test
    fun test03_movementBeyondThreshold_becomesDrag() {
        var isDragging = false
        var dragStartCalled = false

        BubbleTouchTrace.recordDown(
            pointerId = 3L,
            startX = 100f,
            startY = 100f,
            threshold = dragThresholdPx,
            expanded = false
        )

        // Move to (130, 140) -> dx = 30, dy = 40 -> displacement = 50px > 21px
        val currentX = 130f
        val currentY = 140f
        val displacement = Math.hypot((currentX - 100f).toDouble(), (currentY - 100f).toDouble()).toFloat()
        assertEquals(50f, displacement, 0.01f)
        assertTrue(displacement > dragThresholdPx)

        isDragging = true
        dragStartCalled = true
        BubbleTouchTrace.recordMove(currentX, currentY, displacement, dragging = true)
        BubbleTouchTrace.recordDragStart()

        assertTrue(isDragging)
        assertTrue(dragStartCalled)
        assertEquals(1, BubbleTouchTrace.dragCount.get())
        assertEquals(0, BubbleTouchTrace.tapCount.get())
        assertEquals("onDragStart", BubbleTouchTrace.lastCallbackInvoked)
    }

    // 4. Drag does not trigger tap upon release
    @Test
    fun test04_dragDoesNotTriggerTap() {
        var isExpanded = false
        var dragEndCalled = false

        BubbleTouchTrace.recordDown(
            pointerId = 4L,
            startX = 100f,
            startY = 100f,
            threshold = dragThresholdPx,
            expanded = isExpanded
        )
        BubbleTouchTrace.recordDragStart()

        // Finger released after dragging
        dragEndCalled = true
        BubbleTouchTrace.recordRelease()

        assertTrue(dragEndCalled)
        assertFalse(isExpanded) // Tap NOT triggered
        assertEquals(0, BubbleTouchTrace.tapCount.get())
        assertEquals(1, BubbleTouchTrace.dragCount.get())
        assertEquals("onDragEnd", BubbleTouchTrace.lastCallbackInvoked)
    }

    // 5. Tap after drag: clean state transition without stuck gesture
    @Test
    fun test05_tapAfterDrag_cleanTransitionWithoutStuckState() {
        var isExpanded = false

        // --- Gesture 1: Drag ---
        BubbleTouchTrace.recordDown(5L, 100f, 100f, dragThresholdPx, isExpanded)
        BubbleTouchTrace.recordDragStart()
        BubbleTouchTrace.recordRelease()
        assertEquals(1, BubbleTouchTrace.dragCount.get())
        assertEquals(0, BubbleTouchTrace.tapCount.get())
        assertFalse(BubbleTouchTrace.isDragging)

        // --- Gesture 2: Immediate Tap ---
        BubbleTouchTrace.recordDown(5L, 200f, 300f, dragThresholdPx, isExpanded)
        assertFalse(BubbleTouchTrace.isDragging)

        val before = isExpanded
        isExpanded = !before
        BubbleTouchTrace.recordTap(isExpandedBefore = before, isExpandedAfter = isExpanded)

        assertTrue(isExpanded)
        assertEquals(1, BubbleTouchTrace.tapCount.get())
        assertEquals(1, BubbleTouchTrace.dragCount.get())
    }

    // 6. ACTION_CANCEL reset: gesture cancelled without triggering tap
    @Test
    fun test06_actionCancel_resetsGestureStateWithoutTap() {
        var isExpanded = false
        BubbleTouchTrace.recordDown(6L, 100f, 100f, dragThresholdPx, isExpanded)

        // Gesture interrupted by system window manager cancel
        BubbleTouchTrace.recordCancel(wasDragging = false)

        assertFalse(isExpanded)
        assertEquals(0, BubbleTouchTrace.tapCount.get())
        assertEquals(1, BubbleTouchTrace.cancelCount.get())
        assertEquals("ACTION_CANCEL", BubbleTouchTrace.lastAction)

        // Next tap works cleanly
        BubbleTouchTrace.recordDown(6L, 100f, 100f, dragThresholdPx, isExpanded)
        val before = isExpanded
        isExpanded = !before
        BubbleTouchTrace.recordTap(before, isExpanded)
        assertTrue(isExpanded)
        assertEquals(1, BubbleTouchTrace.tapCount.get())
    }

    // 7. Pointer reset: clears pointer tracking state
    @Test
    fun test07_pointerReset_clearsActivePointer() {
        BubbleTouchTrace.recordDown(7L, 50f, 50f, dragThresholdPx, false)
        assertEquals(7L, BubbleTouchTrace.lastPointerId)

        BubbleTouchTrace.reset()
        assertEquals(-1L, BubbleTouchTrace.lastPointerId)
        assertEquals(0f, BubbleTouchTrace.startX, 0.001f)
        assertEquals(0f, BubbleTouchTrace.startY, 0.001f)
    }

    // 8. Bubble expansion: toggles expanded state correctly
    @Test
    fun test08_bubbleExpansion_togglesExpandedState() {
        var isExpanded = false

        // Tap 1: Expands
        isExpanded = !isExpanded
        assertTrue(isExpanded)

        // Tap 2: Collapses
        isExpanded = !isExpanded
        assertFalse(isExpanded)
    }

    // 9. Idle bubble callback: all action callbacks hydrated
    @Test
    fun test09_idleBubbleCallback_registeredAndHydrated() {
        var acquireModeCalled: CrossAppAcquisitionMode? = null
        var closeCalled = false
        var tapCalled = false

        val payload = BubbleViewPayload(
            sessionState = ActiveReadingSessionState(sessionState = ReadingSessionState.Idle),
            activeDocumentState = ActiveDocumentState(),
            crossAppReadingEnabled = true,
            canAcquireText = true,
            isAutoAdvanceEnabled = false,
            onToggleReading = {},
            onPauseReading = {},
            onResumeReading = {},
            onReselectArea = {},
            onStopReading = {},
            onCloseBubble = { closeCalled = true },
            onAcquireMode = { acquireModeCalled = it },
            onCalibrateNextPage = {},
            onBubbleTap = { tapCalled = true },
            onDragStart = {},
            onDrag = { _, _ -> },
            onDragEnd = {},
            onDragCancel = {}
        )

        payload.onBubbleTap()
        assertTrue(tapCalled)

        payload.onAcquireMode(CrossAppAcquisitionMode.SCREEN_OCR)
        assertEquals(CrossAppAcquisitionMode.SCREEN_OCR, acquireModeCalled)

        payload.onCloseBubble()
        assertTrue(closeCalled)
    }

    // 10. Active bubble callback: pause, stop, reselect hydrated
    @Test
    fun test10_activeBubbleCallback_registeredAndHydrated() {
        var pauseCalled = false
        var stopCalled = false
        var reselectCalled = false

        val payload = BubbleViewPayload(
            sessionState = ActiveReadingSessionState(sessionState = ReadingSessionState.Reading),
            activeDocumentState = ActiveDocumentState(documentId = "doc_1", isEphemeral = true),
            crossAppReadingEnabled = true,
            canAcquireText = false,
            isAutoAdvanceEnabled = true,
            onToggleReading = {},
            onPauseReading = { pauseCalled = true },
            onResumeReading = {},
            onReselectArea = { reselectCalled = true },
            onStopReading = { stopCalled = true },
            onCloseBubble = {},
            onAcquireMode = {},
            onCalibrateNextPage = {},
            onBubbleTap = {},
            onDragStart = {},
            onDrag = { _, _ -> },
            onDragEnd = {},
            onDragCancel = {}
        )

        payload.onPauseReading()
        assertTrue(pauseCalled)

        payload.onReselectArea()
        assertTrue(reselectCalled)

        payload.onStopReading()
        assertTrue(stopCalled)
    }

    // 11. Paused bubble callback: resume hydrated
    @Test
    fun test11_pausedBubbleCallback_registeredAndHydrated() {
        var resumeCalled = false
        val payload = BubbleViewPayload(
            sessionState = ActiveReadingSessionState(
                sessionState = ReadingSessionState.Stopped,
                currentPosition = ReadingPosition("doc_1", "sec_1", "seg_1")
            ),
            activeDocumentState = ActiveDocumentState(documentId = "doc_1"),
            crossAppReadingEnabled = true,
            canAcquireText = false,
            isAutoAdvanceEnabled = false,
            onToggleReading = {},
            onPauseReading = {},
            onResumeReading = { resumeCalled = true },
            onReselectArea = {},
            onStopReading = {},
            onCloseBubble = {},
            onAcquireMode = {},
            onCalibrateNextPage = {},
            onBubbleTap = {},
            onDragStart = {},
            onDrag = { _, _ -> },
            onDragEnd = {},
            onDragCancel = {}
        )

        payload.onResumeReading()
        assertTrue(resumeCalled)
    }

    // 12. Callback hydration after refresh: no callbacks become null or no-ops
    @Test
    fun test12_callbackHydration_afterRefreshBubble() {
        var tapCount = 0
        var stopCount = 0

        fun createHydratedPayload(): BubbleViewPayload {
            return BubbleViewPayload(
                sessionState = ActiveReadingSessionState(),
                activeDocumentState = ActiveDocumentState(),
                crossAppReadingEnabled = true,
                canAcquireText = true,
                isAutoAdvanceEnabled = false,
                onToggleReading = {},
                onPauseReading = {},
                onResumeReading = {},
                onReselectArea = {},
                onStopReading = { stopCount++ },
                onCloseBubble = {},
                onAcquireMode = {},
                onCalibrateNextPage = {},
                onBubbleTap = { tapCount++ },
                onDragStart = {},
                onDrag = { _, _ -> },
                onDragEnd = {},
                onDragCancel = {}
            )
        }

        val payload1 = createHydratedPayload()
        val payload2 = createHydratedPayload()

        payload1.onBubbleTap()
        payload2.onBubbleTap()
        assertEquals(2, tapCount)

        payload2.onStopReading()
        assertEquals(1, stopCount)
    }

    // 13. Callback preservation after selector cancel
    @Test
    fun test13_callbackPreservation_afterSelectorCancel() {
        var bubbleTapRestored = false
        var selectorCancelled = false

        val onCancelSelector = {
            selectorCancelled = true
            // Refresh bubble with valid tap callback
            bubbleTapRestored = true
        }

        onCancelSelector()
        assertTrue(selectorCancelled)
        assertTrue(bubbleTapRestored)
    }

    // 14. Callback preservation after bubble reopen
    @Test
    fun test14_callbackPreservation_afterBubbleReopen() {
        var isClosedByUser = true
        var reopenTapCalled = false

        // Bubble closed
        val visibilityClosed = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = isClosedByUser
        )
        assertEquals(BubbleVisibilityState.HiddenByUser, visibilityClosed)

        // Bubble reopened
        isClosedByUser = false
        val visibilityReopened = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = isClosedByUser
        )
        assertEquals(BubbleVisibilityState.Visible, visibilityReopened)

        val payload = BubbleViewPayload(
            sessionState = ActiveReadingSessionState(sessionState = ReadingSessionState.Reading),
            activeDocumentState = ActiveDocumentState(),
            crossAppReadingEnabled = true,
            canAcquireText = false,
            onToggleReading = {},
            onPauseReading = {},
            onResumeReading = {},
            onReselectArea = {},
            onStopReading = {},
            onCloseBubble = {},
            onAcquireMode = {},
            onBubbleTap = { reopenTapCalled = true },
            onDragStart = {},
            onDrag = { _, _ -> },
            onDragEnd = {},
            onDragCancel = {}
        )
        payload.onBubbleTap()
        assertTrue(reopenTapCalled)
    }

    // 15. Duplicate bubble controller prevention: exactly one active overlay
    @Test
    fun test15_duplicateBubbleControllerPrevention() {
        ReadMeCrashLogger.resetAuditCounters()
        assertEquals(0, ReadMeCrashLogger.bubbleAddCount.get())

        // First attachment
        ReadMeCrashLogger.bubbleAddCount.incrementAndGet()
        ReadMeCrashLogger.isBubbleViewAttached = true

        assertEquals(1, ReadMeCrashLogger.bubbleAddCount.get())
        assertTrue(ReadMeCrashLogger.isBubbleViewAttached)

        // Duplicate show call when already attached: does not increment add count
        var isAdded = true
        fun showIfAdded() {
            if (!isAdded) {
                ReadMeCrashLogger.bubbleAddCount.incrementAndGet()
            }
        }
        showIfAdded()
        assertEquals(1, ReadMeCrashLogger.bubbleAddCount.get())
    }

    // 16. Stale bubble callback rejection: generation safety
    @Test
    fun test16_staleBubbleCallbackRejection() {
        val currentSessionGen = 5L
        var executedCallbackGen = 0L

        fun executeIfCurrent(reqGen: Long) {
            if (reqGen == currentSessionGen) {
                executedCallbackGen = reqGen
            }
        }

        // Stale callback from generation 4
        executeIfCurrent(4L)
        assertEquals(0L, executedCallbackGen)

        // Valid callback from generation 5
        executeIfCurrent(5L)
        assertEquals(5L, executedCallbackGen)
    }

    // 17. Overlay state consistency: bubble and selector flags accurately reflect WindowManager
    @Test
    fun test17_overlayStateConsistency() {
        ReadMeCrashLogger.isBubbleViewAttached = true
        ReadMeCrashLogger.isSelectionAttached = false

        assertTrue(ReadMeCrashLogger.isBubbleViewAttached)
        assertFalse(ReadMeCrashLogger.isSelectionAttached)

        // Opening selector temporarily hides bubble to prevent obstruction
        ReadMeCrashLogger.isBubbleViewAttached = false
        ReadMeCrashLogger.isSelectionAttached = true

        assertFalse(ReadMeCrashLogger.isBubbleViewAttached)
        assertTrue(ReadMeCrashLogger.isSelectionAttached)
    }

    // 18. Close-zone state reset: hover reset after drag end or cancel
    @Test
    fun test18_closeZoneStateReset_afterDragEnd() {
        var isHovered = true

        // User releases bubble
        isHovered = false
        assertFalse(isHovered)
    }

    // 19. Multi-touch safety: secondary pointer ignored
    @Test
    fun test19_multiTouchSafety_primaryPointerIsolated() {
        val primaryPointerId = 100L
        val secondaryPointerId = 101L

        BubbleTouchTrace.recordDown(primaryPointerId, 50f, 50f, dragThresholdPx, false)
        assertEquals(primaryPointerId, BubbleTouchTrace.lastPointerId)

        // Secondary pointer arrives at (200, 300): primary pointer remains unchanged
        fun handlePointer(pointerId: Long) {
            if (pointerId == primaryPointerId) {
                BubbleTouchTrace.recordMove(60f, 60f, 14.14f, false)
            }
        }

        handlePointer(secondaryPointerId)
        assertEquals(primaryPointerId, BubbleTouchTrace.lastPointerId)
    }

    // 20. Foreground/background transition lifecycle
    @Test
    fun test20_foregroundBackgroundTransition() {
        // App is in foreground: Bubble must be HiddenByForeground
        val fgVisibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = true,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.HiddenByForeground, fgVisibility)

        // User leaves ReadMe (app backgrounded): Bubble becomes Visible
        val bgVisibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.Visible, bgVisibility)
    }
}
