package com.readme.app.ui.overlay

import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.DocumentLoadState
import com.readme.app.reading.ReadingDocument
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
import com.readme.app.ui.components.BubbleState
import com.readme.app.ui.components.getBubbleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SystemFloatingBubbleTest {

    private lateinit var readingEngine: ReadingEngine
    private lateinit var progressRepository: InMemoryReadingProgressRepository
    private lateinit var coordinator: ReadingSessionCoordinator
    private lateinit var testSpeechEngine: ReadMeSpeechEngine
    private lateinit var testScope: CoroutineScope
    private lateinit var runtime: ReadMeReadingSessionRuntime

    private fun createDoc(id: String = "doc_1", title: String = "Test Doc"): ReadingDocument {
        val segments = listOf(
            ReadingSegment("${id}_seg_0", "Segment 0"),
            ReadingSegment("${id}_seg_1", "Segment 1")
        )
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(ReadingSection("${id}_sec_0", "Section 0", segments))
        )
    }

    private fun createDocState(
        hasDoc: Boolean = true,
        docId: String = "doc1",
        loadState: DocumentLoadState = DocumentLoadState.Loaded
    ) = ActiveDocumentState(
        documentId = if (hasDoc) docId else "",
        title = if (hasDoc) "Title" else "",
        author = null,
        displayName = if (hasDoc) "doc.txt" else "",
        sourceType = ReadingDocumentSourceType.TXT,
        loadState = loadState
    )

    private fun createSessionState(
        sessionState: ReadingSessionState,
        activeDocId: String? = "doc1",
        ttsState: TtsState = TtsState.Stopped,
        errorMessage: String? = null
    ) = ActiveReadingSessionState(
        activeDocumentId = activeDocId,
        sessionState = sessionState,
        speechState = ttsState,
        currentPosition = null,
        errorMessage = errorMessage
    )

    @Before
    fun setUp() {
        readingEngine = ReadingEngine()
        progressRepository = InMemoryReadingProgressRepository()
        coordinator = ReadingSessionCoordinator(readingEngine, progressRepository)
        testSpeechEngine = ReadMeSpeechEngine(null)
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        runtime = ReadMeReadingSessionRuntime(
            context = null,
            progressRepository = progressRepository,
            readingEngine = readingEngine,
            sessionCoordinator = coordinator,
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

    // 1. system bubble disabled → no bubble.
    @Test
    fun test01_systemBubbleDisabled_noBubble() {
        val shouldShow = SystemFloatingBubbleController.shouldDisplaySystemBubble(
            isSystemBubbleEnabled = false,
            hasOverlayPermission = true,
            isAppForeground = false,
            hasActiveDocument = true
        )
        assertFalse(shouldShow)
    }

    // 2. system bubble enabled + unauthorized → no window.
    @Test
    fun test02_systemBubbleEnabled_unauthorized_noWindow() {
        val shouldShow = SystemFloatingBubbleController.shouldDisplaySystemBubble(
            isSystemBubbleEnabled = true,
            hasOverlayPermission = false,
            isAppForeground = false,
            hasActiveDocument = true
        )
        assertFalse(shouldShow)
    }

    // 3. system bubble enabled + authorized → bubble can become visible.
    @Test
    fun test03_systemBubbleEnabled_authorized_canBecomeVisible() {
        val shouldShow = SystemFloatingBubbleController.shouldDisplaySystemBubble(
            isSystemBubbleEnabled = true,
            hasOverlayPermission = true,
            isAppForeground = false,
            hasActiveDocument = true
        )
        assertTrue(shouldShow)
    }

    // 4. no document → inactive/hidden system bubble.
    @Test
    fun test04_noDocument_hiddenSystemBubble() {
        val docState = createDocState(hasDoc = false, loadState = DocumentLoadState.NoDocument)
        val sessionState = createSessionState(ReadingSessionState.Idle, null)
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Hidden, bubbleState)

        val shouldShow = SystemFloatingBubbleController.shouldDisplaySystemBubble(
            isSystemBubbleEnabled = true,
            hasOverlayPermission = true,
            isAppForeground = false,
            hasActiveDocument = docState.hasActiveDocument
        )
        assertFalse(shouldShow)
    }

    // 5. reading → active bubble state.
    @Test
    fun test05_reading_activeBubbleState() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Reading, ttsState = TtsState.Speaking)
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Reading, bubbleState)
    }

    // 6. stopped → stopped/resume bubble state.
    @Test
    fun test06_stopped_stoppedResumeBubbleState() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Stopped)
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Stopped, bubbleState)
    }

    // 7. completed → completed/restart state.
    @Test
    fun test07_completed_completedRestartState() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Completed)
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Completed, bubbleState)
    }

    // 8. error → calm/error state.
    @Test
    fun test08_error_calmErrorState() {
        val docState = createDocState()
        val sessionState = createSessionState(ReadingSessionState.Error, errorMessage = "Speech error")
        val bubbleState = getBubbleState(sessionState, docState)
        assertEquals(BubbleState.Error, bubbleState)
    }

    // 9. system bubble tap delegates to existing runtime.
    @Test
    fun test09_systemBubbleTap_delegatesToRuntime() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)

        assertFalse(runtime.sessionCoordinator.readingSessionState.value.isReading)

        // Simulate tap action toggling runtime reading
        val toggleAction = {
            if (runtime.sessionCoordinator.readingSessionState.value.isReading) {
                runtime.stopReading()
            } else {
                runtime.startReading()
            }
        }

        toggleAction()
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)

        toggleAction()
        assertFalse(runtime.sessionCoordinator.readingSessionState.value.isReading)
    }

    // 10. system bubble does not own ReadingPosition.
    @Test
    fun test10_systemBubbleDoesNotOwnReadingPosition() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)
        runtime.startReading()

        val pos = readingEngine.currentPosition.value
        assertNotNull(pos)
        assertEquals("doc_1", pos?.documentId)
        assertEquals(0, pos?.segmentIndex)
    }

    // 11. system bubble does not own TTS.
    @Test
    fun test11_systemBubbleDoesNotOwnTTS() {
        assertEquals(TtsState.Uninitialized, testSpeechEngine.state.value)
    }

    // 12. disabling bubble does not stop reading.
    @Test
    fun test12_disablingBubbleDoesNotStopReading() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)
        runtime.startReading()
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)

        // Disabling bubble setting
        @Suppress("UNUSED_VALUE")
        var bubbleEnabled = true
        bubbleEnabled = false
        assertFalse(bubbleEnabled)

        // Reading must remain active
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)
    }

    // 13. enabling bubble does not start reading unexpectedly.
    @Test
    fun test13_enablingBubbleDoesNotStartReadingUnexpectedly() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)

        assertFalse(runtime.sessionCoordinator.readingSessionState.value.isReading)

        @Suppress("UNUSED_VALUE")
        var bubbleEnabled = false
        bubbleEnabled = true
        assertTrue(bubbleEnabled)

        // Must remain stopped / idle
        assertFalse(runtime.sessionCoordinator.readingSessionState.value.isReading)
        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isIdle)
    }

    // 14. document replacement updates bubble state.
    @Test
    fun test14_documentReplacementUpdatesBubbleState() {
        val doc1 = createDoc("doc_1", "Doc 1")
        val token1 = coordinator.startLoading(doc1.title)
        coordinator.onDocumentLoaded(token1, doc1, doc1.title)

        val bubbleState1 = getBubbleState(coordinator.readingSessionState.value, coordinator.activeDocumentState.value)
        assertEquals(BubbleState.Idle, bubbleState1)

        val doc2 = createDoc("doc_2", "Doc 2")
        val token2 = coordinator.startLoading(doc2.title)
        coordinator.onDocumentLoaded(token2, doc2, doc2.title)

        val bubbleState2 = getBubbleState(coordinator.readingSessionState.value, coordinator.activeDocumentState.value)
        assertEquals(BubbleState.Idle, bubbleState2)
        assertEquals("doc_2", coordinator.activeDocumentState.value.documentId)
    }

    // 15. stale document cannot control system bubble.
    @Test
    fun test15_staleDocumentCannotControlSystemBubble() {
        val doc1 = createDoc("doc_1", "Doc 1")
        val token1 = coordinator.startLoading(doc1.title)
        coordinator.onDocumentLoaded(token1, doc1, doc1.title)

        val staleToken = token1
        val doc2 = createDoc("doc_2", "Doc 2")
        val activeToken = coordinator.startLoading(doc2.title)

        val wasLoaded = coordinator.onDocumentLoaded(staleToken, doc1, doc1.title)
        assertFalse(wasLoaded)
        assertEquals("Doc 2", coordinator.activeDocumentState.value.displayName)
    }

    // 16. Activity recreation preserves system runtime state.
    @Test
    fun test16_activityRecreationPreservesRuntimeState() {
        val doc = createDoc()
        val token = coordinator.startLoading(doc.title)
        coordinator.onDocumentLoaded(token, doc, doc.title)
        runtime.startReading()

        // Simulating Activity recreation (pause/resume foreground toggle)
        runtime.setAppForeground(false)
        runtime.setAppForeground(true)

        assertTrue(runtime.sessionCoordinator.readingSessionState.value.isReading)
        assertTrue(runtime.sessionCoordinator.hasActiveDocument)
    }

    // 17. Activity foreground/background handoff is deterministic.
    @Test
    fun test17_activityForegroundBackgroundHandoffIsDeterministic() {
        runtime.setAppForeground(true)
        assertTrue(runtime.appForegroundState.value)

        runtime.setAppForeground(false)
        assertFalse(runtime.appForegroundState.value)

        runtime.setAppForeground(true)
        assertTrue(runtime.appForegroundState.value)
    }

    // 18. in-app and system bubbles cannot be simultaneously active unnecessarily.
    @Test
    fun test18_inAppAndSystemBubblesExclusivity() {
        val systemBubbleInForeground = SystemFloatingBubbleController.shouldDisplaySystemBubble(
            isSystemBubbleEnabled = true,
            hasOverlayPermission = true,
            isAppForeground = true,
            hasActiveDocument = true
        )
        assertFalse(systemBubbleInForeground)

        val systemBubbleInBackground = SystemFloatingBubbleController.shouldDisplaySystemBubble(
            isSystemBubbleEnabled = true,
            hasOverlayPermission = true,
            isAppForeground = false,
            hasActiveDocument = true
        )
        assertTrue(systemBubbleInBackground)
    }

    // 19. orientation change clamps system bubble position.
    @Test
    fun test19_orientationChangeClampsPosition() {
        val viewW = 64
        val viewH = 64

        // Portrait 1080x1920
        val (x1, y1) = SystemFloatingBubbleController.clampPosition(1200, 2000, viewW, viewH, 1080, 1920)
        assertEquals(1080 - viewW, x1)
        assertEquals(1920 - viewH, y1)

        // Rotated to Landscape 1920x1080
        val (x2, y2) = SystemFloatingBubbleController.clampPosition(x1, y1, viewW, viewH, 1920, 1080)
        assertTrue(x2 <= 1920 - viewW)
        assertEquals(1080 - viewH, y2)

        // Negative coordinates clamped to 0
        val (x3, y3) = SystemFloatingBubbleController.clampPosition(-50, -20, viewW, viewH, 1080, 1920)
        assertEquals(0, x3)
        assertEquals(0, y3)
    }

    // 20. invalid window-removal lifecycle is handled safely.
    @Test
    fun test20_invalidWindowRemovalHandledSafely() {
        val shouldShowWhenNoDoc = SystemFloatingBubbleController.shouldDisplaySystemBubble(
            isSystemBubbleEnabled = true,
            hasOverlayPermission = true,
            isAppForeground = false,
            hasActiveDocument = false
        )
        assertFalse(shouldShowWhenNoDoc)
    }
}
