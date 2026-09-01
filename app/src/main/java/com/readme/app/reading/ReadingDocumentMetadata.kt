package com.readme.app.reading

/**
 * Supported source types for reading documents.
 * Enables future formats (e.g. EPUB, PDF) without modifying core engine logic.
 */
enum class ReadingDocumentSourceType {
    SAMPLE,
    TXT,
    EPUB,
    PDF,
    IMAGE,
    OTHER
}

/**
 * Metadata representation for a [ReadingDocument].
 *
 * @property title The display title of the document.
 * @property author The author of the document, or null if unknown or not provided.
 * @property sourceType The source format or origin of the document.
 */
data class ReadingDocumentMetadata(
    val title: String,
    val author: String? = null,
    val sourceType: ReadingDocumentSourceType = ReadingDocumentSourceType.OTHER
)
