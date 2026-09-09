package com.readme.app.reading.progress

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingProgressTest {

    private fun createSampleDoc(
        id: String = "doc_1",
        title: String = "Test Doc",
        sectionCount: Int = 2,
        segmentsPerSection: Int = 3,
        sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.TXT
    ): ReadingDocument {
        val sections = (0 until sectionCount).map { secIdx ->
            val sectionId = "sec_$secIdx"
            val segments = (0 until segmentsPerSection).map { segIdx ->
                val globalIdx = secIdx * segmentsPerSection + segIdx
                ReadingSegment(
                    id = "seg_${secIdx}_$segIdx",
                    text = "Sentence $globalIdx."
                )
            }
            ReadingSection(id = sectionId, title = "Section $secIdx", segments = segments)
        }
        return ReadingDocument(
            id = id,
            title = title,
            sourceType = sourceType,
            sections = sections
        )
    }

    @Test
    fun test01_serialization_and_deserialization_roundtrip() {
        val progress = ReadingProgress(
            documentId = "pdf:sample.pdf",
            sectionId = "sec_1",
            segmentId = "seg_1_2",
            segmentIndex = 5,
            sourceType = ReadingDocumentSourceType.PDF,
            isCompleted = false,
            lastUpdated = 123456789L,
            version = 1
        )

        val json = progress.toJson()
        val parsed = ReadingProgress.fromJson(json)

        assertNotNull(parsed)
        assertEquals("pdf:sample.pdf", parsed?.documentId)
        assertEquals("sec_1", parsed?.sectionId)
        assertEquals("seg_1_2", parsed?.segmentId)
        assertEquals(5, parsed?.segmentIndex)
        assertEquals(ReadingDocumentSourceType.PDF, parsed?.sourceType)
        assertFalse(parsed?.isCompleted ?: true)
        assertEquals(123456789L, parsed?.lastUpdated)
        assertEquals(1, parsed?.version)
    }

    @Test
    fun test02_serialization_handles_special_characters_safely() {
        val progress = ReadingProgress(
            documentId = "epub:my_book \"special\" / path & name.epub",
            sectionId = "sec:special/\"1\"",
            segmentId = "seg:id \"with quotes\"",
            segmentIndex = 2,
            sourceType = ReadingDocumentSourceType.EPUB,
            isCompleted = true,
            lastUpdated = 987654321L
        )

        val json = progress.toJson()
        val parsed = ReadingProgress.fromJson(json)

        assertNotNull(parsed)
        assertEquals(progress.documentId, parsed?.documentId)
        assertEquals(progress.sectionId, parsed?.sectionId)
        assertEquals(progress.segmentId, parsed?.segmentId)
        assertEquals(progress.segmentIndex, parsed?.segmentIndex)
        assertEquals(progress.sourceType, parsed?.sourceType)
        assertTrue(parsed?.isCompleted ?: false)
    }

    @Test
    fun test03_toReadingPosition_convertsAccurately() {
        val progress = ReadingProgress(
            documentId = "doc_abc",
            sectionId = "sec_0",
            segmentId = "seg_0_1",
            segmentIndex = 1
        )

        val pos = progress.toReadingPosition()
        assertEquals("doc_abc", pos.documentId)
        assertEquals("sec_0", pos.sectionId)
        assertEquals("seg_0_1", pos.segmentId)
        assertEquals(1, pos.segmentIndex)
    }

    @Test
    fun test04_fromPosition_convertsAccurately() {
        val position = ReadingPosition(
            documentId = "doc_xyz",
            sectionId = "sec_3",
            segmentId = "seg_3_4",
            segmentIndex = 7
        )

        val progress = ReadingProgress.fromPosition(
            position = position,
            sourceType = ReadingDocumentSourceType.EPUB,
            isCompleted = false
        )

        assertEquals("doc_xyz", progress.documentId)
        assertEquals("sec_3", progress.sectionId)
        assertEquals("seg_3_4", progress.segmentId)
        assertEquals(7, progress.segmentIndex)
        assertEquals(ReadingDocumentSourceType.EPUB, progress.sourceType)
        assertFalse(progress.isCompleted)
    }

    @Test
    fun test05_validator_accepts_valid_progress() {
        val doc = createSampleDoc("doc_1", sectionCount = 2, segmentsPerSection = 3)
        val progress = ReadingProgress(
            documentId = "doc_1",
            sectionId = "sec_0",
            segmentId = "seg_0_2",
            segmentIndex = 2
        )

        assertTrue(ReadingProgressValidator.validate(progress, doc))
    }

    @Test
    fun test06_validator_rejects_mismatched_document_id() {
        val doc = createSampleDoc("doc_1")
        val progress = ReadingProgress(
            documentId = "foreign_doc",
            sectionId = "sec_0",
            segmentId = "seg_0_0",
            segmentIndex = 0
        )

        assertFalse(ReadingProgressValidator.validate(progress, doc))
    }

    @Test
    fun test07_validator_rejects_missing_section() {
        val doc = createSampleDoc("doc_1", sectionCount = 2)
        val progress = ReadingProgress(
            documentId = "doc_1",
            sectionId = "non_existent_sec",
            segmentId = "seg_0_0",
            segmentIndex = 0
        )

        assertFalse(ReadingProgressValidator.validate(progress, doc))
    }

    @Test
    fun test08_validator_rejects_missing_segment() {
        val doc = createSampleDoc("doc_1", sectionCount = 2, segmentsPerSection = 2)
        val progress = ReadingProgress(
            documentId = "doc_1",
            sectionId = "sec_0",
            segmentId = "non_existent_seg",
            segmentIndex = 0
        )

        assertFalse(ReadingProgressValidator.validate(progress, doc))
    }

    @Test
    fun test09_validator_rejects_out_of_bounds_index() {
        val doc = createSampleDoc("doc_1", sectionCount = 1, segmentsPerSection = 2) // total 2 segments: indices 0, 1
        val progress = ReadingProgress(
            documentId = "doc_1",
            sectionId = "sec_0",
            segmentId = "seg_0_1",
            segmentIndex = 99 // out of bounds
        )

        assertFalse(ReadingProgressValidator.validate(progress, doc))
    }

    @Test
    fun test10_validator_rejects_nulls_and_empty_ids() {
        assertFalse(ReadingProgressValidator.validate(null, null))
        val doc = createSampleDoc("")
        val progress = ReadingProgress("", "sec", "seg", 0)
        assertFalse(ReadingProgressValidator.validate(progress, doc))
    }
}
