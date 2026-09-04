package com.readme.app.ui.pdf

/**
 * Immutable representation of a pending speech-driven PDF page navigation request.
 * Document-aware to prevent stale cross-document navigation.
 *
 * Page index uses the 0-based physical page indexing convention.
 */
data class PendingPdfNavigation(
    val documentId: String,
    val pageIndex: Int
)
