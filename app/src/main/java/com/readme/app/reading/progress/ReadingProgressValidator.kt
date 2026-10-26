package com.readme.app.reading.progress

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingPosition

/**
 * Validates persisted [ReadingProgress] against an in-memory [ReadingDocument]
 * before restoring reading positions.
 *
 * Rules:
 * 1. Progress and document must not be null or have empty IDs.
 * 2. Progress documentId must match document.id.
 * 3. Progress sectionId must exist in document.sections.
 * 4. Progress segmentId must exist within that section.
 * 5. Progress segmentIndex must be within bounds for the document's segments.
 * 6. If content drifted, the segment must still be resolvable within the document.
 */
object ReadingProgressValidator {

    fun validate(progress: ReadingProgress?, document: ReadingDocument?): Boolean {
        if (progress == null || document == null) return false
        if (progress.documentId.isBlank() || document.id.isBlank()) return false
        if (progress.documentId != document.id) return false

        val section = document.sections.firstOrNull { it.id == progress.sectionId } ?: return false
        val segment = section.segments.firstOrNull { it.id == progress.segmentId } ?: return false

        val allSegments = document.allSegments()
        if (allSegments.isEmpty()) return false
        if (progress.segmentIndex !in allSegments.indices) return false

        val segmentAtIndex = allSegments[progress.segmentIndex]
        if (segmentAtIndex.id != segment.id) {
            // Index drifted or content changed; verify the segment is still present in the document
            if (allSegments.none { it.id == segment.id }) return false
        }

        return true
    }

    /**
     * Resolves a safe, range-clamped [ReadingPosition] for [progress] against [document].
     * Clamps the segmentIndex to [0, allSegments.size - 1] and resolves valid document position.
     */
    fun clampAndResolvePosition(progress: ReadingProgress?, document: ReadingDocument?): ReadingPosition? {
        if (progress == null || document == null) return null
        if (progress.documentId.isBlank() || document.id.isBlank()) return null
        if (progress.documentId != document.id) return null

        val allSegments = document.allSegments()
        if (allSegments.isEmpty()) return null

        // If strictly valid, use exact position
        if (validate(progress, document)) {
            val clampedIndex = progress.segmentIndex.coerceIn(0, allSegments.lastIndex)
            return document.positionForIndex(clampedIndex) ?: progress.toReadingPosition()
        }

        // Section or segment must belong to the document to allow safe clamping
        val section = document.sections.firstOrNull { it.id == progress.sectionId }
        val segment = section?.segments?.firstOrNull { it.id == progress.segmentId }
            ?: allSegments.firstOrNull { it.id == progress.segmentId }

        if (segment != null) {
            val segmentIdx = allSegments.indexOfFirst { it.id == segment.id }
            if (segmentIdx >= 0) {
                return document.positionForIndex(segmentIdx)
            }
        }

        // If section exists, clamp within that section
        if (section != null && section.segments.isNotEmpty()) {
            val clampedIndex = progress.segmentIndex.coerceIn(0, allSegments.lastIndex)
            return document.positionForIndex(clampedIndex)
        }

        return null
    }
}
