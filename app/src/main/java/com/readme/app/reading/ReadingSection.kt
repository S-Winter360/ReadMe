package com.readme.app.reading

data class ReadingSection(
    val id: String,
    val title: String = "",
    val segments: List<ReadingSegment> = emptyList()
)
