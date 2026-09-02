package com.readme.app.reading.content

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentFormatDetectorTest {

    @Test
    fun detect_pdfMimeType_returnsPdf() {
        assertEquals(DetectedFormat.PDF, DocumentFormatDetector.detect("application/pdf", "sample.unknown"))
        assertEquals(DetectedFormat.PDF, DocumentFormatDetector.detect("APPLICATION/PDF", "sample"))
    }

    @Test
    fun detect_pdfExtension_returnsPdf() {
        assertEquals(DetectedFormat.PDF, DocumentFormatDetector.detect(null, "manual.pdf"))
        assertEquals(DetectedFormat.PDF, DocumentFormatDetector.detect("application/octet-stream", "report.PDF"))
        assertEquals(DetectedFormat.PDF, DocumentFormatDetector.detect("", "document.pdf "))
    }

    @Test
    fun detect_epubMimeType_returnsEpub() {
        assertEquals(DetectedFormat.EPUB, DocumentFormatDetector.detect("application/epub+zip", "book.unknown"))
        assertEquals(DetectedFormat.EPUB, DocumentFormatDetector.detect("APPLICATION/EPUB+ZIP", "book"))
    }

    @Test
    fun detect_epubExtension_returnsEpub() {
        assertEquals(DetectedFormat.EPUB, DocumentFormatDetector.detect(null, "dracula.epub"))
        assertEquals(DetectedFormat.EPUB, DocumentFormatDetector.detect("application/zip", "novel.EPUB"))
    }

    @Test
    fun detect_txtMimeType_returnsTxt() {
        assertEquals(DetectedFormat.TXT, DocumentFormatDetector.detect("text/plain", "notes.bin"))
        assertEquals(DetectedFormat.TXT, DocumentFormatDetector.detect("text/plain; charset=utf-8", "notes"))
        assertEquals(DetectedFormat.TXT, DocumentFormatDetector.detect("TEXT/PLAIN", "notes"))
    }

    @Test
    fun detect_txtExtension_returnsTxt() {
        assertEquals(DetectedFormat.TXT, DocumentFormatDetector.detect(null, "story.txt"))
        assertEquals(DetectedFormat.TXT, DocumentFormatDetector.detect("application/octet-stream", "readme.TXT"))
    }

    @Test
    fun detect_unsupportedFormats_returnsUnknown() {
        assertEquals(DetectedFormat.UNKNOWN, DocumentFormatDetector.detect("application/msword", "document.docx"))
        assertEquals(DetectedFormat.UNKNOWN, DocumentFormatDetector.detect("image/png", "picture.png"))
        assertEquals(DetectedFormat.UNKNOWN, DocumentFormatDetector.detect("audio/mpeg", "song.mp3"))
        assertEquals(DetectedFormat.UNKNOWN, DocumentFormatDetector.detect(null, "archive.zip"))
        assertEquals(DetectedFormat.UNKNOWN, DocumentFormatDetector.detect(null, null))
    }
}
