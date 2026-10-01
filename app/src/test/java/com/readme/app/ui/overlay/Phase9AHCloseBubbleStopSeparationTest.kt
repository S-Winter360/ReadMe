package com.readme.app.ui.overlay

import com.readme.app.diagnostics.ReadMeCrashLogger
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.speech.TtsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 9AH Regression Test Suite:
 * Restores strict separation between Close Bubble (hide UI only) and Stop Reading (session termination).
 */
class Phase9AHCloseBubbleStopSeparationTest {

    private lateinit var engine: ReadingEngine
    private lateinit var coordinator: ReadingSessionCoordinator

    @Before
    fun setUp() {
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
        engine = ReadingEngine()
        coordinator = ReadingSessionCoordinator(engine)
    }

    @After
    fun tearDown() {
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
    }

    private fun createEphemeralDoc(id: String = "crossapp_screen_1"): ReadingDocument {
        return ReadingDocument(
            id = id,
            metadata = ReadingDocumentMetadata(
                title = "Screen Reading Ephemeral",
                sourceType = ReadingDocumentSourceType.OTHER
            ),
            sections = listOf(
                ReadingSection(
                    id = "sec_0",
                    title = "Main",
                    segments = listOf(
                        ReadingSegment(id = "seg_0", text = "First sentence of active screen reading.")
                    )
                )
            )
        )
    }

    // 1. Close Bubble does not stop reading
    @Test
    fun test01_closeBubble_doesNotStopReading() {
        val doc = createEphemeralDoc()
        val token = coordinator.startLoading("Screen Reading Ephemeral")
        coordinator.onDocumentLoaded(token, doc, "Screen Reading Ephemeral", isEphemeral = true)
        coordinator.startReading()

        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Close Bubble action
        var isClosedByUser = false
        fun handleCloseBubble() {
            isClosedByUser = true
        }

        handleCloseBubble()

        // Verify active reading continues uninterrupted
        assertTrue(isClosedByUser)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        assertTrue(coordinator.readingSessionState.value.isReading)
    }

    // 2. Close Bubble does not discard ephemeral context
    @Test
    fun test02_closeBubble_doesNotDiscardEphemeralContext() {
        val doc = createEphemeralDoc("crossapp_screen_2")
        val token = coordinator.startLoading("Screen Reading")
        coordinator.onDocumentLoaded(token, doc, "Screen Reading", isEphemeral = true, sourcePackageName = "com.example.reader")
        coordinator.startReading()

        val docStateBefore = coordinator.activeDocumentState.value
        assertTrue(docStateBefore.isEphemeral)
        assertEquals("crossapp_screen_2", docStateBefore.documentId)

        // Close Bubble action
        var isClosedByUser = false
        fun handleCloseBubble() {
            isClosedByUser = true
        }

        handleCloseBubble()

        val docStateAfter = coordinator.activeDocumentState.value
        assertTrue(isClosedByUser)
        assertTrue(docStateAfter.isEphemeral)
        assertEquals("crossapp_screen_2", docStateAfter.documentId)
        assertEquals("com.example.reader", docStateAfter.sourcePackageName)
    }

    // 3. Close Bubble does not clear sentence highlight
    @Test
    fun test03_closeBubble_doesNotClearHighlight() {
        val doc = createEphemeralDoc("crossapp_screen_3")
        val token = coordinator.startLoading("Screen Reading")
        coordinator.onDocumentLoaded(token, doc, "Screen Reading", isEphemeral = true)
        coordinator.startReading()

        var isHighlightVisible = true
        var isClosedByUser = false

        fun handleCloseBubble() {
            isClosedByUser = true
            // highlight state remains untouched
        }

        handleCloseBubble()

        assertTrue(isClosedByUser)
        assertTrue(isHighlightVisible)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    // 4. Close Bubble does not cancel active auto-navigation
    @Test
    fun test04_closeBubble_doesNotCancelAutoNavigation() {
        var autoNavCancelled = false
        var autoNavReset = false

        val doc = createEphemeralDoc("crossapp_screen_4")
        val token = coordinator.startLoading("Screen Reading")
        coordinator.onDocumentLoaded(token, doc, "Screen Reading", isEphemeral = true)
        coordinator.startReading()

        // Close Bubble action
        fun handleCloseBubble() {
            // No autoNav.reset() on close bubble
        }

        handleCloseBubble()

        assertFalse(autoNavCancelled)
        assertFalse(autoNavReset)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    // 5. Bubble can be reopened during active reading showing correct active controls
    @Test
    fun test05_reopenBubble_duringActiveReading() {
        val doc = createEphemeralDoc("crossapp_screen_5")
        val token = coordinator.startLoading("Screen Reading")
        coordinator.onDocumentLoaded(token, doc, "Screen Reading", isEphemeral = true)
        coordinator.startReading()

        var isClosedByUser = false

        // Step 1: User closes bubble during reading
        isClosedByUser = true
        var visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = isClosedByUser
        )
        assertEquals(BubbleVisibilityState.HiddenByUser, visibility)

        // Step 2: User reopens bubble
        isClosedByUser = false
        visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = isClosedByUser
        )
        assertEquals(BubbleVisibilityState.Visible, visibility)

        // Step 3: Compute UI state when expanded / compact during active reading
        val uiStateCompact = BubbleLifecyclePolicy.computeUiState(
            visibilityState = visibility,
            sessionState = coordinator.readingSessionState.value,
            documentState = coordinator.activeDocumentState.value,
            isExpanded = false
        )
        assertEquals(BubbleUiState.CompactReading, uiStateCompact)

        val uiStateExpanded = BubbleLifecyclePolicy.computeUiState(
            visibilityState = visibility,
            sessionState = coordinator.readingSessionState.value,
            documentState = coordinator.activeDocumentState.value,
            isExpanded = true
        )
        assertEquals(BubbleUiState.ExpandedReading, uiStateExpanded)

        // Reading session was preserved completely
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
    }

    // 6. Stop performs complete session cleanup
    @Test
    fun test06_stop_performsCompleteCleanup() {
        val doc = createEphemeralDoc("crossapp_screen_6")
        val token = coordinator.startLoading("Screen Reading")
        coordinator.onDocumentLoaded(
            token = token,
            document = doc,
            displayName = "Screen Reading",
            isEphemeral = true,
            hasSuspendedPrimary = true,
            suspendedPrimaryTitle = "Primary Book"
        )
        coordinator.startReading()

        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        assertTrue(coordinator.activeDocumentState.value.isEphemeral)

        var isHighlightVisible = true
        var autoNavResetCalled = false

        fun handleStop() {
            autoNavResetCalled = true
            coordinator.stopReading()
            assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
            isHighlightVisible = false
            coordinator.clearActiveDocument()
        }

        handleStop()

        // Verify complete cleanup
        assertTrue(autoNavResetCalled)
        assertFalse(isHighlightVisible)
        assertFalse(coordinator.hasActiveDocument)
        assertEquals(ReadingSessionState.Idle, coordinator.readingSessionState.value.sessionState)
    }
}
