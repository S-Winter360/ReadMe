package com.readme.app.reading.progress

import com.readme.app.reading.ReadingDocument

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
}
