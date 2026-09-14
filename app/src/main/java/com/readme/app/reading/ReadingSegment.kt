package com.readme.app.reading

import android.graphics.RectF

data class ReadingSegment(
    val id: String,
    val text: String,
    val boundingBoxes: List<RectF> = emptyList()
)
