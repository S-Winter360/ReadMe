package com.readme.app.reading.content.pdf

import androidx.pdf.PdfDocument
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.content.TxtDocumentParser
import com.readme.app.reading.content.pdf.ocr.PdfOcrEngine
import com.readme.app.reading.content.pdf.ocr.PdfOcrTextNormalizer
import com.readme.app.reading.content.pdf.ocr.PdfPageRasterizer
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class PdfDocumentParser(
    private val ocrEngine: PdfOcrEngine? = null
) {
    suspend fun parse(
        pdfDocument: PdfDocument,
        documentId: String,
        title: String,
        author: String? = null
    ): ReadingDocument {
        val sections = mutableListOf<ReadingSection>()
        val pageCount = pdfDocument.pageCount

        var hasValidText = false
        var lastModelUnavailableException: com.readme.app.reading.content.pdf.ocr.PdfOcrModelUnavailableException? = null
        var lastUnavailableException: com.readme.app.reading.content.pdf.ocr.PdfOcrUnavailableException? = null
        var lastInitializationException: com.readme.app.reading.content.pdf.ocr.PdfOcrInitializationException? = null
        var ocrAttemptedPages = 0

        for (page in 0 until pageCount) {
            coroutineContext.ensureActive()

            val pageContent = try {
                pdfDocument.getPageContent(page)
            } catch (e: Throwable) {
                null
            }
            val textContents = pageContent?.textContents

            var pageText = ""
            var fromOcr = false
            val textSpans = mutableListOf<Triple<Int, Int, List<android.graphics.RectF>>>()

            if (textContents != null && textContents.isNotEmpty()) {
                val textBuilder = java.lang.StringBuilder()
                for (textContent in textContents) {
                    val start = textBuilder.length
                    textBuilder.append(textContent.text)
                    val end = textBuilder.length
                    textBuilder.append(" ")
                    val bList = try { textContent.bounds } catch (_: Throwable) { emptyList<android.graphics.RectF>() }
                    textSpans.add(Triple(start, end, bList))
                }
                pageText = textBuilder.toString()
            }

            var cleanText = normalizeNativeText(pageText)

            // If no native selectable text, fallback to OCR on this page
            if (cleanText.isBlank() && ocrEngine != null) {
                ocrAttemptedPages++
                try {
                    val pageInfo = try {
                        pdfDocument.getPageInfo(page)
                    } catch (e: Throwable) {
                        null
                    }
                    val rasterSize = PdfPageRasterizer.calculateRasterSize(
                        pageInfo?.width ?: 0,
                        pageInfo?.height ?: 0
                    )

                    val bitmapSource = try {
                        pdfDocument.getPageBitmapSource(page)
                    } catch (e: Throwable) {
                        null
                    }

                    val bitmap = if (bitmapSource != null) {
                        try {
                            bitmapSource.getBitmap(rasterSize)
                        } catch (e: Throwable) {
                            try { bitmapSource.close() } catch (_: Throwable) {}
                            null
                        }
                    } else null

                    if (bitmap != null && bitmapSource != null) {
                        try {
                            val ocrResult = ocrEngine.recognize(bitmap, page)
                            if (ocrResult.hasText) {
                                val normalizedOcr = PdfOcrTextNormalizer.normalize(ocrResult.text)
                                if (normalizedOcr.isNotBlank()) {
                                    cleanText = normalizedOcr
                                    fromOcr = true
                                }
                            }
                        } finally {
                            try {
                                bitmap.recycle()
                            } catch (e: Throwable) {
                                // Ignore recycle error
                            }
                            try {
                                bitmapSource.close()
                            } catch (e: Throwable) {
                                // Ignore close error
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: com.readme.app.reading.content.pdf.ocr.PdfOcrModelUnavailableException) {
                    lastModelUnavailableException = e
                } catch (e: com.readme.app.reading.content.pdf.ocr.PdfOcrUnavailableException) {
                    lastUnavailableException = e
                } catch (e: com.readme.app.reading.content.pdf.ocr.PdfOcrInitializationException) {
                    lastInitializationException = e
                } catch (e: Throwable) {
                    // Page-level OCR failure: preserve physical page identity, do not crash whole document
                }
            }

            // If text is available (either native or OCR), construct segments
            if (cleanText.isNotBlank()) {
                hasValidText = true
                val paragraphs = cleanText.split(Regex("\\n\\s*\\n+"))
                val allSentences = mutableListOf<String>()
                for (para in paragraphs) {
                    val sentences = TxtDocumentParser.splitIntoSentences(para)
                    for (sentence in sentences) {
                        val trimmed = sentence.trim()
                        if (trimmed.isNotBlank()) {
                            allSentences.add(trimmed)
                        }
                    }
                }

                if (allSentences.isNotEmpty()) {
                    var searchOffset = 0
                    val sectionSegments = allSentences.mapIndexed { index, sentence ->
                        val segmentId = if (fromOcr) {
                            "pdf:$documentId:page:$page:ocr:$index"
                        } else {
                            "page:$page:segment:$index"
                        }
                        val sentenceBounds = mutableListOf<android.graphics.RectF>()
                        if (!fromOcr && textSpans.isNotEmpty()) {
                            val sIdx = pageText.indexOf(sentence, searchOffset)
                            val actualStart = if (sIdx >= 0) sIdx else searchOffset
                            val actualEnd = actualStart + sentence.length
                            if (sIdx >= 0) {
                                searchOffset = actualEnd
                            }
                            for (span in textSpans) {
                                if (span.first < actualEnd && span.second > actualStart) {
                                    sentenceBounds.addAll(span.third)
                                }
                            }
                        }
                        ReadingSegment(id = segmentId, text = sentence, boundingBoxes = sentenceBounds)
                    }

                    sections.add(
                        ReadingSection(
                            id = "page:$page",
                            title = "Page ${page + 1}",
                            segments = sectionSegments
                        )
                    )
                }
            }
        }

        if (!hasValidText) {
            if (lastModelUnavailableException != null) {
                throw lastModelUnavailableException
            }
            if (lastUnavailableException != null) {
                throw lastUnavailableException
            }
            if (lastInitializationException != null) {
                throw lastInitializationException
            }
            throw PdfNoSelectableTextException("No selectable text was found in this PDF.")
        }

        return ReadingDocument(
            id = "pdf:$documentId",
            metadata = ReadingDocumentMetadata(
                title = title.ifBlank { "Untitled Document" },
                author = author,
                sourceType = ReadingDocumentSourceType.PDF
            ),
            sections = sections
        )
    }

    private fun normalizeNativeText(text: String): String {
        return text
            .replace(Regex("(?<=\\w)-\\s*\\r?\\n\\s*(?=\\w)"), "") // Hyphenated word break
            .replace(Regex("\\r?\\n"), " ") // New lines to spaces
            .replace(Regex("\\s+"), " ") // Multiple spaces to single space
            .trim()
    }
}

open class PdfNoSelectableTextException(message: String = "No selectable text was found in this PDF.") : Exception(message)
class PdfOcrNoTextException(message: String = "No text could be recognized in this PDF.") : PdfNoSelectableTextException(message)
class PdfExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PdfPasswordRequiredException(message: String) : Exception(message)

