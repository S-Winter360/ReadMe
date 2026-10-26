package com.readme.app.reading.progress

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
import com.readme.app.reading.service.ReadMeReadingSessionRuntime
import com.readme.app.speech.ReadMeSpeechEngine
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
 * Phase 1: Verification tests for persistent reading position restoration,
 * process death simulation, multi-document isolation, range clamping, and restart semantics.
 */
class ReadingProgressRestorationTest {

    private lateinit var progressRepo: InMemoryReadingProgressRepository
    private lateinit var testScope: CoroutineScope

    private fun createDoc(
        id: String,
        title: String,
        sectionCount: Int = 2,
        segmentsPerSection: Int = 3
    ): ReadingDocument {
        val sections = (0 until sectionCount).map { secIdx ->
            val sectionId = "${id}_sec_$secIdx"
            val segments = (0 until segmentsPerSection).map { segIdx ->
                val globalIdx = secIdx * segmentsPerSection + segIdx
                ReadingSegment(
                    id = "${id}_sec_${secIdx}_seg_$segIdx",
                    text = "Sentence $globalIdx of $title."
                )
            }
            ReadingSection(id = sectionId, title = "Section $secIdx", segments = segments)
        }
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = ReadingDocumentSourceType.TXT,
            sections = sections
        )
    }

    private fun createRuntime(
        repo: ReadingProgressRepository,
        engine: ReadingEngine = ReadingEngine()
    ): ReadMeReadingSessionRuntime {
        val coordinator = ReadingSessionCoordinator(engine, repo)
        val speechEngine = object : ReadMeSpeechEngine(context = null) {
            override fun speakSegment(
                segmentId: String,
                text: String,
                sessionId: Long,
                voiceId: String,
                speed: Float,
                pitch: Float,
                volume: Float
            ) {
                setStateForTesting(TtsState.Speaking)
            }
            override fun stop() {
                setStateForTesting(TtsState.Ready)
            }
        }
        return ReadMeReadingSessionRuntime(
            context = null,
            progressRepository = repo,
            readingEngine = engine,
            sessionCoordinator = coordinator,
            speechEngine = speechEngine,
            settingsRepository = null,
            runtimeScope = testScope,
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Before
    fun setup() {
        progressRepo = InMemoryReadingProgressRepository()
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        ReadMeReadingSessionRuntime.resetForTesting()
        testScope.cancel()
    }

    @Test
    fun test01_savingAndRestoringReadingPositionForSameDocument() = runBlocking {
        val runtime1 = createRuntime(progressRepo)
        val doc = createDoc("doc_novel", "Novel", sectionCount = 2, segmentsPerSection = 5)

        val token1 = runtime1.sessionCoordinator.startLoading("Novel")
        runtime1.sessionCoordinator.onDocumentLoaded(token1, doc, "Novel")

        // Start reading and advance through 3 segments
        runtime1.startReading()
        val sessionId = runtime1.currentSessionId
        runtime1.handleSegmentCompletedForTesting("doc_novel_sec_0_seg_0", sessionId)
        runtime1.handleSegmentCompletedForTesting("doc_novel_sec_0_seg_1", sessionId)
        runtime1.handleSegmentCompletedForTesting("doc_novel_sec_0_seg_2", sessionId)

        assertEquals(3, runtime1.readingEngine.currentPosition.value?.segmentIndex)

        // Stop session normally (e.g. user leaves or pauses)
        runtime1.stopReading()

        // Verify progress is saved in repository
        val saved = progressRepo.loadProgress("doc_novel")
        assertNotNull(saved)
        assertEquals(3, saved?.segmentIndex)
        assertEquals("doc_novel_sec_0_seg_3", saved?.segmentId)
        assertFalse(saved?.isCompleted ?: true)

        runtime1.shutdown()
    }

    @Test
    fun test02_reopeningDocumentAfterSimulatedAppRestartSurvivesProcessDeath() = runBlocking {
        val doc = createDoc("doc_process_death", "Book", sectionCount = 3, segmentsPerSection = 4)

        // Step 1: User reads to segment 5 in runtime instance 1
        val runtime1 = createRuntime(progressRepo)
        val token1 = runtime1.sessionCoordinator.startLoading("Book")
        runtime1.sessionCoordinator.onDocumentLoaded(token1, doc, "Book")

        runtime1.startReading()
        val sessionId = runtime1.currentSessionId
        for (i in 0 until 5) {
            val allSegs = doc.allSegments()
            runtime1.handleSegmentCompletedForTesting(allSegs[i].id, sessionId)
        }
        assertEquals(5, runtime1.readingEngine.currentPosition.value?.segmentIndex)

        // User stops reading
        runtime1.stopReading()
        // Simulate process death by shutting down runtime 1 completely
        runtime1.shutdown()
        ReadMeReadingSessionRuntime.resetForTesting()

        // Step 2: Relaunch app with fresh runtime instance 2 sharing same repository
        val runtime2 = createRuntime(progressRepo)
        val token2 = runtime2.sessionCoordinator.startLoading("Book")
        runtime2.sessionCoordinator.onDocumentLoaded(token2, doc, "Book")

        // Restore progress for document
        runtime2.restoreProgressIfAvailable(doc.id)

        // Verify state is Resumable and position is restored to segment 5
        val progressState = runtime2.savedProgressState.value
        assertTrue(progressState is SavedProgressState.Resumable)
        val restoredPos = runtime2.sessionCoordinator.readingSessionState.value.currentPosition
        assertNotNull(restoredPos)
        assertEquals(5, restoredPos?.segmentIndex)
        assertEquals(doc.allSegments()[5].id, restoredPos?.segmentId)

        // Verify resume reading speaks segment 5
        val nextSegment = runtime2.resumeReading()
        assertNotNull(nextSegment)
        assertEquals(doc.allSegments()[5].id, nextSegment?.id)
        assertEquals(ReadingSessionState.Reading, runtime2.readingSessionState.value.sessionState)

        runtime2.shutdown()
    }

    @Test
    fun test03_multiDocumentProgressSeparation() = runBlocking {
        val docA = createDoc("doc_A", "Book A", sectionCount = 1, segmentsPerSection = 5)
        val docB = createDoc("doc_B", "Book B", sectionCount = 1, segmentsPerSection = 5)

        val runtime = createRuntime(progressRepo)

        // Read Doc A to index 3
        val tokenA = runtime.sessionCoordinator.startLoading("Book A")
        runtime.sessionCoordinator.onDocumentLoaded(tokenA, docA, "Book A")
        runtime.startReading()
        val sessionA = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting("doc_A_sec_0_seg_0", sessionA)
        runtime.handleSegmentCompletedForTesting("doc_A_sec_0_seg_1", sessionA)
        runtime.handleSegmentCompletedForTesting("doc_A_sec_0_seg_2", sessionA)
        runtime.stopReading()

        // Switch to Doc B, read Doc B to index 1
        val tokenB = runtime.sessionCoordinator.startLoading("Book B")
        runtime.sessionCoordinator.onDocumentLoaded(tokenB, docB, "Book B")
        runtime.restoreProgressIfAvailable(docB.id)
        assertEquals(SavedProgressState.None, runtime.savedProgressState.value)

        runtime.startReading()
        val sessionB = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting("doc_B_sec_0_seg_0", sessionB)
        runtime.stopReading()

        // Verify repository stores each independently
        val progA = progressRepo.loadProgress("doc_A")
        val progB = progressRepo.loadProgress("doc_B")
        assertEquals(3, progA?.segmentIndex)
        assertEquals(1, progB?.segmentIndex)

        // Reopen Doc A and verify Doc A restores position 3
        val tokenA2 = runtime.sessionCoordinator.startLoading("Book A")
        runtime.sessionCoordinator.onDocumentLoaded(tokenA2, docA, "Book A")
        runtime.restoreProgressIfAvailable(docA.id)
        assertEquals(3, runtime.sessionCoordinator.readingSessionState.value.currentPosition?.segmentIndex)

        // Reopen Doc B and verify Doc B restores position 1
        val tokenB2 = runtime.sessionCoordinator.startLoading("Book B")
        runtime.sessionCoordinator.onDocumentLoaded(tokenB2, docB, "Book B")
        runtime.restoreProgressIfAvailable(docB.id)
        assertEquals(1, runtime.sessionCoordinator.readingSessionState.value.currentPosition?.segmentIndex)

        runtime.shutdown()
    }

    @Test
    fun test04_safeClampingOfOutOfBoundsOrDriftedProgress() = runBlocking {
        val doc = createDoc("doc_clamping", "Clamping Test", sectionCount = 1, segmentsPerSection = 4) // 0..3

        // Saved progress has segmentIndex = 99 (beyond document size 4)
        val outOfBoundsProgress = ReadingProgress(
            documentId = "doc_clamping",
            sectionId = "doc_clamping_sec_0",
            segmentId = "doc_clamping_sec_0_seg_99",
            segmentIndex = 99
        )
        progressRepo.saveProgress(outOfBoundsProgress)

        val runtime = createRuntime(progressRepo)
        val token = runtime.sessionCoordinator.startLoading("Clamping Test")
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, "Clamping Test")

        // Restore progress should clamp to index 3 (last segment)
        runtime.restoreProgressIfAvailable(doc.id)

        val pos = runtime.sessionCoordinator.readingSessionState.value.currentPosition
        assertNotNull(pos)
        assertEquals(3, pos?.segmentIndex)
        assertEquals("doc_clamping_sec_0_seg_3", pos?.segmentId)

        runtime.shutdown()
    }

    @Test
    fun test05_normalSessionTerminationDoesNotEraseProgress() = runBlocking {
        val doc = createDoc("doc_termination", "Termination Test", sectionCount = 1, segmentsPerSection = 5)
        val runtime = createRuntime(progressRepo)

        val token = runtime.sessionCoordinator.startLoading("Termination Test")
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, "Termination Test")

        runtime.startReading()
        val sessionId = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting("doc_termination_sec_0_seg_0", sessionId)
        runtime.handleSegmentCompletedForTesting("doc_termination_sec_0_seg_1", sessionId)

        assertEquals(2, runtime.readingEngine.currentPosition.value?.segmentIndex)

        // Stop session normally
        runtime.stopReading()

        // Calling stop multiple times or stopping session must NOT clear progress
        runtime.stopReading()
        runtime.sessionCoordinator.stopReading()

        val saved = progressRepo.loadProgress("doc_termination")
        assertNotNull(saved)
        assertEquals(2, saved?.segmentIndex)

        runtime.shutdown()
    }

    @Test
    fun test06_explicitRestartClearsProgressAndRestartsFromBeginning() = runBlocking {
        val doc = createDoc("doc_restart", "Restart Test", sectionCount = 1, segmentsPerSection = 4)
        val runtime = createRuntime(progressRepo)

        val token = runtime.sessionCoordinator.startLoading("Restart Test")
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, "Restart Test")

        runtime.startReading()
        val sessionId = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting("doc_restart_sec_0_seg_0", sessionId)
        runtime.handleSegmentCompletedForTesting("doc_restart_sec_0_seg_1", sessionId)
        runtime.stopReading()

        assertEquals(2, progressRepo.loadProgress("doc_restart")?.segmentIndex)

        // User explicitly triggers restart reading from beginning
        runtime.restartReadingFromBeginning()

        assertEquals(0, runtime.readingEngine.currentPosition.value?.segmentIndex)
        assertEquals("doc_restart_sec_0_seg_0", runtime.readingEngine.currentSegment.value?.id)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)

        runtime.shutdown()
    }

    @Test
    fun test07_missingProgressDefaultsCleanlyToBeginning() = runBlocking {
        val doc = createDoc("doc_unseen", "Unseen Doc", sectionCount = 1, segmentsPerSection = 3)
        val runtime = createRuntime(progressRepo)

        val token = runtime.sessionCoordinator.startLoading("Unseen Doc")
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, "Unseen Doc")

        // No progress exists in repo
        runtime.restoreProgressIfAvailable(doc.id)

        assertEquals(SavedProgressState.None, runtime.savedProgressState.value)
        assertNull(runtime.sessionCoordinator.readingSessionState.value.currentPosition)

        // Start reading starts cleanly at segment 0
        val segment = runtime.startReading()
        assertNotNull(segment)
        assertEquals("doc_unseen_sec_0_seg_0", segment?.id)
        assertEquals(0, runtime.readingEngine.currentPosition.value?.segmentIndex)

        runtime.shutdown()
    }
}
