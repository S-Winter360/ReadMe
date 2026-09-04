package com.readme.app.reading.content.pdf

/**
 * Immutable representation of the synchronization state between
 * the active speech/reading position and the visual PDF viewport.
 *
 * All page numbers represented here use a 0-based indexing convention.
 *
 * This state is observational only and does NOT trigger any automatic scrolling
 * or speech modification.
 */
data class PdfReadingSyncState(
    /**
     * 0-based PDF page index corresponding to the currently spoken/read segment,
     * or null if no PDF is active or there is no active reading position.
     */
    val speechPage: Int? = null,

    /**
     * 0-based PDF page index of the first visible page in the viewport,
     * or null if no PDF viewer is active or no viewport has been reported.
     */
    val viewportPage: Int? = null,

    /**
     * True if [speechPage] is within the visible page range of the viewport.
     */
    val isSpeechPageVisible: Boolean = false,

    /**
     * The 0-based page index range currently visible in the viewport, or null.
     */
    val visiblePageRange: IntRange? = null,

    /**
     * Document ID of the active PDF document, or null if no PDF is active.
     */
    val documentId: String? = null,

    /**
     * 0-based PDF page index of any pending speech-driven navigation request, or null.
     */
    val pendingNavigationPage: Int? = null,

    /**
     * True if an automatic speech-driven navigation request is currently pending.
     */
    val isNavigationPending: Boolean = pendingNavigationPage != null
) {
    val hasActivePdf: Boolean get() = documentId != null
    val isEmpty: Boolean get() = documentId == null && speechPage == null && viewportPage == null && pendingNavigationPage == null

    companion object {
        val Empty = PdfReadingSyncState()
    }
}
