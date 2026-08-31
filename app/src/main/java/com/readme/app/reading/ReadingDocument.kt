package com.readme.app.reading

data class ReadingDocument(
    val id: String,
    val title: String,
    val sections: List<ReadingSection> = emptyList()
) {
    /**
     * Returns all segments across all sections in strict sequential reading order.
     */
    fun allSegments(): List<ReadingSegment> = sections.flatMap { it.segments }
}
