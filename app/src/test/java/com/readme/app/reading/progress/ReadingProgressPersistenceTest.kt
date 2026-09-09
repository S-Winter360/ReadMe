package com.readme.app.reading.progress

import com.readme.app.reading.ReadingDocumentSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingProgressPersistenceTest {

    @Test
    fun test01_save_and_load_progress() = runBlocking {
        val repo = InMemoryReadingProgressRepository()
        val progress = ReadingProgress(
            documentId = "doc_123",
            sectionId = "sec_1",
            segmentId = "seg_5",
            segmentIndex = 5,
            sourceType = ReadingDocumentSourceType.TXT
        )

        repo.saveProgress(progress)
        val loaded = repo.loadProgress("doc_123")

        assertNotNull(loaded)
        assertEquals("doc_123", loaded?.documentId)
        assertEquals(5, loaded?.segmentIndex)
    }

    @Test
    fun test02_load_non_existent_returns_null() = runBlocking {
        val repo = InMemoryReadingProgressRepository()
        val loaded = repo.loadProgress("non_existent")
        assertNull(loaded)
    }

    @Test
    fun test03_clear_progress_removes_entry() = runBlocking {
        val repo = InMemoryReadingProgressRepository()
        val progress = ReadingProgress(
            documentId = "doc_to_clear",
            sectionId = "sec_0",
            segmentId = "seg_0",
            segmentIndex = 0
        )

        repo.saveProgress(progress)
        assertNotNull(repo.loadProgress("doc_to_clear"))

        repo.clearProgress("doc_to_clear")
        assertNull(repo.loadProgress("doc_to_clear"))
    }

    @Test
    fun test04_distinct_documents_do_not_overwrite_each_other() = runBlocking {
        val repo = InMemoryReadingProgressRepository()
        val progressA = ReadingProgress("doc_A", "sec_0", "seg_0", 1)
        val progressB = ReadingProgress("doc_B", "sec_0", "seg_0", 4)

        repo.saveProgress(progressA)
        repo.saveProgress(progressB)

        assertEquals(1, repo.loadProgress("doc_A")?.segmentIndex)
        assertEquals(4, repo.loadProgress("doc_B")?.segmentIndex)
    }

    @Test
    fun test05_preference_key_is_deterministic_and_safe() {
        val key1 = PersistentReadingProgressRepository.preferenceKeyFor("content://docs/sample 1.pdf")
        val key2 = PersistentReadingProgressRepository.preferenceKeyFor("content://docs/sample 1.pdf")
        val key3 = PersistentReadingProgressRepository.preferenceKeyFor("content://docs/sample 2.pdf")

        assertEquals(key1.name, key2.name)
        assertTrue(key1.name != key3.name)
        assertTrue(key1.name.startsWith("prog_"))
        // Hex chars only after prefix
        val hexPart = key1.name.removePrefix("prog_")
        assertTrue(hexPart.matches(Regex("[0-9a-f]{64}")))
    }
}
