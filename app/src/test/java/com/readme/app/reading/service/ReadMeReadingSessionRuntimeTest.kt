package com.readme.app.reading.service

import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.progress.InMemoryReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgress
import com.readme.app.reading.progress.SavedProgressState
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadMeReadingSessionRuntimeTest {

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

    private fun createSampleDoc(
        id: String = "test_doc_1",
        title: String = "Test Book",
        segmentCount: Int = 3
    ): ReadingDocument {
        val segments = (0 until segmentCount).map { i ->
            ReadingSegment(id = "${id}_seg_$i", text = "Sentence $i of $title.")
        }
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(
                ReadingSection(id = "${id}_sec_0", title = "Section 0", segments = segments)
            )
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

    @Test
    fun test01_initialStateIsIdleAndNoDocument() {
        assertFalse(runtime.sessionCoordinator.hasActiveDocument)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isIdle)
        assertFalse(runtime.sessionCoordinator.readingSessionState.value.isReading)
        assertEquals(SavedProgressState.None, runtime.savedProgressState.value)
    }

    @Test
    fun test02_startReadingWithoutDocumentDoesNothing() {
        runtime.startReading()
        assertFalse(runtime.sessionCoordinator.readingSessionState.value.isReading)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isIdle)
        assertEquals(0, testSpeechEngine.stopCallCount)
    }

    @Test
    fun test03_documentLoadedAndReadingStarted() = runBlocking {
        val doc = createSampleDoc()
        val token = runtime.sessionCoordinator.startLoading(doc.title)
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        assertTrue(runtime.sessionCoordinator.hasActiveDocument)

        runtime.startReading()

        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)
        assertEquals("test_doc_1_seg_0", testSpeechEngine.lastSpokenSegmentId)
        assertEquals("Sentence 0 of Test Book.", testSpeechEngine.lastSpokenText)

        val saved = testProgressRepository.loadProgress(doc.id)
        assertNotNull(saved)
        assertEquals(0, saved?.segmentIndex)
    }

    @Test
    fun test04_stopReadingHaltsSessionAndSavesProgress() = runBlocking {
        val doc = createSampleDoc()
        val token = runtime.sessionCoordinator.startLoading(doc.title)
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)

        runtime.stopReading()
        assertFalse(runtime.sessionCoordinator.readingSessionState.value.isReading)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isStopped)
        assertTrue(testSpeechEngine.stopCallCount > 0)

        val saved = testProgressRepository.loadProgress(doc.id)
        assertNotNull(saved)
        assertEquals(0, saved?.segmentIndex)
    }

    @Test
    fun test05_speechSegmentCompletionAdvancesReading() = runBlocking {
        val doc = createSampleDoc(segmentCount = 3)
        val token = runtime.sessionCoordinator.startLoading(doc.title)
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        assertEquals(0, runtime.readingEngine.currentPosition.value?.segmentIndex)
        assertEquals("test_doc_1_seg_0", testSpeechEngine.lastSpokenSegmentId)

        val sessionId = runtime.currentSessionId

        // Simulate segment 0 completion
        runtime.handleSegmentCompletedForTesting("test_doc_1_seg_0", sessionId)

        assertEquals(1, runtime.readingEngine.currentPosition.value?.segmentIndex)
        assertEquals("test_doc_1_seg_1", testSpeechEngine.lastSpokenSegmentId)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)

        // Simulate segment 1 completion
        runtime.handleSegmentCompletedForTesting("test_doc_1_seg_1", sessionId)
        assertEquals(2, runtime.readingEngine.currentPosition.value?.segmentIndex)
        assertEquals("test_doc_1_seg_2", testSpeechEngine.lastSpokenSegmentId)

        // Simulate final segment completion
        runtime.handleSegmentCompletedForTesting("test_doc_1_seg_2", sessionId)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isCompleted)
        val saved = testProgressRepository.loadProgress(doc.id)
        assertTrue(saved?.isCompleted == true)
    }

    @Test
    fun test06_restartReadingFromBeginningResetsToZero() = runBlocking {
        val doc = createSampleDoc(segmentCount = 3)
        val token = runtime.sessionCoordinator.startLoading(doc.title)
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        val sessionId = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting("test_doc_1_seg_0", sessionId)
        assertEquals(1, runtime.readingEngine.currentPosition.value?.segmentIndex)

        runtime.restartReadingFromBeginning()
        assertEquals(0, runtime.readingEngine.currentPosition.value?.segmentIndex)
        assertEquals("test_doc_1_seg_0", testSpeechEngine.lastSpokenSegmentId)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)
    }

    @Test
    fun test07_staleSegmentCallbacksIgnored() = runBlocking {
        val doc = createSampleDoc(segmentCount = 3)
        val token = runtime.sessionCoordinator.startLoading(doc.title)
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        val originalSessionId = runtime.currentSessionId

        // Stop session (resets activeSessionId)
        runtime.stopReading()

        // Call completion from old stale session
        runtime.handleSegmentCompletedForTesting("test_doc_1_seg_0", originalSessionId)

        // Must remain stopped, no advancement
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isStopped)
        assertEquals(0, runtime.readingEngine.currentPosition.value?.segmentIndex)
    }

    @Test
    fun test08_speechErrorTransitionsToErrorState() = runBlocking {
        val doc = createSampleDoc()
        val token = runtime.sessionCoordinator.startLoading(doc.title)
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)

        val sessionId = runtime.currentSessionId
        runtime.handleSegmentErrorForTesting("test_doc_1_seg_0", sessionId, 4)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isError)
    }

    @Test
    fun test09_playbackLifecycleListenersNotified() = runBlocking {
        val doc = createSampleDoc(segmentCount = 2)
        val token = runtime.sessionCoordinator.startLoading(doc.title)
        runtime.sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        var startedCount = 0
        var stoppedCount = 0
        var completedCount = 0
        var errorCount = 0

        val listener = object : ReadMeReadingSessionRuntime.PlaybackLifecycleListener {
            override fun onReadingStarted() { startedCount++ }
            override fun onReadingStopped() { stoppedCount++ }
            override fun onReadingCompleted() { completedCount++ }
            override fun onReadingError(message: String?) { errorCount++ }
        }

        runtime.addLifecycleListener(listener)

        runtime.startReading()
        assertEquals(1, startedCount)

        val sessionId = runtime.currentSessionId
        runtime.handleSegmentCompletedForTesting("test_doc_1_seg_0", sessionId)
        runtime.handleSegmentCompletedForTesting("test_doc_1_seg_1", sessionId)
        assertEquals(1, completedCount)

        runtime.stopReading()
        assertEquals(1, stoppedCount)

        runtime.removeLifecycleListener(listener)
    }
}
