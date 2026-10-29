package com.readme.app.reading.service

import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.progress.InMemoryReadingProgressRepository
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

class Phase4ANotificationAndPlaybackTest {

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
    }

    private fun createDoc(
        id: String = "doc_test",
        title: String = "Notification Test Title",
        count: Int = 4
    ): ReadingDocument {
        val segments = (0 until count).map { i ->
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
    }

    @After
    fun tearDown() {
        runtime.shutdown()
        testScope.cancel()
    }

    @Test
    fun testReadingEnginePreviousAndSkipToNext() {
        val doc = createDoc(count = 3)
        readingEngine.loadDocument(doc)
        assertEquals(ReadingSessionState.Idle, readingEngine.readingState.value)

        // Start session
        val first = readingEngine.startFromBeginning()
        assertNotNull(first)
        assertEquals(0, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 0 of Notification Test Title.", first?.text)

        // Skip to next
        val second = readingEngine.skipToNext()
        assertEquals(1, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 1 of Notification Test Title.", second?.text)

        // Previous
        val backToFirst = readingEngine.previous()
        assertEquals(0, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 0 of Notification Test Title.", backToFirst?.text)

        // Previous again at beginning stays on segment 0
        val stayFirst = readingEngine.previous()
        assertEquals(0, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 0 of Notification Test Title.", stayFirst?.text)
    }

    @Test
    fun testCoordinatorPreviousAndSkipToNext() {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        sessionCoordinator.startReading()
        assertEquals(0, readingEngine.currentSegmentIndex.value)

        val next = sessionCoordinator.skipToNextReading()
        assertEquals(1, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 1 of Notification Test Title.", next?.text)

        val prev = sessionCoordinator.previousReading()
        assertEquals(0, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 0 of Notification Test Title.", prev?.text)
    }

    @Test
    fun testRuntimeCurrentSentenceSyncOnStartAndProgress() = runBlocking {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        assertNull(runtime.currentReadingSentence.value)

        // Start reading
        runtime.startReading()
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)
        assertEquals("Sentence 0 of Notification Test Title.", testSpeechEngine.lastSpokenText)

        // Complete segment 0 -> advances to segment 1
        val seg0Id = doc.sections[0].segments[0].id
        runtime.handleSegmentCompletedForTesting(seg0Id, runtime.currentSessionId)

        assertEquals("Sentence 1 of Notification Test Title.", runtime.currentReadingSentence.value)
        assertEquals("Sentence 1 of Notification Test Title.", testSpeechEngine.lastSpokenText)
    }

    @Test
    fun testRuntimePausePreservesCurrentSentence() = runBlocking {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)

        // Pause
        runtime.pauseReading()
        assertFalse(runtime.readingSessionState.value.isReading)
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)

        // Resume
        runtime.resumeReading()
        assertTrue(runtime.readingSessionState.value.isReading)
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)
    }

    @Test
    fun testRuntimeStopClearsCurrentSentence() = runBlocking {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)

        runtime.stopReading()
        assertFalse(runtime.readingSessionState.value.isReading)
        assertNull(runtime.currentReadingSentence.value)
    }

    @Test
    fun testRuntimeSkipToNextWhilePlaying() = runBlocking {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)

        // Skip to next while playing
        val next = runtime.skipToNext()
        assertNotNull(next)
        assertEquals("Sentence 1 of Notification Test Title.", next?.text)
        assertEquals("Sentence 1 of Notification Test Title.", runtime.currentReadingSentence.value)
        assertEquals("Sentence 1 of Notification Test Title.", testSpeechEngine.lastSpokenText)
        assertTrue(runtime.readingSessionState.value.isReading)
    }

    @Test
    fun testRuntimeSkipToPreviousWhilePlaying() = runBlocking {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        runtime.skipToNext() // on segment 1
        assertEquals("Sentence 1 of Notification Test Title.", runtime.currentReadingSentence.value)

        // Skip back to previous
        val prev = runtime.skipToPrevious()
        assertNotNull(prev)
        assertEquals("Sentence 0 of Notification Test Title.", prev?.text)
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)
        assertEquals("Sentence 0 of Notification Test Title.", testSpeechEngine.lastSpokenText)
        assertTrue(runtime.readingSessionState.value.isReading)
    }

    @Test
    fun testRuntimeSkipWhilePausedUpdatesPositionWithoutSpeaking() = runBlocking {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        runtime.pauseReading()
        assertFalse(runtime.readingSessionState.value.isReading)
        assertEquals(0, readingEngine.currentSegmentIndex.value)

        testSpeechEngine.lastSpokenText = null

        // Skip to next while paused
        val next = runtime.skipToNext()
        assertEquals(1, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 1 of Notification Test Title.", next?.text)
        assertEquals("Sentence 1 of Notification Test Title.", runtime.currentReadingSentence.value)
        // Must remain paused without auto-speaking
        assertNull(testSpeechEngine.lastSpokenText)
        assertFalse(runtime.readingSessionState.value.isReading)

        // Skip back to previous while paused
        val prev = runtime.skipToPrevious()
        assertEquals(0, readingEngine.currentSegmentIndex.value)
        assertEquals("Sentence 0 of Notification Test Title.", prev?.text)
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)
        assertNull(testSpeechEngine.lastSpokenText)
        assertFalse(runtime.readingSessionState.value.isReading)
    }

    @Test
    fun testStaleSessionCompletionIgnoredAfterSkip() = runBlocking {
        val doc = createDoc(count = 3)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        val oldSessionId = runtime.currentSessionId

        // User taps Next
        runtime.skipToNext()
        val newSessionId = runtime.currentSessionId
        assertTrue(newSessionId != oldSessionId)
        assertEquals(1, readingEngine.currentSegmentIndex.value)

        // Delayed callback from old session arrives
        runtime.handleSegmentCompletedForTesting(doc.sections[0].segments[0].id, oldSessionId)

        // Segment index must NOT have advanced to 2
        assertEquals(1, readingEngine.currentSegmentIndex.value)
    }

    @Test
    fun testDocumentCompletionClearsCurrentSentence() = runBlocking {
        val doc = createDoc(count = 1)
        val token = sessionCoordinator.startLoading(doc.title)
        sessionCoordinator.onDocumentLoaded(token, doc, doc.title)

        runtime.startReading()
        assertEquals("Sentence 0 of Notification Test Title.", runtime.currentReadingSentence.value)

        // Final segment completes
        runtime.handleSegmentCompletedForTesting(doc.sections[0].segments[0].id, runtime.currentSessionId)

        assertTrue(runtime.readingSessionState.value.isCompleted)
        assertNull(runtime.currentReadingSentence.value)
    }

    @Test
    fun testServiceActionConstants() {
        assertEquals("com.readme.app.action.START_READING", ReadMeReadingService.ACTION_START_READING)
        assertEquals("com.readme.app.action.STOP_READING", ReadMeReadingService.ACTION_STOP_READING)
        assertEquals("com.readme.app.action.PAUSE_READING", ReadMeReadingService.ACTION_PAUSE_READING)
        assertEquals("com.readme.app.action.RESUME_READING", ReadMeReadingService.ACTION_RESUME_READING)
        assertEquals("com.readme.app.action.PREVIOUS_SEGMENT", ReadMeReadingService.ACTION_PREVIOUS_SEGMENT)
        assertEquals("com.readme.app.action.NEXT_SEGMENT", ReadMeReadingService.ACTION_NEXT_SEGMENT)
        assertEquals("com.readme.app.action.SYNC_SERVICE", ReadMeReadingService.ACTION_SYNC_SERVICE)
    }
}
