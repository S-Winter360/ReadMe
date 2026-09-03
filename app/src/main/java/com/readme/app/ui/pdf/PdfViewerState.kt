package com.readme.app.ui.pdf

import android.net.Uri

/**
 * State of the visual PDF viewer in ReadMe.
 *
 * Lifecycle states:
 * - [Empty]: No PDF is active (initial state, TXT, EPUB, or after error/clear).
 * - [Loading]: A PDF URI is currently being prepared/loaded.
 * - [Active]: A PDF document is active and ready to be displayed visually.
 * - [Error]: PDF visual preparation failed.
 */
sealed class PdfViewerState {
    object Empty : PdfViewerState()
    data class Loading(val uri: Uri? = null) : PdfViewerState()
    data class Active(val uri: Uri? = null, val displayName: String = "") : PdfViewerState()
    data class Error(val message: String) : PdfViewerState()

    val isEmpty: Boolean get() = this is Empty
    val isActive: Boolean get() = this is Active
    val isLoading: Boolean get() = this is Loading
}
