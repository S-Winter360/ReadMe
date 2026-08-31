package com.readme.app.reading.content

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment

/**
 * Pure Kotlin parser that converts raw plain text into structured [ReadingDocument]
 * containing ordered [ReadingSection]s and [ReadingSegment]s.
 */
object TxtDocumentParser {

    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")

    /**
     * Parses [rawText] with a given [title] into a [ReadingDocument].
     */
    fun parse(title: String, rawText: String, documentId: String = "txt_doc_${System.currentTimeMillis()}"): ReadingDocument {
        val cleanTitle = title.trim().ifBlank { "Untitled Document" }
        
        if (rawText.isBlank()) {
            return ReadingDocument(
                id = documentId,
                title = cleanTitle,
                sections = emptyList()
            )
        }

        val normalizedText = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val rawParagraphs = normalizedText.split(Regex("\n\\s*\n+"))

        val segments = mutableListOf<ReadingSegment>()
        var segmentCounter = 0

        for (paragraph in rawParagraphs) {
            val cleanParagraph = paragraph.trim()
            if (cleanParagraph.isBlank()) continue

            // Split paragraph into sentences or lines
            val rawSentences = cleanParagraph.split(SENTENCE_SPLIT_REGEX)
            for (sentence in rawSentences) {
                val trimmed = sentence.trim().replace(Regex("\\s+"), " ")
                if (trimmed.isNotBlank()) {
                    segments.add(
                        ReadingSegment(
                            id = "${documentId}_seg_$segmentCounter",
                            text = trimmed
                        )
                    )
                    segmentCounter++
                }
            }
        }

        val section = ReadingSection(
            id = "${documentId}_sec_0",
            title = cleanTitle,
            segments = segments
        )

        return ReadingDocument(
            id = documentId,
            title = cleanTitle,
            sections = if (segments.isEmpty()) emptyList() else listOf(section)
        )
    }
}
