package com.readme.app.reading.content

import com.readme.app.reading.ReadingDocument

/**
 * Abstraction for supplying a [ReadingDocument] to the reading engine.
 * Decouples reading consumers from the origin or storage mechanism of documents.
 */
interface ReadingContentSource {
    suspend fun load(): ReadingDocument
}
