package com.readme.app.reading

/**
 * Immutable representation of a reading location within a [ReadingDocument].
 * Identifies the exact document, section, and segment, along with an optional sequential index.
 */
data class ReadingPosition(
    val documentId: String,
    val sectionId: String,
    val segmentId: String,
    val segmentIndex: Int = 0
)
