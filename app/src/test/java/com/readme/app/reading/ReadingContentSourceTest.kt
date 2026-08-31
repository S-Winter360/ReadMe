package com.readme.app.reading

import com.readme.app.reading.content.ReadingContentSource
import com.readme.app.reading.content.SampleContentSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingContentSourceTest {

    @Test
    fun sampleContentSource_returnsSampleDocument() = runBlocking {
        val source: ReadingContentSource = SampleContentSource()
        val document = source.load()

        assertEquals("readme_sample_doc", document.id)
        assertEquals("ReadMe Sample", document.title)
        assertEquals(1, document.sections.size)

        val segments = document.allSegments()
        assertEquals(3, segments.size)
        assertEquals("Welcome to ReadMe.", segments[0].text)
        assertEquals("This is a test of your selected voice, speech speed, pitch, and volume.", segments[1].text)
        assertEquals("ReadMe is ready to read.", segments[2].text)
    }

    @Test
    fun readingEngine_loadsDocumentFromContentSource() = runBlocking {
        val customSource = object : ReadingContentSource {
            override suspend fun load(): ReadingDocument {
                return ReadingDocument(
                    id = "custom_doc",
                    title = "Custom Title",
                    sections = listOf(
                        ReadingSection(
                            id = "sec1",
                            title = "Chapter 1",
                            segments = listOf(
                                ReadingSegment("c1", "Custom first line."),
                                ReadingSegment("c2", "Custom second line.")
                            )
                        )
                    )
                )
            }
        }

        val engine = ReadingEngine()
        assertEquals(0, engine.totalSegments())

        val doc = customSource.load()
        engine.loadDocument(doc)

        assertEquals("custom_doc", engine.currentDocument.id)
        assertEquals(2, engine.totalSegments())

        val first = engine.startSession()
        assertNotNull(first)
        assertEquals("Custom first line.", first?.text)
        assertEquals(ReadingSessionState.Reading, engine.readingState.value)

        val second = engine.advance()
        assertNotNull(second)
        assertEquals("Custom second line.", second?.text)

        val completed = engine.advance()
        assertNull(completed)
        assertEquals(ReadingSessionState.Completed, engine.readingState.value)
    }

    @Test
    fun readingEngine_independentOfSampleContentByDefault() {
        val engine = ReadingEngine()
        assertEquals(0, engine.totalSegments())
        assertEquals("", engine.currentDocument.id)
        assertEquals(ReadingSessionState.Idle, engine.readingState.value)
    }
}
