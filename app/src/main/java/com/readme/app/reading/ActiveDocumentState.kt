package com.readme.app.reading

/**
 * Unified observable state for the currently active document.
 * Exposes identity, metadata, and load lifecycle without duplicating
 * the underlying segments or document structure.
 *
 * @property documentId Unique identifier of the loaded document, or empty if none.
 * @property title The title of the document.
 * @property author The author of the document, or null if unknown.
 * @property sourceType The [ReadingDocumentSourceType] (e.g. TXT, EPUB, PDF).
 * @property displayName User-facing name (e.g. filename) for the document.
 * @property loadState The current [DocumentLoadState] lifecycle stage.
 */
data class ActiveDocumentState(
    val documentId: String = "",
    val title: String = "",
    val author: String? = null,
    val sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.OTHER,
    val displayName: String = "",
    val loadState: DocumentLoadState = DocumentLoadState.NoDocument
) {
    val hasActiveDocument: Boolean get() = documentId.isNotEmpty() && loadState is DocumentLoadState.Loaded
    val isLoaded: Boolean get() = loadState is DocumentLoadState.Loaded
    val isLoading: Boolean get() = loadState is DocumentLoadState.Loading
    val isError: Boolean get() = loadState is DocumentLoadState.Error
    val isNoDocument: Boolean get() = loadState is DocumentLoadState.NoDocument

    companion object {
        val None = ActiveDocumentState()

        fun fromDocument(
            document: ReadingDocument,
            displayName: String = document.title,
            loadState: DocumentLoadState = DocumentLoadState.Loaded
        ): ActiveDocumentState {
            return ActiveDocumentState(
                documentId = document.id,
                title = document.title,
                author = document.author,
                sourceType = document.sourceType,
                displayName = displayName,
                loadState = loadState
            )
        }
    }
}
