package com.readme.app.reading.content

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.SampleContent

/**
 * Concrete [ReadingContentSource] supplying the built-in sample ReadingDocument.
 */
class SampleContentSource : ReadingContentSource {
    override suspend fun load(): ReadingDocument {
        return SampleContent.sampleDocument
    }
}
