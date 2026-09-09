package com.readme.app.reading

/**
 * Format-agnostic representation of the document loading lifecycle.
 *
 * Distinct lifecycle states:
 * - [NoDocument]: No content is currently selected or loaded.
 * - [Loading]: A document is asynchronously being loaded/parsed.
 * - [Loaded]: Document has parsed successfully and is ready to be read.
 * - [Error]: Document loading or parsing failed.
 */
sealed interface DocumentLoadState {
    object NoDocument : DocumentLoadState
    data class Loading(val displayName: String? = null) : DocumentLoadState
    object Loaded : DocumentLoadState
    data class Error(val message: String) : DocumentLoadState

    val isNoDocument: Boolean get() = this is NoDocument
    val isLoading: Boolean get() = this is Loading
    val isLoaded: Boolean get() = this is Loaded
    val isError: Boolean get() = this is Error
}
