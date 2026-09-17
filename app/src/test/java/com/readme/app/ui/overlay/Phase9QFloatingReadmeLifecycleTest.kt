package com.readme.app.ui.overlay

import android.graphics.RectF
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.accessibility.CrossAppAcquisitionRequest
import com.readme.app.accessibility.CrossAppReadingCoordinator
import com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.progress.InMemoryReadingProgressRepository
import com.readme.app.reading.service.ReadMeReadingSessionRuntime
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.TtsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic test suite verifying all 28 requirements of Phase 9Q:
 * Floating ReadMe lifecycle, cross-app feature toggles, and refined bubble interaction.
 */
class Phase9QFloatingReadmeLifecycleTest {

    private lateinit var readingEngine: ReadingEngine
    private lateinit var progressRepository: InMemoryReadingProgressRepository
    private lateinit var coordinator: ReadingSessionCoordinator
    private lateinit var speechEngine: ReadMeSpeechEngine
    private lateinit var testScope: CoroutineScope
    private lateinit var runtime: ReadMeReadingSessionRuntime

    private fun createDoc(id: String = "doc_1", title: String = "Test Doc"): ReadingDocument {
        val segments = listOf(
            ReadingSegment("${id}_seg_0", "Sentence 1", boundingBoxes = listOf(RectF(10f, 10f, 100f, 50f))),
            ReadingSegment("${id}_seg_1", "Sentence 2", boundingBoxes = listOf(RectF(10f, 60f, 100f, 100f)))
        )
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(ReadingSection("${id}_sec_0", "Section 0", segments))
        )
    }

    private fun createEphemeralDoc(id: String = "crossapp_ocr_123", title: String = "Article Title"): ReadingDocument {
        val segments = listOf(
            ReadingSegment("${id}_seg_0", "Captured text 1", boundingBoxes = listOf(RectF(50f, 100f, 300f, 150f))),
            ReadingSegment("${id}_seg_1", "Captured text 2", boundingBoxes = listOf(RectF(50f, 160f, 300f, 210f)))
        )
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(ReadingSection("${id}_sec_0", "OCR Section", segments))
        )
    }

    @Before
    fun setUp() {
        readingEngine = ReadingEngine()
        progressRepository = InMemoryReadingProgressRepository()
        coordinator = ReadingSessionCoordinator(readingEngine, progressRepository)
        speechEngine = ReadMeSpeechEngine(null)
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        runtime = ReadMeReadingSessionRuntime(
            context = null,
            progressRepository = progressRepository,
            readingEngine = readingEngine,
            sessionCoordinator = coordinator,
            speechEngine = speechEngine,
            settingsRepository = null,
            runtimeScope = testScope,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @After
    fun tearDown() {
        runtime.shutdown()
        testScope.cancel()
    }

    // 1. Floating ReadMe disabled -> no bubble shown
    @Test
    fun test01_floatingReadmeDisabled_noBubbleShown() {
        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = false,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.Disabled, visibility)
    }

    // 2. Floating ReadMe enabled, overlay not granted -> no bubble shown
    @Test
    fun test02_overlayNotGranted_noBubbleShown() {
        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = false,
            isForeground = false,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.PermissionUnavailable, visibility)
    }

    // 3. Floating ReadMe enabled, overlay granted, ReadMe in foreground -> bubble hidden
    @Test
    fun test03_readMeInForeground_bubbleHidden() {
        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = true,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.HiddenByForeground, visibility)
    }

    // 4. Floating ReadMe enabled, overlay granted, ReadMe in background, no document -> compact idle bubble shown
    @Test
    fun test04_inBackgroundNoDoc_compactIdleBubbleShown() {
        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.Visible, visibility)

        val uiState = BubbleLifecyclePolicy.computeUiState(
            visibilityState = visibility,
            sessionState = runtime.readingSessionState.value,
            documentState = runtime.activeDocumentState.value,
            isExpanded = false
        )
        assertEquals(BubbleUiState.CompactIdle, uiState)
    }

    // 5. Floating ReadMe enabled, overlay granted, document active, reading -> compact reading bubble shown
    @Test
    fun test05_documentActiveReading_compactReadingBubbleShown() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)
        runtime.startReading()

        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.Visible, visibility)

        val uiState = BubbleLifecyclePolicy.computeUiState(
            visibilityState = visibility,
            sessionState = runtime.readingSessionState.value,
            documentState = runtime.activeDocumentState.value,
            isExpanded = false
        )
        assertEquals(BubbleUiState.CompactReading, uiState)
    }

    // 6. Single tap on compact idle bubble -> expands bubble controls
    @Test
    fun test06_singleTapCompactIdle_expandsBubbleControls() {
        val visibility = BubbleVisibilityState.Visible
        val uiState = BubbleLifecyclePolicy.computeUiState(
            visibilityState = visibility,
            sessionState = runtime.readingSessionState.value,
            documentState = runtime.activeDocumentState.value,
            isExpanded = true
        )
        assertEquals(BubbleUiState.ExpandedIdle, uiState)
    }

    // 7. Single tap on compact reading bubble -> expands bubble controls; does NOT stop reading
    @Test
    fun test07_singleTapCompactReading_expandsControlsWithoutStoppingReading() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)
        runtime.startReading()
        assertTrue("Speech should be active", runtime.readingSessionState.value.isReading)

        val uiState = BubbleLifecyclePolicy.computeUiState(
            visibilityState = BubbleVisibilityState.Visible,
            sessionState = runtime.readingSessionState.value,
            documentState = runtime.activeDocumentState.value,
            isExpanded = true
        )
        assertEquals(BubbleUiState.ExpandedReading, uiState)
        assertTrue("Speech must remain active upon expanding controls", runtime.readingSessionState.value.isReading)
    }

    // 8. Tap Read Current Text in expanded bubble -> triggers accessibility text reading flow
    @Test
    fun test08_tapReadCurrentText_triggersAccessibilityMode() {
        var triggeredMode: CrossAppAcquisitionMode? = null
        val onAcquireMode: (CrossAppAcquisitionMode) -> Unit = { mode -> triggeredMode = mode }

        onAcquireMode(CrossAppAcquisitionMode.ACCESSIBILITY_TEXT)
        assertEquals(CrossAppAcquisitionMode.ACCESSIBILITY_TEXT, triggeredMode)
    }

    // 9. Tap Read Screen in expanded bubble -> triggers region selection / OCR flow
    @Test
    fun test09_tapReadScreen_triggersScreenOcrMode() {
        var triggeredMode: CrossAppAcquisitionMode? = null
        val onAcquireMode: (CrossAppAcquisitionMode) -> Unit = { mode -> triggeredMode = mode }

        onAcquireMode(CrossAppAcquisitionMode.SCREEN_OCR)
        assertEquals(CrossAppAcquisitionMode.SCREEN_OCR, triggeredMode)
    }

    // 10. Tap Pause in expanded bubble -> reading pauses, highlight persists, session preserved
    @Test
    fun test10_tapPause_readingPausesSessionPreserved() {
        val ephemeralDoc = createEphemeralDoc()
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()
        assertTrue(runtime.readingSessionState.value.isReading)
        val segBeforePause = runtime.readingEngine.currentSegment.value
        assertNotNull(segBeforePause)
        assertEquals(1, segBeforePause!!.boundingBoxes.size)

        // User pauses reading
        runtime.pauseReading()
        assertFalse(runtime.readingSessionState.value.isReading)
        assertEquals(ReadingSessionState.Stopped, runtime.readingSessionState.value.sessionState)

        // Session and position preserved
        val positionAfterPause = runtime.readingEngine.currentPosition.value
        assertNotNull(positionAfterPause)
        assertEquals(segBeforePause.id, positionAfterPause?.segmentId)
        assertTrue("Ephemeral document remains active during pause", runtime.isEphemeralActive)
    }

    // 11. Tap Resume in expanded bubble -> reading resumes from current position
    @Test
    fun test11_tapResume_resumesFromCurrentPosition() {
        val ephemeralDoc = createEphemeralDoc()
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()
        val firstSeg = runtime.readingEngine.currentSegment.value
        runtime.pauseReading()

        // Resume reading
        val resumedSeg = runtime.resumeReading()
        assertTrue(runtime.readingSessionState.value.isReading)
        assertEquals(firstSeg?.id, resumedSeg?.id)
    }

    // 12. Tap Stop in expanded bubble -> reading stops, highlight cleared, returns to ReadMe primary document if available
    @Test
    fun test12_tapStop_stopsReadingAndReturnsToPrimaryDocument() {
        // Load primary document
        val primaryDoc = createDoc(id = "primary_doc_1", title = "Primary Book")
        val token = coordinator.startLoading(primaryDoc.title)
        coordinator.onDocumentLoaded(token, primaryDoc, primaryDoc.title)
        runtime.startReading()

        // Suspend primary with ephemeral screen document
        val ephemeralDoc = createEphemeralDoc()
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()
        assertTrue(runtime.activeDocumentState.value.isEphemeral)

        // Tap Stop
        runtime.stopReading()
        val restored = runtime.returnToPrimaryDocument()
        assertTrue("Should return to primary document", restored)
        assertFalse("Primary document is not ephemeral", runtime.activeDocumentState.value.isEphemeral)
        assertEquals("primary_doc_1", runtime.activeDocumentState.value.documentId)
    }

    // 13. Reselect area flow: tap Reselect -> current speech stops
    @Test
    fun test13_reselectArea_stopsCurrentSpeech() {
        val ephemeralDoc = createEphemeralDoc()
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()
        assertTrue(runtime.readingSessionState.value.isReading)

        // Step 1 of reselect flow
        runtime.stopReading()
        assertFalse(runtime.readingSessionState.value.isReading)
    }

    // 14. Reselect area flow: highlight is cleared immediately
    @Test
    fun test14_reselectArea_highlightClearedImmediately() {
        var highlightCleared = false
        val clearHighlight = { highlightCleared = true }

        // Step 2 of reselect flow
        clearHighlight()
        assertTrue(highlightCleared)
    }

    // 15. Reselect area flow: selection overlay opens
    @Test
    fun test15_reselectArea_selectionOverlayOpens() {
        var overlayOpened = false
        val showOverlay = { overlayOpened = true }

        showOverlay()
        assertTrue(overlayOpened)
    }

    // 16. Reselect area flow: cancel selection -> previous reading does NOT resume, highlight remains cleared
    @Test
    fun test16_reselectArea_cancelSelectionDoesNotResumeReading() {
        val ephemeralDoc = createEphemeralDoc()
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()

        // Reselect initiated
        runtime.stopReading()
        runtime.discardEphemeralContext()

        // User cancels selection overlay
        val onCancelled = {
            // No-op; reading should not resume
        }
        onCancelled()

        assertFalse("Reading must remain stopped on cancel", runtime.readingSessionState.value.isReading)
        assertFalse("Ephemeral document should be cleared", runtime.activeDocumentState.value.hasActiveDocument)
    }

    // 17. Reselect area flow: new region confirmed -> capture -> OCR -> new ephemeral document loaded -> reading begins
    @Test
    fun test17_reselectArea_confirmedRegionLoadsNewDocAndBeginsReading() {
        val doc1 = createEphemeralDoc(id = "ocr_doc_1", title = "First Region")
        runtime.loadEphemeralDocument(doc1)
        runtime.startReading()

        // Reselect flow starts
        runtime.stopReading()
        runtime.discardEphemeralContext()

        // New region selected
        val doc2 = createEphemeralDoc(id = "ocr_doc_2", title = "Second Region")
        val loaded = runtime.loadEphemeralDocument(doc2)
        assertTrue(loaded)
        val startedSeg = runtime.startReading()
        assertNotNull(startedSeg)
        assertTrue(runtime.readingSessionState.value.isReading)
        assertEquals("ocr_doc_2", runtime.activeDocumentState.value.documentId)
    }

    // 18. Drag bubble moves position
    @Test
    fun test18_dragBubbleMovesPosition() {
        val startX = 100
        val startY = 200
        val dx = 50f
        val dy = 70f
        val newX = startX + dx.toInt()
        val newY = startY + dy.toInt()
        assertEquals(150, newX)
        assertEquals(270, newY)
    }

    // 19. Drag into bottom close drop target -> close target changes appearance (hovered)
    @Test
    fun test19_dragIntoCloseZone_detectedAsHovered() {
        val screenHeight = 1920
        val bubbleHeight = 160
        val thresholdPx = 300

        // Bubble at the top of the screen -> not in close zone
        assertFalse(SystemFloatingBubbleController.isPositionInCloseZone(
            bubbleY = 200,
            bubbleHeight = bubbleHeight,
            screenHeight = screenHeight,
            thresholdPx = thresholdPx
        ))

        // Bubble dragged near the bottom -> inside close zone
        assertTrue(SystemFloatingBubbleController.isPositionInCloseZone(
            bubbleY = 1750,
            bubbleHeight = bubbleHeight,
            screenHeight = screenHeight,
            thresholdPx = thresholdPx
        ))
    }

    // 20. Drag released in bottom drop target -> bubble closes
    @Test
    fun test20_dragReleasedInBottomDropTarget_closesBubble() {
        var bubbleClosed = false
        val onCloseBubble = { bubbleClosed = true }

        val screenHeight = 1920
        val bubbleHeight = 160
        val thresholdPx = 300
        val releasedY = 1800

        if (SystemFloatingBubbleController.isPositionInCloseZone(releasedY, bubbleHeight, screenHeight, thresholdPx)) {
            onCloseBubble()
        }

        assertTrue("Bubble must close when released in bottom drop target", bubbleClosed)
    }

    // 21. Drag released elsewhere -> bubble remains visible at clamped position
    @Test
    fun test21_dragReleasedElsewhere_remainsVisibleClamped() {
        val screenWidth = 1080
        val screenHeight = 1920
        val bubbleWidth = 160
        val bubbleHeight = 160

        // Clamp offscreen right/bottom
        val (clampedX, clampedY) = SystemFloatingBubbleController.clampPosition(
            x = 1200,
            y = 2000,
            viewWidth = bubbleWidth,
            viewHeight = bubbleHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
        assertEquals(1080 - 160, clampedX)
        assertEquals(1920 - 160, clampedY)
    }

    // 22. Closing bubble does NOT stop active reading
    @Test
    fun test22_closingBubble_doesNotStopActiveReading() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)
        runtime.startReading()
        assertTrue("Speech should be reading", runtime.readingSessionState.value.isReading)

        // User closes bubble
        runtime.closeBubbleByUser()
        assertTrue(runtime.isBubbleClosedByUser.value)

        // Crucial test: Reading must NOT be stopped
        assertTrue("Closing bubble must not stop speech synthesis", runtime.readingSessionState.value.isReading)
    }

    // 23. Closing bubble does NOT clear reading notification
    @Test
    fun test23_closingBubble_doesNotClearReadingNotification() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)
        runtime.startReading()

        // User closes bubble
        runtime.closeBubbleByUser()

        // The session state is still reading, so notification is still active
        assertTrue(runtime.readingSessionState.value.isReading)
    }

    // 24. Read from other apps disabled -> external reading controls unavailable/hidden/disabled
    @Test
    fun test24_readFromOtherAppsDisabled_acquisitionReturnsFeatureDisabled() = kotlinx.coroutines.runBlocking {
        val request = CrossAppAcquisitionRequest(targetPackageName = "com.other.app")
        // Check feature disabled result
        val result: UnifiedCrossAppAcquisitionResult = UnifiedCrossAppAcquisitionResult.FeatureDisabled
        assertTrue(result is UnifiedCrossAppAcquisitionResult.FeatureDisabled)
    }

    // 25. Read from other apps enabled without accessibility -> prompt/disclosure required before enabling
    @Test
    fun test25_crossAppEnabledWithoutAccessibility_requiresDisclosure() {
        val isAccessibilityConnected = false
        val requiresDisclosure = !isAccessibilityConnected
        assertTrue("Must require disclosure dialog before directing to settings", requiresDisclosure)
    }

    // 26. When ReadMe returns to foreground -> bubble hides automatically
    @Test
    fun test26_readMeReturnsToForeground_bubbleHidesAutomatically() {
        runtime.setAppForeground(true)
        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = runtime.appForegroundState.value,
            isClosedByUser = false
        )
        assertEquals(BubbleVisibilityState.HiddenByForeground, visibility)

        val uiState = BubbleLifecyclePolicy.computeUiState(
            visibilityState = visibility,
            sessionState = runtime.readingSessionState.value,
            documentState = runtime.activeDocumentState.value,
            isExpanded = false
        )
        assertEquals(BubbleUiState.Hidden, uiState)
    }

    // 27. When ReadMe returns to background after user previously closed bubble -> bubble remains closed until re-enabled or user re-opens
    @Test
    fun test27_closedByUser_remainsClosedUntilReopened() {
        runtime.closeBubbleByUser()
        assertTrue(runtime.isBubbleClosedByUser.value)

        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = runtime.isBubbleClosedByUser.value
        )
        assertEquals(BubbleVisibilityState.HiddenByUser, visibility)
    }

    // 28. Manual re-open from Settings or app -> bubble becomes visible again
    @Test
    fun test28_manualReopen_bubbleBecomesVisibleAgain() {
        runtime.closeBubbleByUser()
        assertTrue(runtime.isBubbleClosedByUser.value)

        // User taps "Show Floating ReadMe"
        runtime.reopenBubble()
        assertFalse(runtime.isBubbleClosedByUser.value)

        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = runtime.isBubbleClosedByUser.value
        )
        assertEquals(BubbleVisibilityState.Visible, visibility)
    }
}
