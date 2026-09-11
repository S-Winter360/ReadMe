package com.readme.app.accessibility

enum class CrossAppTextRole {
    TEXT,
    HEADING,
    LABEL
}

data class CrossAppTextBlock(
    val text: String,
    val order: Int,
    val role: CrossAppTextRole = CrossAppTextRole.TEXT
)

/**
 * Immutable data model representing an acquired text snapshot from an external application.
 * Contains no platform AccessibilityNodeInfo references.
 */
data class CrossAppTextSnapshot(
    val sourcePackageName: String,
    val sourceWindowId: Int? = null,
    val capturedAt: Long = System.currentTimeMillis(),
    val generation: Long = 0L,
    val title: String? = null,
    val blocks: List<CrossAppTextBlock> = emptyList()
) {
    val totalTextLength: Int get() = blocks.sumOf { it.text.length }
    val isEmpty: Boolean get() = blocks.isEmpty() || blocks.all { it.text.isBlank() }
}
