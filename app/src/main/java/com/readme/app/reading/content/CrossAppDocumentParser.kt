package com.readme.app.reading.content

import com.readme.app.accessibility.CrossAppTextSnapshot
import com.readme.app.accessibility.CrossAppWindowTarget
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment

/**
 * Converts ephemeral [CrossAppTextSnapshot]s into structured [ReadingDocument]s.
 *
 * Preserves the architectural boundary:
 * - Reuses existing [TxtDocumentParser.splitIntoSentences] for speech-friendly sentence segmentation.
 * - Uses [ReadingDocumentSourceType.OTHER] to mark external ephemeral reading content.
 * - Generates deterministic segment IDs scoped by session generation and package identity.
 */
object CrossAppDocumentParser {

    fun parse(snapshot: CrossAppTextSnapshot?, appLabel: String? = null): ReadingDocument {
        if (snapshot == null || snapshot.isEmpty) {
            val emptyId = "crossapp_empty_${System.currentTimeMillis()}"
            return ReadingDocument(
                id = emptyId,
                metadata = ReadingDocumentMetadata(
                    title = "External Content",
                    author = null,
                    sourceType = ReadingDocumentSourceType.OTHER
                ),
                sections = emptyList()
            )
        }

        val docId = "crossapp_${snapshot.sourcePackageName}_${snapshot.capturedAt}_gen${snapshot.generation}"
        val docTitle = when {
            !appLabel.isNullOrBlank() -> "Text from $appLabel"
            !snapshot.title.isNullOrBlank() -> snapshot.title.trim()
            else -> formatSourceTitle(snapshot.sourcePackageName)
        }

        val metadata = ReadingDocumentMetadata(
            title = docTitle,
            author = null,
            sourceType = ReadingDocumentSourceType.OTHER
        )

        val segments = mutableListOf<ReadingSegment>()
        var segmentCounter = 0

        for (block in snapshot.blocks) {
            val cleanBlockText = block.text.trim()
            if (cleanBlockText.isBlank()) continue

            // Split block into speech-friendly sentence units using existing TxtDocumentParser logic
            val sentences = TxtDocumentParser.splitIntoSentences(cleanBlockText)
            for (sentence in sentences) {
                val trimmedSentence = sentence.trim()
                if (trimmedSentence.isNotBlank()) {
                    segments.add(
                        ReadingSegment(
                            id = "${docId}_seg_$segmentCounter",
                            text = trimmedSentence
                        )
                    )
                    segmentCounter++
                }
            }
        }

        val section = ReadingSection(
            id = "${docId}_sec_0",
            title = docTitle,
            segments = segments
        )

        return ReadingDocument(
            id = docId,
            metadata = metadata,
            sections = if (segments.isEmpty()) emptyList() else listOf(section)
        )
    }

    /**
     * Converts recognized OCR text from an external window snapshot into a structured [ReadingDocument].
     *
     * Reuses [TxtDocumentParser.splitIntoSentences] for speech-friendly sentence segmentation.
     * Uses [ReadingDocumentSourceType.OTHER] and an ephemeral "crossapp_ocr_" document ID prefix.
     */
    fun parseOcrText(
        target: CrossAppWindowTarget,
        ocrText: String,
        appLabel: String? = null
    ): ReadingDocument {
        val cleanText = ocrText.trim()
        val docId = "crossapp_ocr_${target.packageName}_win${target.windowId}_gen${target.generation}_${target.requestId}"
        val docTitle = when {
            !appLabel.isNullOrBlank() -> "Text from $appLabel"
            else -> formatSourceTitle(target.packageName)
        }

        val metadata = ReadingDocumentMetadata(
            title = docTitle,
            author = null,
            sourceType = ReadingDocumentSourceType.OTHER
        )

        if (cleanText.isBlank()) {
            return ReadingDocument(
                id = docId,
                metadata = metadata,
                sections = emptyList()
            )
        }

        val sentences = TxtDocumentParser.splitIntoSentences(cleanText)
        val segments = mutableListOf<ReadingSegment>()
        var segmentCounter = 0

        for (sentence in sentences) {
            val trimmedSentence = sentence.trim()
            if (trimmedSentence.isNotBlank()) {
                segments.add(
                    ReadingSegment(
                        id = "${docId}_seg_$segmentCounter",
                        text = trimmedSentence
                    )
                )
                segmentCounter++
            }
        }

        val section = ReadingSection(
            id = "${docId}_sec_0",
            title = docTitle,
            segments = segments
        )

        return ReadingDocument(
            id = docId,
            metadata = metadata,
            sections = if (segments.isEmpty()) emptyList() else listOf(section)
        )
    }

    private fun formatSourceTitle(packageName: String): String {
        val lastSegment = packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return if (lastSegment.isNotBlank()) {
            "Text from $lastSegment"
        } else {
            "External App Content"
        }
    }
}
