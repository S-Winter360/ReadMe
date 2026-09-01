package com.readme.app.reading

data class ReadingDocument(
    val id: String,
    val metadata: ReadingDocumentMetadata,
    val sections: List<ReadingSection> = emptyList()
) {
    /**
     * Convenience property delegating to [ReadingDocumentMetadata.title].
     */
    val title: String get() = metadata.title

    /**
     * Convenience property delegating to [ReadingDocumentMetadata.author].
     */
    val author: String? get() = metadata.author

    /**
     * Convenience property delegating to [ReadingDocumentMetadata.sourceType].
     */
    val sourceType: ReadingDocumentSourceType get() = metadata.sourceType

    /**
     * Convenience constructor allowing direct creation with title and optional author/sourceType.
     */
    constructor(
        id: String,
        title: String,
        author: String? = null,
        sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.OTHER,
        sections: List<ReadingSection> = emptyList()
    ) : this(
        id = id,
        metadata = ReadingDocumentMetadata(title = title, author = author, sourceType = sourceType),
        sections = sections
    )
    /**
     * Returns all segments across all sections in strict sequential reading order.
     */
    fun allSegments(): List<ReadingSegment> = sections.flatMap { it.segments }

    /**
     * Resolves a [ReadingPosition] to its corresponding [ReadingSegment], or null if not found,
     * or if document ID does not match.
     */
    fun resolvePosition(position: ReadingPosition?): ReadingSegment? {
        if (position == null || position.documentId != id || id.isEmpty()) return null
        val section = sections.find { it.id == position.sectionId } ?: return null
        return section.segments.find { it.id == position.segmentId }
    }

    /**
     * Finds the [ReadingPosition] for a given global segment index across all sections,
     * or null if the index is out of bounds or the document is empty.
     */
    fun positionForIndex(index: Int): ReadingPosition? {
        if (index < 0 || id.isEmpty()) return null
        var runningCount = 0
        for (section in sections) {
            if (index < runningCount + section.segments.size) {
                val seg = section.segments[index - runningCount]
                return ReadingPosition(
                    documentId = id,
                    sectionId = section.id,
                    segmentId = seg.id,
                    segmentIndex = index
                )
            }
            runningCount += section.segments.size
        }
        return null
    }

    /**
     * Finds the [ReadingPosition] for a specific [segmentId], or null if not found.
     */
    fun positionForSegmentId(segmentId: String): ReadingPosition? {
        if (id.isEmpty()) return null
        var globalIndex = 0
        for (section in sections) {
            for (seg in section.segments) {
                if (seg.id == segmentId) {
                    return ReadingPosition(
                        documentId = id,
                        sectionId = section.id,
                        segmentId = seg.id,
                        segmentIndex = globalIndex
                    )
                }
                globalIndex++
            }
        }
        return null
    }
}
