package com.readme.app.reading.progress

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingEngine
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.ReadingSessionCoordinator
import com.readme.app.reading.ReadingSessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadingSessionProgressIntegrationTest {

    private lateinit var readingEngine: ReadingEngine
    private lateinit var progressRepo: InMemoryReadingProgressRepository
    private lateinit var coordinator: ReadingSessionCoordinator

    private fun createSampleDoc(
        id: String = "doc_1",
        title: String = "Test Doc",
        segmentCount: Int = 5
    ): ReadingDocument {
        val segments = (0 until segmentCount).map { i ->
            ReadingSegment(
                id = "seg_$i",
                text = "Sentence $i."
            )
        }
        val section = ReadingSection(id = "sec_0", title = "Section 0", segments = segments)
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = ReadingDocumentSourceType.TXT,
            sections = listOf(section)
        )
    }

    @Before
    fun setup() {
        readingEngine = ReadingEngine()
        progressRepo = InMemoryReadingProgressRepository()
        coordinator = ReadingSessionCoordinator(readingEngine, progressRepo)
    }

    @Test
    fun test01_restoreProgress_setsPosition_whenValid() = runBlocking {
        val doc = createSampleDoc("doc_1", segmentCount = 6)
        val progress = ReadingProgress(
            documentId = "doc_1",
            sectionId = "sec_0",
            segmentId = "seg_3",
            segmentIndex = 3
        )
        progressRepo.saveProgress(progress)

        val token = coordinator.startLoading("doc_1.txt")
        coordinator.onDocumentLoaded(token, doc, "doc_1.txt")

        val restored = coordinator.restoreProgress(doc)
        assertNotNull(restored)
        assertEquals(3, restored?.segmentIndex)

        // Verifying in-memory reading engine position
        val currentPos = coordinator.readingSessionState.value.currentPosition
        assertNotNull(currentPos)
        assertEquals("doc_1", currentPos?.documentId)
        assertEquals("seg_3", currentPos?.segmentId)
        assertEquals(3, currentPos?.segmentIndex)
    }

    @Test
    fun test02_startReading_resumes_from_restored_position() = runBlocking {
        val doc = createSampleDoc("doc_1", segmentCount = 6)
        val progress = ReadingProgress(
            documentId = "doc_1",
            sectionId = "sec_0",
            segmentId = "seg_3",
            segmentIndex = 3
        )
        progressRepo.saveProgress(progress)

        val token = coordinator.startLoading("doc_1.txt")
        coordinator.onDocumentLoaded(token, doc, "doc_1.txt")
        coordinator.restoreProgress(doc)

        // Starting reading should speak segment 3, NOT segment 0
        val segmentToSpeak = coordinator.startReading()
        assertNotNull(segmentToSpeak)
        assertEquals("seg_3", segmentToSpeak?.id)
        assertEquals("Sentence 3.", segmentToSpeak?.text)
        assertEquals(ReadingSessionState.Reading, coordinator.readingSessionState.value.sessionState)
    }

    @Test
    fun test03_startReading_without_saved_progress_starts_from_beginning() = runBlocking {
        val doc = createSampleDoc("doc_fresh", segmentCount = 4)
        val token = coordinator.startLoading("fresh.txt")
        coordinator.onDocumentLoaded(token, doc, "fresh.txt")

        val segmentToSpeak = coordinator.startReading()
        assertNotNull(segmentToSpeak)
        assertEquals("seg_0", segmentToSpeak?.id)
        assertEquals("Sentence 0.", segmentToSpeak?.text)
    }

    @Test
    fun test04_completed_progress_is_not_restored_as_active_position() = runBlocking {
        val doc = createSampleDoc("doc_completed", segmentCount = 4)
        val progress = ReadingProgress(
            documentId = "doc_completed",
            sectionId = "sec_0",
            segmentId = "seg_3",
            segmentIndex = 3,
            isCompleted = true
        )
        progressRepo.saveProgress(progress)

        val token = coordinator.startLoading("completed.txt")
        coordinator.onDocumentLoaded(token, doc, "completed.txt")

        val restored = coordinator.restoreProgress(doc)
        assertNotNull(restored)
        assertTrue(restored?.isCompleted == true)

        // Because it was completed, active position must remain null
        assertNull(coordinator.readingSessionState.value.currentPosition)

        // Pressing start reading should start from beginning
        val segmentToSpeak = coordinator.startReading()
        assertEquals("seg_0", segmentToSpeak?.id)
    }

    @Test
    fun test05_saveCurrentProgress_persists_active_position() = runBlocking {
        val doc = createSampleDoc("doc_save", segmentCount = 5)
        val token = coordinator.startLoading("doc_save.txt")
        coordinator.onDocumentLoaded(token, doc, "doc_save.txt")

        coordinator.startReading() // seg 0
        coordinator.advanceReading() // seg 1
        coordinator.advanceReading() // seg 2

        val saved = coordinator.saveCurrentProgress(isCompleted = false)
        assertNotNull(saved)
        assertEquals(2, saved?.segmentIndex)

        val persisted = progressRepo.loadProgress("doc_save")
        assertNotNull(persisted)
        assertEquals("seg_2", persisted?.segmentId)
        assertEquals(2, persisted?.segmentIndex)
    }

    @Test
    fun test06_document_replacement_prevents_leaking_progress() = runBlocking {
        val docA = createSampleDoc("doc_A", segmentCount = 5)
        val docB = createSampleDoc("doc_B", segmentCount = 5)

        val progressA = ReadingProgress("doc_A", "sec_0", "seg_3", 3)
        progressRepo.saveProgress(progressA)

        // Load docA and restore
        val tokenA = coordinator.startLoading("docA.txt")
        coordinator.onDocumentLoaded(tokenA, docA, "docA.txt")
        coordinator.restoreProgress(docA)
        assertEquals(3, coordinator.readingSessionState.value.currentPosition?.segmentIndex)

        // Now load docB which has no progress
        val tokenB = coordinator.startLoading("docB.txt")
        coordinator.onDocumentLoaded(tokenB, docB, "docB.txt")
        val restoredB = coordinator.restoreProgress(docB)
        assertNull(restoredB)
        assertNull(coordinator.readingSessionState.value.currentPosition)

        // Reading docB starts from segment 0
        val segB = coordinator.startReading()
        assertEquals("seg_0", segB?.id)
    }

    @Test
    fun test07_invalid_progress_is_rejected_and_starts_from_beginning() = runBlocking {
        val doc = createSampleDoc("doc_modified", segmentCount = 2) // only segments 0 and 1
        val staleProgress = ReadingProgress(
            documentId = "doc_modified",
            sectionId = "sec_0",
            segmentId = "seg_99", // non existent segment
            segmentIndex = 99
        )
        progressRepo.saveProgress(staleProgress)

        val token = coordinator.startLoading("doc_modified.txt")
        coordinator.onDocumentLoaded(token, doc, "doc_modified.txt")
        val restored = coordinator.restoreProgress(doc)
        assertNull(restored)
        assertNull(coordinator.readingSessionState.value.currentPosition)

        // Reading starts from beginning
        val seg = coordinator.startReading()
        assertEquals("seg_0", seg?.id)
    }
}
