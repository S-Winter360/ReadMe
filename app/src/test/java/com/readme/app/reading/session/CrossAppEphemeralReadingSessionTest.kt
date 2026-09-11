package com.readme.app.reading.session

import com.readme.app.accessibility.CrossAppAcquisitionCoordinator
import com.readme.app.accessibility.CrossAppAcquisitionRequest
import com.readme.app.accessibility.CrossAppTextAcquirer
import com.readme.app.accessibility.CrossAppTextBlock
import com.readme.app.accessibility.CrossAppTextSnapshot
import com.readme.app.reading.ActiveDocumentState
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
import com.readme.app.reading.content.CrossAppDocumentParser
import com.readme.app.reading.progress.InMemoryReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgress
import com.readme.app.reading.progress.SavedProgressState
import com.readme.app.reading.service.ReadMeReadingSessionRuntime
import com.readme.app.speech.ReadMeSpeechEngine
import com.readme.app.speech.SpeechEngineListener
import com.readme.app.speech.TtsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Phase 9H Comprehensive Verification Test Suite:
 * Unified Ephemeral Cross-App Reading Session, Safe Suspension and Restoration.
 */
class CrossAppEphemeralReadingSessionTest {

    private lateinit var readingEngine: ReadingEngine
    private lateinit var testProgressRepository: InMemoryReadingProgressRepository
    private lateinit var sessionCoordinator: ReadingSessionCoordinator
    private lateinit var testSpeechEngine: TestSpeechEngine
    private lateinit var testScope: CoroutineScope
    private lateinit var runtime: ReadMeReadingSessionRuntime

    private class TestSpeechEngine : ReadMeSpeechEngine(context = null) {
        var lastSpokenSegmentId: String? = null
        var lastSpokenText: String? = null
        var lastSessionId: Long? = null
        var speakCallCount = 0
        var stopCallCount = 0
        var shutdownCallCount = 0
        val listener: SpeechEngineListener? get() = speechListenerForTesting

        override fun speakSegment(
            segmentId: String,
            text: String,
            sessionId: Long,
            voiceId: String,
            speed: Float,
            pitch: Float,
            volume: Float
        ) {
            speakCallCount++
            lastSpokenSegmentId = segmentId
            lastSpokenText = text
            lastSessionId = sessionId
            setStateForTesting(TtsState.Speaking)
            listener?.onSegmentStarted(segmentId, sessionId)
        }

        override fun stop() {
            stopCallCount++
            lastSpokenSegmentId = null
            setStateForTesting(TtsState.Ready)
        }

        override fun shutdown() {
            shutdownCallCount++
            setStateForTesting(TtsState.Uninitialized)
        }
    }

    private class MockAcquirer(
        private val snapshotProvider: () -> CrossAppTextSnapshot?
    ) : CrossAppTextAcquirer {
        override fun acquireCurrentText(): CrossAppTextSnapshot? = snapshotProvider()
    }

    private fun createPrimaryDoc(
        id: String = "primary_doc_1",
        title: String = "War and Peace",
        sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.TXT,
        segmentCount: Int = 5
    ): ReadingDocument {
        val segments = (0 until segmentCount).map { i ->
            ReadingSegment(id = "${id}_seg_$i", text = "Primary sentence $i of $title.")
        }
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = sourceType,
            sections = listOf(
                ReadingSection(id = "${id}_sec_0", title = "Chapter 1", segments = segments)
            )
        )
    }

    private fun createSnapshot(
        packageName: String = "com.example.browser",
        textList: List<String> = listOf("First external sentence.", "Second external sentence."),
        title: String? = "Web Article",
        generation: Long = 1L
    ): CrossAppTextSnapshot {
        val blocks = textList.mapIndexed { idx, text ->
            CrossAppTextBlock(
                text = text,
                order = idx
            )
        }
        return CrossAppTextSnapshot(
            sourcePackageName = packageName,
            blocks = blocks,
            title = title,
            capturedAt = System.currentTimeMillis(),
            generation = generation
        )
    }

    @Before
    fun setUp() {
        readingEngine = ReadingEngine()
        testProgressRepository = InMemoryReadingProgressRepository()
        sessionCoordinator = ReadingSessionCoordinator(readingEngine, testProgressRepository)
        testSpeechEngine = TestSpeechEngine()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        runtime = ReadMeReadingSessionRuntime(
            context = null,
            progressRepository = testProgressRepository,
            readingEngine = readingEngine,
            sessionCoordinator = sessionCoordinator,
            speechEngine = testSpeechEngine,
            settingsRepository = null,
            runtimeScope = testScope,
            ioDispatcher = Dispatchers.Unconfined
        )
        ReadMeReadingSessionRuntime.setInstanceForTesting(runtime)
    }

    @After
    fun tearDown() {
        runtime.shutdown()
        ReadMeReadingSessionRuntime.resetForTesting()
        testScope.cancel()
    }

    // 1. Ephemeral snapshot loading transforms into ephemeral ReadingDocument
    @Test
    fun test01_ephemeralSnapshotTransformsToReadingDocument() {
        val snapshot = createSnapshot()
        val doc = CrossAppDocumentParser.parse(snapshot, appLabel = "Chrome")
        assertEquals("Text from Chrome", doc.metadata.title)
        assertEquals(2, doc.allSegments().size)
        assertTrue(doc.id.startsWith("crossapp_"))
    }

    // 2. Loading ephemeral document suspends active primary document
    @Test
    fun test02_loadingEphemeralSuspendsActivePrimaryDocument() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.startReading()

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        val loaded = runtime.loadEphemeralDocument(
            document = ephemeralDoc,
            displayName = "External Text",
            sourcePackageName = "com.example.browser",
            sourceAppLabel = "Browser"
        )

        assertTrue(loaded)
        assertTrue(runtime.isEphemeralActive)
        assertNotNull(runtime.currentSuspendedPrimaryContext)
        assertEquals(primary.id, runtime.currentSuspendedPrimaryContext?.document?.id)
        assertEquals(primary.title, runtime.currentSuspendedPrimaryContext?.displayName)
    }

    // 3. Suspended state captures primary document, title, position, session state
    @Test
    fun test03_suspendedStateCapturesAllPrimaryDetails() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.startReading()
        sessionCoordinator.advanceReading() // Advance to sentence 1

        val currentPos = readingEngine.currentPosition.value
        assertNotNull(currentPos)
        assertEquals(1, currentPos?.segmentIndex)

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(
            document = ephemeralDoc,
            displayName = "External Text",
            sourcePackageName = "com.example.browser"
        )

        val suspended = runtime.currentSuspendedPrimaryContext
        assertNotNull(suspended)
        assertEquals(1, suspended?.savedPosition?.segmentIndex)
        assertEquals(ReadingSessionState.Reading, suspended?.priorSessionState)
    }

    // 4. Loading ephemeral halts active speech
    @Test
    fun test04_loadingEphemeralHaltsSpeech() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.startReading()
        assertEquals(1, testSpeechEngine.speakCallCount)

        val stopsBefore = testSpeechEngine.stopCallCount
        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)

        assertTrue(testSpeechEngine.stopCallCount > stopsBefore)
    }

    // 5. Ephemeral reading starts from beginning
    @Test
    fun test05_ephemeralReadingStartsFromBeginning() {
        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)

        val segment = runtime.startReading()
        assertNotNull(segment)
        assertEquals("First external sentence.", segment?.text)
        assertEquals("First external sentence.", testSpeechEngine.lastSpokenText)
    }

    // 6. Ephemeral advances and completes
    @Test
    fun test06_ephemeralAdvancesAndCompletes() {
        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("S1", "S2")))
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()

        val activeSessionId = runtime.currentSessionId
        // Complete S1 -> advance to S2
        runtime.handleSegmentCompletedForTesting(ephemeralDoc.allSegments()[0].id, activeSessionId)
        assertEquals("S2", testSpeechEngine.lastSpokenText)

        // Complete S2 -> completed
        runtime.handleSegmentCompletedForTesting(ephemeralDoc.allSegments()[1].id, activeSessionId)
        assertTrue(sessionCoordinator.readingSessionState.value.isCompleted)
    }

    // 7. Ephemeral reading NEVER persists to ReadingProgressRepository
    @Test
    fun test07_ephemeralReadingNeverPersistsProgress() = runBlocking {
        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("E1", "E2")))
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()

        // Explicitly attempt to save progress
        runtime.saveCurrentProgress(isCompleted = false)
        assertTrue(testProgressRepository.getAllProgress().isEmpty())

        val activeSessionId = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting(ephemeralDoc.allSegments()[0].id, activeSessionId)
        assertTrue(testProgressRepository.getAllProgress().isEmpty())

        runtime.stopReading()
        assertTrue(testProgressRepository.getAllProgress().isEmpty())

        runtime.startReading()
        runtime.handleSegmentCompletedForTesting(ephemeralDoc.allSegments()[1].id, runtime.currentSessionId)
        assertTrue(testProgressRepository.getAllProgress().isEmpty())
    }

    // 8. Ephemeral reading state reflects metadata
    @Test
    fun test08_ephemeralStateReflectsMetadata() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(
            document = ephemeralDoc,
            displayName = "Snapshot Text",
            sourcePackageName = "com.news.app",
            sourceAppLabel = "News App"
        )

        val state = sessionCoordinator.activeDocumentState.value
        assertTrue(state.isEphemeral)
        assertEquals("com.news.app", state.sourcePackageName)
        assertEquals("News App", state.sourceAppLabel)
        assertTrue(state.hasSuspendedPrimary)
        assertEquals(primary.title, state.suspendedPrimaryTitle)
        assertTrue(sessionCoordinator.readingSessionState.value.isEphemeral)
    }

    // 9. returnToPrimaryDocument restores suspended primary without auto-starting speech
    @Test
    fun test09_returnToPrimaryDocumentRestoresWithoutAutoSpeech() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.startReading()

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)

        val speaksBefore = testSpeechEngine.speakCallCount
        val returned = runtime.returnToPrimaryDocument()

        assertTrue(returned)
        assertFalse(runtime.isEphemeralActive)
        assertNull(runtime.currentSuspendedPrimaryContext)

        val activeDoc = sessionCoordinator.activeDocumentState.value
        assertFalse(activeDoc.isEphemeral)
        assertEquals(primary.id, activeDoc.documentId)
        assertFalse(sessionCoordinator.readingSessionState.value.isReading)
        assertEquals(speaksBefore, testSpeechEngine.speakCallCount)
    }

    // 10. Restored primary document has exact same position
    @Test
    fun test10_restoredPrimaryPreservesPosition() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.startReading()
        sessionCoordinator.advanceReading() // Position at index 1

        val posBefore = readingEngine.currentPosition.value
        assertEquals(1, posBefore?.segmentIndex)

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()

        runtime.returnToPrimaryDocument()

        val posAfter = readingEngine.currentPosition.value
        assertEquals(1, posAfter?.segmentIndex)
        assertEquals(posBefore?.segmentId, posAfter?.segmentId)
    }

    // 11. Progress repository for primary document remains intact
    @Test
    fun test11_primaryProgressIntactThroughoutEphemeralSession() = runBlocking {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.startReading()
        runtime.saveCurrentProgress(isCompleted = false)

        val savedProgBefore = testProgressRepository.loadProgress(primary.id)
        assertNotNull(savedProgBefore)

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)
        runtime.startReading()
        runtime.stopReading()

        val savedProgDuring = testProgressRepository.loadProgress(primary.id)
        assertEquals(savedProgBefore?.segmentIndex, savedProgDuring?.segmentIndex)

        runtime.returnToPrimaryDocument()
        val savedProgAfter = testProgressRepository.loadProgress(primary.id)
        assertEquals(savedProgBefore?.segmentIndex, savedProgAfter?.segmentIndex)
    }

    // 12. If primary was stopped when suspended, it remains stopped upon restoration
    @Test
    fun test12_primaryStoppedRemainsStopped() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.stopReading()

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)

        runtime.returnToPrimaryDocument()
        assertTrue(sessionCoordinator.readingSessionState.value.isStopped || sessionCoordinator.readingSessionState.value.isIdle)
        assertFalse(sessionCoordinator.readingSessionState.value.isReading)
    }

    // 13. Discarding ephemeral context restores primary document
    @Test
    fun test13_discardEphemeralRestoresPrimary() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)

        val discarded = runtime.discardEphemeralContext()
        assertTrue(discarded)
        assertEquals(primary.id, sessionCoordinator.activeDocumentState.value.documentId)
    }

    // 14. Opening a normal document clears suspended context and ephemeral context
    @Test
    fun test14_openingNormalDocumentClearsSuspendedAndEphemeral() {
        val primary1 = createPrimaryDoc(id = "doc1", title = "Doc 1")
        val token1 = sessionCoordinator.startLoading(primary1.title)
        sessionCoordinator.onDocumentLoaded(token1, primary1, primary1.title)

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)
        assertNotNull(runtime.currentSuspendedPrimaryContext)

        runtime.onOpeningNormalDocument()
        assertNull(runtime.currentSuspendedPrimaryContext)
        assertFalse(runtime.isEphemeralActive)

        val primary2 = createPrimaryDoc(id = "doc2", title = "Doc 2")
        val token2 = sessionCoordinator.startLoading(primary2.title)
        sessionCoordinator.onDocumentLoaded(token2, primary2, primary2.title)

        assertEquals("doc2", sessionCoordinator.activeDocumentState.value.documentId)
        assertFalse(sessionCoordinator.activeDocumentState.value.hasSuspendedPrimary)
    }

    // 15. Loading new ephemeral document while an ephemeral document is active preserves original suspended primary
    @Test
    fun test15_multipleEphemeralsPreserveOriginalSuspendedPrimary() {
        val primary = createPrimaryDoc(id = "primary", title = "Main Book")
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)

        val eph1 = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("Eph 1 text")))
        runtime.loadEphemeralDocument(eph1, displayName = "Eph 1")
        val originalSuspended = runtime.currentSuspendedPrimaryContext
        assertNotNull(originalSuspended)
        assertEquals("primary", originalSuspended?.document?.id)

        val eph2 = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("Eph 2 text")))
        runtime.loadEphemeralDocument(eph2, displayName = "Eph 2")

        val currentSuspended = runtime.currentSuspendedPrimaryContext
        assertEquals("primary", currentSuspended?.document?.id)
        assertEquals("Main Book", currentSuspended?.displayName)
    }

    // 16. If no primary document was active when ephemeral loaded, suspended context is null
    @Test
    fun test16_noPrimaryDocumentResultsInNullSuspendedContext() {
        assertFalse(sessionCoordinator.hasActiveDocument)

        val ephemeralDoc = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(ephemeralDoc)

        assertTrue(runtime.isEphemeralActive)
        assertNull(runtime.currentSuspendedPrimaryContext)
        assertFalse(sessionCoordinator.activeDocumentState.value.hasSuspendedPrimary)

        // returnToPrimaryDocument clears document cleanly and returns false
        val returned = runtime.returnToPrimaryDocument()
        assertFalse(returned)
        assertFalse(sessionCoordinator.hasActiveDocument)
    }

    // 17. Session generation counter increments on each switch
    @Test
    fun test17_sessionGenerationIncrements() {
        val gen0 = runtime.currentSessionGeneration
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)

        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)
        val gen1 = runtime.currentSessionGeneration
        assertTrue(gen1 > gen0)

        runtime.returnToPrimaryDocument()
        val gen2 = runtime.currentSessionGeneration
        assertTrue(gen2 > gen1)
    }

    // 18. PDF primary document suspension captures position without viewport
    @Test
    fun test18_pdfSuspensionCapturesPositionOnly() {
        val pdfDoc = createPrimaryDoc(id = "pdf_doc_1", title = "Report.pdf", sourceType = ReadingDocumentSourceType.PDF)
        val token = sessionCoordinator.startLoading(pdfDoc.title)
        sessionCoordinator.onDocumentLoaded(token, pdfDoc, pdfDoc.title)
        val pos = ReadingPosition(
            documentId = pdfDoc.id,
            sectionId = "pdf_doc_1_sec_0",
            segmentId = "pdf_doc_1_seg_2",
            segmentIndex = 2
        )
        sessionCoordinator.setPosition(pos)

        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)

        val suspended = runtime.currentSuspendedPrimaryContext
        assertNotNull(suspended)
        assertEquals(2, suspended?.savedPosition?.segmentIndex)
        assertEquals(ReadingDocumentSourceType.PDF, suspended?.document?.metadata?.sourceType)

        runtime.returnToPrimaryDocument()
        assertEquals(2, readingEngine.currentPosition.value?.segmentIndex)
    }

    // 19. Ephemeral restart reading restarts from sentence 0 without writing progress
    @Test
    fun test19_ephemeralRestartReadingFromBeginning() = runBlocking {
        val eph = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("Sent A", "Sent B")))
        runtime.loadEphemeralDocument(eph)
        runtime.startReading()
        sessionCoordinator.advanceReading()
        assertEquals(1, readingEngine.currentPosition.value?.segmentIndex)

        runtime.restartReadingFromBeginning()
        assertEquals(0, readingEngine.currentPosition.value?.segmentIndex)
        assertTrue(testProgressRepository.getAllProgress().isEmpty())
    }

    // 20. End-to-end cycle: Primary -> Read -> Advance -> Ephemeral -> Read -> Return -> Resume Primary
    @Test
    fun test20_endToEndCycle() = runBlocking {
        // 1. Load Primary TXT
        val primary = createPrimaryDoc(segmentCount = 4)
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)

        // 2. Start reading primary and advance to sentence 1
        runtime.startReading()
        val seg0 = readingEngine.currentSegment.value
        assertEquals("primary_doc_1_seg_0", seg0?.id)

        val activeSessionId1 = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting(seg0!!.id, activeSessionId1)
        assertEquals(1, readingEngine.currentPosition.value?.segmentIndex)
        assertEquals("Primary sentence 1 of War and Peace.", testSpeechEngine.lastSpokenText)

        // 3. User switches to another app and taps bubble: Ephemeral snapshot acquired
        val snapshot = createSnapshot(
            packageName = "org.wikipedia",
            textList = listOf("Wiki snippet 1", "Wiki snippet 2")
        )
        val acquirer = MockAcquirer { snapshot }
        val req = CrossAppAcquisitionRequest(targetPackageName = "org.wikipedia")
        val acqResult = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, req, appLabel = "Wikipedia")
        assertTrue(acqResult is com.readme.app.accessibility.AcquisitionResult.Success)

        val success = acqResult as com.readme.app.accessibility.AcquisitionResult.Success
        runtime.loadEphemeralDocument(
            document = success.document,
            displayName = success.document.metadata.title,
            sourcePackageName = success.snapshot.sourcePackageName,
            sourceAppLabel = "Wikipedia",
            snapshotIdentity = success.snapshot.capturedAt
        )

        // Verify state during ephemeral
        assertTrue(runtime.isEphemeralActive)
        assertEquals("Text from Wikipedia", sessionCoordinator.activeDocumentState.value.displayName)
        assertTrue(sessionCoordinator.activeDocumentState.value.hasSuspendedPrimary)
        assertEquals("War and Peace", sessionCoordinator.activeDocumentState.value.suspendedPrimaryTitle)

        // 4. Start reading ephemeral
        runtime.startReading()
        assertEquals("Wiki snippet 1", testSpeechEngine.lastSpokenText)

        // Complete Wiki snippet 1 -> Wiki snippet 2
        val activeSessionId2 = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting(success.document.allSegments()[0].id, activeSessionId2)
        assertEquals("Wiki snippet 2", testSpeechEngine.lastSpokenText)

        // Complete Wiki snippet 2 -> completed
        runtime.handleSegmentCompletedForTesting(success.document.allSegments()[1].id, activeSessionId2)
        assertTrue(sessionCoordinator.readingSessionState.value.isCompleted)

        // Verify NO progress was written for ephemeral
        assertNull(testProgressRepository.loadProgress(success.document.id))

        // 5. User returns to ReadMe
        val restored = runtime.returnToPrimaryDocument()
        assertTrue(restored)
        assertFalse(runtime.isEphemeralActive)
        assertEquals("primary_doc_1", sessionCoordinator.activeDocumentState.value.documentId)
        assertEquals(1, readingEngine.currentPosition.value?.segmentIndex)
        assertFalse(sessionCoordinator.readingSessionState.value.isReading)

        // 6. Resume reading primary from sentence 1
        val resumedSeg = runtime.startReading()
        assertNotNull(resumedSeg)
        assertEquals("Primary sentence 1 of War and Peace.", resumedSeg?.text)
        assertTrue(sessionCoordinator.readingSessionState.value.isReading)
    }

    // 21. Loading ephemeral when primary was completed suspends primary in completed state
    @Test
    fun test21_loadingEphemeralWhenPrimaryCompleted() {
        val primary = createPrimaryDoc(segmentCount = 1)
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)
        runtime.startReading()
        runtime.handleSegmentCompletedForTesting(primary.allSegments()[0].id, runtime.currentSessionId)
        assertTrue(sessionCoordinator.readingSessionState.value.isCompleted)

        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)
        val suspended = runtime.currentSuspendedPrimaryContext
        assertNotNull(suspended)
        assertEquals(ReadingSessionState.Completed, suspended?.priorSessionState)

        runtime.returnToPrimaryDocument()
        assertEquals(primary.id, sessionCoordinator.activeDocumentState.value.documentId)
    }

    // 22. CrossAppAcquisitionCoordinator with empty snapshot returns NoTextAvailable
    @Test
    fun test22_acquisitionEmptySnapshot() {
        val emptySnapshot = createSnapshot(textList = emptyList())
        val acquirer = MockAcquirer { emptySnapshot }
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, CrossAppAcquisitionRequest())
        assertTrue(result is com.readme.app.accessibility.AcquisitionResult.NoTextAvailable)
    }

    // 23. CrossAppAcquisitionCoordinator with ReadMe self-package returns ReadMeSelfIgnored
    @Test
    fun test23_acquisitionReadMeSelfIgnored() {
        val snapshot = createSnapshot(packageName = com.readme.app.accessibility.CrossAppTextExtractor.README_PACKAGE)
        val acquirer = MockAcquirer { snapshot }
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, CrossAppAcquisitionRequest())
        assertTrue(result is com.readme.app.accessibility.AcquisitionResult.ReadMeSelfIgnored)
    }

    // 24. CrossAppAcquisitionCoordinator with null acquirer returns ServiceNotConnected
    @Test
    fun test24_acquisitionNullAcquirer() {
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(null, CrossAppAcquisitionRequest())
        assertTrue(result is com.readme.app.accessibility.AcquisitionResult.ServiceNotConnected)
    }

    // 25. CrossAppAcquisitionCoordinator with stale app switch returns StaleAppSwitch
    @Test
    fun test25_acquisitionStaleAppSwitch() {
        val snapshot = createSnapshot(packageName = "com.other.app")
        val acquirer = MockAcquirer { snapshot }
        val req = CrossAppAcquisitionRequest(targetPackageName = "com.expected.app")
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, req)
        assertTrue(result is com.readme.app.accessibility.AcquisitionResult.StaleAppSwitch)
    }

    // 26. CrossAppAcquisitionCoordinator with stale generation returns StaleGeneration
    @Test
    fun test26_acquisitionStaleGeneration() {
        val snapshot = createSnapshot(generation = 1L)
        val acquirer = MockAcquirer { snapshot }
        val req = CrossAppAcquisitionRequest(expectedGeneration = 5L)
        val result = CrossAppAcquisitionCoordinator.executeAcquisition(acquirer, req)
        assertTrue(result is com.readme.app.accessibility.AcquisitionResult.StaleGeneration)
    }

    // 27. Speech error during ephemeral reading does not corrupt suspended primary
    @Test
    fun test27_speechErrorDuringEphemeralDoesNotCorruptSuspendedPrimary() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)

        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)
        runtime.startReading()

        runtime.handleSegmentErrorForTesting("any_seg", runtime.currentSessionId, 99)
        assertTrue(sessionCoordinator.readingSessionState.value.isError)
        assertNotNull(runtime.currentSuspendedPrimaryContext)

        val restored = runtime.returnToPrimaryDocument()
        assertTrue(restored)
        assertEquals(primary.id, sessionCoordinator.activeDocumentState.value.documentId)
    }

    // 28. Ephemeral with multiple blocks produces continuous sequential segments
    @Test
    fun test28_ephemeralMultipleBlocksSequential() {
        val snapshot = createSnapshot(textList = listOf("Line 1", "Line 2", "Line 3"))
        val doc = CrossAppDocumentParser.parse(snapshot)
        assertEquals(3, doc.allSegments().size)
        assertEquals("Line 1", doc.allSegments()[0].text)
        assertEquals("Line 2", doc.allSegments()[1].text)
        assertEquals("Line 3", doc.allSegments()[2].text)
    }

    // 29. Title formatting: with app label -> "Text from $label"
    @Test
    fun test29_titleFormattingWithAppLabel() {
        val snapshot = createSnapshot()
        val doc = CrossAppDocumentParser.parse(snapshot, appLabel = "Reddit")
        assertEquals("Text from Reddit", doc.metadata.title)
    }

    // 30. Title formatting: with snapshot title and null label -> snapshot title
    @Test
    fun test30_titleFormattingWithSnapshotTitle() {
        val snapshot = createSnapshot(title = "Breaking News")
        val doc = CrossAppDocumentParser.parse(snapshot, appLabel = null)
        assertEquals("Breaking News", doc.metadata.title)
    }

    // 31. Title formatting: with null label and null title -> "Text from $lastSegment"
    @Test
    fun test31_titleFormattingFallbackPackage() {
        val snapshot = createSnapshot(packageName = "com.medium.reader", title = null)
        val doc = CrossAppDocumentParser.parse(snapshot, appLabel = null)
        assertEquals("Text from Reader", doc.metadata.title)
    }

    // 32. Active document state hasSuspendedPrimary is false when no primary was loaded
    @Test
    fun test32_hasSuspendedPrimaryFalseWhenNoPrimary() {
        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)
        assertFalse(sessionCoordinator.activeDocumentState.value.hasSuspendedPrimary)
    }

    // 33. Active document state suspendedPrimaryTitle is null when no primary was loaded
    @Test
    fun test33_suspendedPrimaryTitleNullWhenNoPrimary() {
        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)
        assertNull(sessionCoordinator.activeDocumentState.value.suspendedPrimaryTitle)
    }

    // 34. Discarding ephemeral context when no primary was suspended clears document
    @Test
    fun test34_discardEphemeralWhenNoPrimaryClears() {
        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)
        val discarded = runtime.discardEphemeralContext()
        assertFalse(discarded)
        assertFalse(sessionCoordinator.hasActiveDocument)
    }

    // 35. Starting and stopping ephemeral preserves ephemeral in-memory position
    @Test
    fun test35_startStopEphemeralPreservesPosition() {
        val eph = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("A", "B", "C")))
        runtime.loadEphemeralDocument(eph)
        runtime.startReading()
        sessionCoordinator.advanceReading() // sentence index 1
        assertEquals(1, readingEngine.currentPosition.value?.segmentIndex)

        runtime.stopReading()
        assertEquals(1, readingEngine.currentPosition.value?.segmentIndex)

        runtime.startReading()
        assertEquals(1, readingEngine.currentPosition.value?.segmentIndex)
    }

    // 36. Advancing ephemeral to end completes and retains hasSuspendedPrimary
    @Test
    fun test36_ephemeralCompletedRetainsSuspendedFlag() {
        val primary = createPrimaryDoc()
        val token = sessionCoordinator.startLoading(primary.title)
        sessionCoordinator.onDocumentLoaded(token, primary, primary.title)

        val eph = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("Single sentence.")))
        runtime.loadEphemeralDocument(eph)
        runtime.startReading()

        runtime.handleSegmentCompletedForTesting(eph.allSegments()[0].id, runtime.currentSessionId)
        assertTrue(sessionCoordinator.readingSessionState.value.isCompleted)
        assertTrue(sessionCoordinator.activeDocumentState.value.hasSuspendedPrimary)
    }

    // 37. Multiple stops and resumes on ephemeral do not trigger progress repository writes
    @Test
    fun test37_multipleStopsResumesOnEphemeralNoProgressWrites() = runBlocking {
        val eph = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("A", "B", "C")))
        runtime.loadEphemeralDocument(eph)
        for (i in 0..3) {
            runtime.startReading()
            runtime.stopReading()
        }
        assertTrue(testProgressRepository.getAllProgress().isEmpty())
    }

    // 38. PDF primary document suspended and restored preserves PDF sourceType
    @Test
    fun test38_pdfSourceTypePreserved() {
        val pdf = createPrimaryDoc(id = "doc.pdf", title = "Doc.pdf", sourceType = ReadingDocumentSourceType.PDF)
        val token = sessionCoordinator.startLoading(pdf.title)
        sessionCoordinator.onDocumentLoaded(token, pdf, pdf.title)
        assertEquals(ReadingDocumentSourceType.PDF, sessionCoordinator.activeDocumentState.value.sourceType)

        val eph = CrossAppDocumentParser.parse(createSnapshot())
        runtime.loadEphemeralDocument(eph)
        assertEquals(ReadingDocumentSourceType.OTHER, sessionCoordinator.activeDocumentState.value.sourceType)

        runtime.returnToPrimaryDocument()
        assertEquals(ReadingDocumentSourceType.PDF, sessionCoordinator.activeDocumentState.value.sourceType)
    }

    // 39. Session generation strictly increases on each transition
    @Test
    fun test39_generationIncreasesStrictly() {
        val g0 = runtime.currentSessionGeneration
        runtime.loadEphemeralDocument(CrossAppDocumentParser.parse(createSnapshot()))
        val g1 = runtime.currentSessionGeneration
        assertTrue(g1 > g0)

        runtime.loadEphemeralDocument(CrossAppDocumentParser.parse(createSnapshot()))
        val g2 = runtime.currentSessionGeneration
        assertTrue(g2 > g1)

        runtime.returnToPrimaryDocument()
        val g3 = runtime.currentSessionGeneration
        assertTrue(g3 > g2)
    }

    // 40. Full cycle with PDF primary document
    @Test
    fun test40_fullCyclePdfPrimary() = runBlocking {
        val pdfDoc = createPrimaryDoc(id = "guide.pdf", title = "Guide.pdf", sourceType = ReadingDocumentSourceType.PDF, segmentCount = 6)
        val token = sessionCoordinator.startLoading(pdfDoc.title)
        sessionCoordinator.onDocumentLoaded(token, pdfDoc, pdfDoc.title)

        // Advance to segment 3
        runtime.startReading()
        sessionCoordinator.advanceReading()
        sessionCoordinator.advanceReading()
        sessionCoordinator.advanceReading()
        assertEquals(3, readingEngine.currentPosition.value?.segmentIndex)

        // Trigger ephemeral snapshot
        val ephDoc = CrossAppDocumentParser.parse(createSnapshot(textList = listOf("Overlay 1", "Overlay 2")))
        runtime.loadEphemeralDocument(
            document = ephDoc,
            displayName = "Text from Overlay",
            sourcePackageName = "com.overlay.app",
            sourceAppLabel = "Overlay"
        )
        assertTrue(runtime.isEphemeralActive)
        assertEquals(3, runtime.currentSuspendedPrimaryContext?.savedPosition?.segmentIndex)

        // Read and complete ephemeral
        runtime.startReading()
        val sId = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting(ephDoc.allSegments()[0].id, sId)
        runtime.handleSegmentCompletedForTesting(ephDoc.allSegments()[1].id, sId)
        assertTrue(sessionCoordinator.readingSessionState.value.isCompleted)

        // Return to PDF
        val returned = runtime.returnToPrimaryDocument()
        assertTrue(returned)
        assertFalse(runtime.isEphemeralActive)
        assertEquals("guide.pdf", sessionCoordinator.activeDocumentState.value.documentId)
        assertEquals(3, readingEngine.currentPosition.value?.segmentIndex)

        // Resume PDF reading from segment 3
        val seg = runtime.startReading()
        assertNotNull(seg)
        assertEquals("guide.pdf_seg_3", seg?.id)
        assertTrue(sessionCoordinator.readingSessionState.value.isReading)
    }
}
