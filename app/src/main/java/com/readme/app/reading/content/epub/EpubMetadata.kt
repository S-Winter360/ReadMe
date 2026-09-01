package com.readme.app.reading.content.epub

/**
 * Clean data model representing metadata extracted from an EPUB package document (.opf).
 *
 * @property title The title of the EPUB book, or fallback if unassigned.
 * @property author The creator or author of the EPUB, or null if not provided.
 * @property identifier The unique book identifier (e.g. ISBN or UUID), or null if not provided.
 * @property language The language code of the book, or null if not provided.
 * @property publisher The publisher name, or null if not provided.
 */
data class EpubMetadata(
    val title: String,
    val author: String? = null,
    val identifier: String? = null,
    val language: String? = null,
    val publisher: String? = null
)
