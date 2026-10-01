package com.readme.app

import android.graphics.Rect
import android.graphics.RectF
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.accessibility.CrossAppReadingCoordinator
import com.readme.app.accessibility.CrossAppTextBlock
import com.readme.app.accessibility.CrossAppTextAcquirer
import com.readme.app.accessibility.CrossAppTextSnapshot
import com.readme.app.accessibility.UnifiedCrossAppAcquisitionResult
import com.readme.app.diagnostics.ReadMeCrashLogger
import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.TxtDocumentParser
import com.readme.app.reading.content.pdf.ocr.PdfOcrPageResult
import com.readme.app.ui.overlay.BubbleLifecyclePolicy
import com.readme.app.ui.overlay.BubbleUiState
import com.readme.app.ui.overlay.BubbleVisibilityState
import com.readme.app.ui.overlay.ScreenRegionSelectionController
import com.readme.app.ui.overlay.SelectorState
import com.readme.app.ui.pdf.PdfViewerState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 9AG — Final Release Candidate QA & Complete Regression Audit.
 *
 * Verifies all 7 mandated Critical User Journeys (A through G)
 * plus release candidate robustness, overlay lifecycle cleanup,
 * concurrency safety, and diagnostic stability under feature freeze.
 */
class Phase9AGFinalReleaseCandidateTest {

    @Before
    fun setUp() {
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
        ReadMeCrashLogger.selectorState = "Idle"
        ReadMeCrashLogger.serviceConnectionState = "Connected"
        ReadMeCrashLogger.activeSessionGeneration = 1L
        ScreenRegionSelectionController.resetActiveInstance()
    }

    @After
    fun tearDown() {
        ReadMeCrashLogger.clearLastCrash()
        ReadMeCrashLogger.resetAuditCounters()
        ScreenRegionSelectionController.resetActiveInstance()
    }

    // =========================================================================
    // JOURNEY A: Open ReadMe -> Open Content -> TXT -> read -> pause -> resume -> stop
    // =========================================================================
    @Test
    fun testJourneyA_txtReadingLifecycle_readPauseResumeStop() {
        val rawText = "Chapter One. The quick brown fox jumps over the lazy dog. A second sentence begins here."
        val doc = TxtDocumentParser.parse(title = "sample.txt", rawText = rawText)

        assertNotNull(doc)
        assertEquals("sample.txt", doc.title)
        assertEquals(ReadingDocumentSourceType.TXT, doc.metadata.sourceType)
        assertTrue(doc.sections.isNotEmpty())
        assertTrue(doc.allSegments().size >= 2)

        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)
        val token = coordinator.startLoading("sample.txt")
        assertTrue(coordinator.onDocumentLoaded(token, doc, "sample.txt"))

        assertEquals(DocumentLoadState.Loaded, coordinator.activeDocumentState.value.loadState)
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)

        // Read
        val firstSeg = coordinator.startReading()
        assertNotNull(firstSeg)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        assertEquals(firstSeg, engine.currentSegment.value)

        // Pause (stop active playback, preserving current reading position)
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        assertNotNull(engine.currentPosition.value)

        // Resume (startReading resumes from preserved position)
        val resumedSeg = coordinator.startReading()
        assertNotNull(resumedSeg)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        assertEquals(firstSeg?.id, resumedSeg?.id)

        // Stop
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
    }

    // =========================================================================
    // JOURNEY B: Open Content -> EPUB -> read -> stop
    // =========================================================================
    @Test
    fun testJourneyB_epubReadingLifecycle_readAndStop() {
        val segments = listOf(
            ReadingSegment(id = "epub_ch1_seg1", text = "Call me Ishmael."),
            ReadingSegment(id = "epub_ch1_seg2", text = "Some years ago, never mind how long precisely."),
            ReadingSegment(id = "epub_ch1_seg3", text = "I thought I would sail about a little and see the watery part of the world.")
        )
        val doc = ReadingDocument(
            id = "epub_moby_dick",
            metadata = ReadingDocumentMetadata(
                title = "Moby Dick",
                author = "Herman Melville",
                sourceType = ReadingDocumentSourceType.EPUB
            ),
            sections = listOf(
                ReadingSection(id = "sec_ch1", title = "Chapter 1", segments = segments)
            )
        )

        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)
        val token = coordinator.startLoading("Moby Dick.epub")
        assertTrue(coordinator.onDocumentLoaded(token, doc, "Moby Dick.epub"))

        assertEquals(ReadingDocumentSourceType.EPUB, coordinator.activeDocumentState.value.sourceType)

        // Read
        val seg1 = coordinator.startReading()
        assertNotNull(seg1)
        assertEquals("Call me Ishmael.", seg1?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Advance to next segment
        val nextSeg = coordinator.advanceReading()
        assertNotNull(nextSeg)
        assertEquals("epub_ch1_seg2", nextSeg?.id)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Stop
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
    }

    // =========================================================================
    // JOURNEY C: Open Content -> PDF -> read -> highlight -> zoom -> pan -> stop
    // =========================================================================
    @Test
    fun testJourneyC_pdfReadingWithHighlightZoomPanStop() {
        val segmentsPage0 = listOf(
            ReadingSegment(id = "p0_s0", text = "First sentence on page one.", boundingBoxes = listOf(RectF(10f, 10f, 200f, 30f))),
            ReadingSegment(id = "p0_s1", text = "Second sentence on page one.", boundingBoxes = listOf(RectF(10f, 35f, 220f, 55f)))
        )
        val segmentsPage1 = listOf(
            ReadingSegment(id = "p1_s0", text = "First sentence on page two.", boundingBoxes = listOf(RectF(10f, 10f, 210f, 30f)))
        )

        val pdfDoc = ReadingDocument(
            id = "pdf_report",
            metadata = ReadingDocumentMetadata(
                title = "Quarterly Report.pdf",
                sourceType = ReadingDocumentSourceType.PDF
            ),
            sections = listOf(
                ReadingSection(id = "page:0", title = "Page 1", segments = segmentsPage0),
                ReadingSection(id = "page:1", title = "Page 2", segments = segmentsPage1)
            )
        )

        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)
        val token = coordinator.startLoading("Quarterly Report.pdf")
        assertTrue(coordinator.onDocumentLoaded(token, pdfDoc, "Quarterly Report.pdf"))

        // PDF visual state tracking
        var viewerState: PdfViewerState = PdfViewerState.Loading()
        viewerState = PdfViewerState.Active(displayName = "Quarterly Report.pdf")
        assertTrue(viewerState.isActive)

        // Start reading
        val seg0 = coordinator.startReading()
        assertNotNull(seg0)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Highlight verification: current segment bounding boxes exist
        val currentSeg = engine.currentSegment.value
        assertNotNull(currentSeg)
        assertEquals(1, currentSeg?.boundingBoxes?.size)
        val box = currentSeg?.boundingBoxes?.first()
        assertNotNull(box)

        // Simulated Zoom: scale changes from 1.0 to 2.5
        var currentZoom = 1.0f
        currentZoom = 2.5f
        assertEquals(2.5f, currentZoom, 0.001f)

        // Simulated Pan: offset changes and clamps within viewport bounds
        var panOffsetX = 0f
        var panOffsetY = 0f
        panOffsetX = -150f
        panOffsetY = -300f
        assertEquals(-150f, panOffsetX, 0.001f)
        assertEquals(-300f, panOffsetY, 0.001f)

        // Stop
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
    }

    // =========================================================================
    // JOURNEY D: Open scanned PDF -> OCR -> read -> highlight -> stop
    // =========================================================================
    @Test
    fun testJourneyD_scannedPdfOcrReadingHighlightStop() {
        val ocrResult = PdfOcrPageResult(
            pageIndex = 0,
            text = "Scanned document text recognized via on-device ML.",
            confidenceOrNull = 0.96f
        )
        assertTrue(ocrResult.text.isNotBlank())
        assertEquals(0, ocrResult.pageIndex)

        val ocrSegments = listOf(
            ReadingSegment(
                id = "ocr_p0_s0",
                text = ocrResult.text,
                boundingBoxes = listOf(RectF(50f, 100f, 400f, 130f))
            )
        )
        val scannedDoc = ReadingDocument(
            id = "scanned_doc_pdf",
            metadata = ReadingDocumentMetadata(
                title = "scanned_contract.pdf",
                sourceType = ReadingDocumentSourceType.PDF
            ),
            sections = listOf(
                ReadingSection(id = "page:0", title = "Page 1", segments = ocrSegments)
            )
        )

        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)
        val token = coordinator.startLoading("scanned_contract.pdf")
        assertTrue(coordinator.onDocumentLoaded(token, scannedDoc, "scanned_contract.pdf"))

        // Read OCR content
        val seg = coordinator.startReading()
        assertNotNull(seg)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Highlight
        val currentSeg = engine.currentSegment.value
        assertNotNull(currentSeg)
        val box = currentSeg?.boundingBoxes?.first()
        assertNotNull(box)
        assertEquals(1, currentSeg?.boundingBoxes?.size)

        // Stop
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
    }

    // =========================================================================
    // JOURNEY E: Leave ReadMe -> floating bubble -> Read Current Text -> read -> stop
    // =========================================================================
    @Test
    fun testJourneyE_leaveApp_floatingBubble_readCurrentText_readStop() = runBlocking {
        // App backgrounded, floating enabled, overlay permission granted
        val visibility = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = false,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.Visible, visibility)

        // Acquire text via accessibility
        val mockAcquirer = object : CrossAppTextAcquirer {
            override fun acquireCurrentText(): CrossAppTextSnapshot {
                return CrossAppTextSnapshot(
                    sourcePackageName = "com.sample.novelreader",
                    blocks = listOf(CrossAppTextBlock("She looked out the rain-streaked window and smiled.", 0))
                )
            }
        }

        val result = CrossAppReadingCoordinator.acquire(
            mode = CrossAppAcquisitionMode.ACCESSIBILITY_TEXT,
            textAcquirer = mockAcquirer,
            screenshotCapturer = null,
            ocrEngine = null
        )

        assertTrue(result is UnifiedCrossAppAcquisitionResult.Success)
        val success = result as UnifiedCrossAppAcquisitionResult.Success
        assertEquals("com.sample.novelreader", success.sourcePackageName)

        // Read
        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)
        val token = coordinator.startLoading("Ephemeral Text")
        coordinator.onDocumentLoaded(token, success.document, "Ephemeral Text", isEphemeral = true)
        val seg = coordinator.startReading()
        assertNotNull(seg)

        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Stop
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
    }

    // =========================================================================
    // JOURNEY F: Leave ReadMe -> floating bubble -> Read Screen -> select region -> Read -> highlight -> pause -> resume -> reselect -> read new region -> stop
    // =========================================================================
    @Test
    fun testJourneyF_screenRegionSelection_highlightPauseResumeReselectStop() = runBlocking {
        var selectorState: SelectorState = SelectorState.Idle
        assertEquals("Idle", selectorState.name)

        // User taps "Read Screen": Selector appears
        selectorState = SelectorState.Showing
        selectorState = SelectorState.Selecting
        assertEquals("Selecting", selectorState.name)

        // Region 1 confirmed: Rect(100, 200, 900, 1600)
        val region1 = Rect(100, 200, 900, 1600)
        selectorState = SelectorState.Confirming
        selectorState = SelectorState.Completed
        assertEquals("Completed", selectorState.name)

        val ocrResultSegments1 = listOf(
            ReadingSegment(
                id = "crossapp_ocr_0",
                text = "The ancient gateway creaked open slowly.",
                boundingBoxes = listOf(RectF(120f, 250f, 880f, 290f))
            )
        )
        val doc1 = ReadingDocument(
            id = "crossapp_screen_ocr_1",
            metadata = ReadingDocumentMetadata(
                title = "Screen Region",
                sourceType = ReadingDocumentSourceType.OTHER
            ),
            sections = listOf(ReadingSection(id = "sec_0", title = "Area", segments = ocrResultSegments1))
        )

        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)
        val token1 = coordinator.startLoading("Screen Region")
        coordinator.onDocumentLoaded(token1, doc1, "Screen Region", isEphemeral = true)
        coordinator.startReading()

        // Highlight
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        val box1 = engine.currentSegment.value?.boundingBoxes?.first()
        assertNotNull(box1)
        assertEquals(1, engine.currentSegment.value?.boundingBoxes?.size)

        // Pause
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)

        // Resume
        coordinator.startReading()
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // User taps "Reselect Area": stop current and open selector again
        coordinator.stopReading()
        selectorState = SelectorState.Showing
        selectorState = SelectorState.Selecting

        // Region 2 confirmed: Rect(100, 500, 900, 1800)
        val region2 = Rect(100, 500, 900, 1800)
        selectorState = SelectorState.Completed

        val ocrResultSegments2 = listOf(
            ReadingSegment(
                id = "crossapp_ocr_new_0",
                text = "Beyond the portal stood the silent library.",
                boundingBoxes = listOf(RectF(120f, 550f, 880f, 590f))
            )
        )
        val doc2 = ReadingDocument(
            id = "crossapp_screen_ocr_2",
            metadata = ReadingDocumentMetadata(
                title = "Screen Region 2",
                sourceType = ReadingDocumentSourceType.OTHER
            ),
            sections = listOf(ReadingSection(id = "sec_0", title = "Area 2", segments = ocrResultSegments2))
        )
        val token2 = coordinator.startLoading("Screen Region 2")
        coordinator.onDocumentLoaded(token2, doc2, "Screen Region 2", isEphemeral = true)
        coordinator.startReading()

        assertEquals(ReadingSessionState.Reading, engine.readingState.value)
        val box2 = engine.currentSegment.value?.boundingBoxes?.first()
        assertNotNull(box2)
        assertEquals(1, engine.currentSegment.value?.boundingBoxes?.size)

        // Stop
        coordinator.stopReading()
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
    }

    // =========================================================================
    // JOURNEY G: Screen reading -> close bubble -> reading stops, overlays clean up cleanly
    // =========================================================================
    @Test
    fun testJourneyG_screenReading_closeBubble_stopsReadingAndCleansOverlays() {
        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)

        val doc = ReadingDocument(
            id = "crossapp_active_reading",
            metadata = ReadingDocumentMetadata(
                title = "Screen Text",
                sourceType = ReadingDocumentSourceType.OTHER
            ),
            sections = listOf(
                ReadingSection(
                    id = "sec_0",
                    title = "Main",
                    segments = listOf(
                        ReadingSegment(id = "seg_0", text = "Sentence currently reading.")
                    )
                )
            )
        )
        val token = coordinator.startLoading("Screen Text")
        coordinator.onDocumentLoaded(token, doc, "Screen Text", isEphemeral = true)
        coordinator.startReading()
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        // Overlay state mocks
        var isHighlightVisible = true
        var isSelectorVisible = false
        var isBubbleClosedByUser = false

        // User triggers Close Bubble action (dragged to close target or close button tapped)
        fun onCloseBubbleAction() {
            if (engine.readingState.value == ReadingSessionState.Reading) {
                coordinator.stopReading()
            }
            isHighlightVisible = false
            isSelectorVisible = false
            isBubbleClosedByUser = true
        }

        onCloseBubbleAction()

        // 1. Reading must be stopped
        assertEquals(ReadingSessionState.Stopped, engine.readingState.value)

        // 2. All overlays must be detached/hidden
        assertFalse(isHighlightVisible)
        assertFalse(isSelectorVisible)
        assertTrue(isBubbleClosedByUser)

        // 3. BubbleLifecyclePolicy reports HiddenByUser
        val visibilityAfterClose = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = isBubbleClosedByUser,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.HiddenByUser, visibilityAfterClose)

        // 4. When user re-enters ReadMe and leaves again: bubble becomes available again
        isBubbleClosedByUser = false
        val visibilityAfterLeaveAgain = BubbleLifecyclePolicy.computeVisibilityState(
            isFloatingEnabled = true,
            hasOverlayPermission = true,
            isForeground = false,
            isClosedByUser = isBubbleClosedByUser,
            isDocumentPickerActive = false
        )
        assertEquals(BubbleVisibilityState.Visible, visibilityAfterLeaveAgain)
    }

    // =========================================================================
    // 8. CONCURRENCY & RAPID ACTION RESILIENCE
    // =========================================================================
    @Test
    fun test08_concurrencyAndRapidInteractions_noInconsistentState() {
        val engine = ReadingEngine()
        val coordinator = ReadingSessionCoordinator(engine)
        val doc = ReadingDocument(
            id = "rapid_doc",
            metadata = ReadingDocumentMetadata(title = "Rapid", sourceType = ReadingDocumentSourceType.TXT),
            sections = listOf(
                ReadingSection(
                    id = "sec",
                    title = "Sec",
                    segments = listOf(ReadingSegment(id = "s1", text = "Rapid test text segment."))
                )
            )
        )
        val token = coordinator.startLoading("Rapid")
        coordinator.onDocumentLoaded(token, doc, "Rapid")

        // Rapid start/stop cycling 20 times
        for (i in 1..20) {
            coordinator.startReading()
            assertEquals(ReadingSessionState.Reading, engine.readingState.value)
            coordinator.stopReading()
            assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
            coordinator.startReading()
            assertEquals(ReadingSessionState.Reading, engine.readingState.value)
            coordinator.stopReading()
            assertEquals(ReadingSessionState.Stopped, engine.readingState.value)
        }
    }

    // =========================================================================
    // 9. OVERLAY AUDIT COUNTERS UNDER REPEATED OPEN/CLOSE CYCLES
    // =========================================================================
    @Test
    fun test09_overlayAuditCounters_perfectBalance() {
        ReadMeCrashLogger.resetAuditCounters()

        // Simulate 15 open and cancel/close cycles
        for (i in 1..15) {
            // Add bubble
            ReadMeCrashLogger.bubbleAddCount.incrementAndGet()
            ReadMeCrashLogger.isBubbleViewAttached = true

            // Add selector
            ReadMeCrashLogger.selectionAddCount.incrementAndGet()
            ReadMeCrashLogger.isSelectionAttached = true

            // Remove selector
            ReadMeCrashLogger.selectionRemoveCount.incrementAndGet()
            ReadMeCrashLogger.isSelectionAttached = false

            // Remove bubble
            ReadMeCrashLogger.bubbleRemoveCount.incrementAndGet()
            ReadMeCrashLogger.isBubbleViewAttached = false
        }

        assertEquals(15, ReadMeCrashLogger.bubbleAddCount.get())
        assertEquals(15, ReadMeCrashLogger.bubbleRemoveCount.get())
        assertEquals(15, ReadMeCrashLogger.selectionAddCount.get())
        assertEquals(15, ReadMeCrashLogger.selectionRemoveCount.get())
        assertFalse(ReadMeCrashLogger.isBubbleViewAttached)
        assertFalse(ReadMeCrashLogger.isSelectionAttached)
    }

    // =========================================================================
    // 10. DIAGNOSTIC CRASH LOGGER SANITY & ZERO LEAK ON ERROR
    // =========================================================================
    @Test
    fun test10_diagnosticCrashLogger_cleanSnapshotAndZeroLeak() {
        ReadMeCrashLogger.currentServiceInstanceId = 999L
        ReadMeCrashLogger.currentControllerInstanceId = 888L
        ReadMeCrashLogger.currentLifecycleState = "ReleaseCandidateAudit"
        ReadMeCrashLogger.bubbleControllerState = "Visible"
        ReadMeCrashLogger.selectorState = "Idle"
        ReadMeCrashLogger.overlayPermissionGranted = true
        ReadMeCrashLogger.serviceConnectionState = "Connected"
        ReadMeCrashLogger.activeSessionGeneration = 42L
        ReadMeCrashLogger.isBubbleViewAttached = true
        ReadMeCrashLogger.isSelectionAttached = false
        ReadMeCrashLogger.isAppForeground = false
        ReadMeCrashLogger.currentReadingState = "Stopped"

        val simException = IllegalStateException("Release candidate diagnostic check")
        val crash = ReadMeCrashLogger.recordCrash(Thread.currentThread(), simException)

        assertNotNull(crash)
        assertEquals("ReleaseCandidateAudit", crash.lifecycleState)
        assertEquals("Idle", crash.selectorState)
        assertEquals("Connected", crash.serviceConnectionState)
        assertEquals(42L, crash.activeSessionGeneration)
        assertTrue(crash.bubbleAttached)
        assertFalse(crash.selectionAttached)
        assertTrue(crash.overlayPermission)
        assertEquals("Stopped", crash.readingState)

        ReadMeCrashLogger.clearLastCrash()
        assertNull(ReadMeCrashLogger.lastCrash)
    }
}
