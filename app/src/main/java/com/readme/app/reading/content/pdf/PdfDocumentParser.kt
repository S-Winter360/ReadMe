package com.readme.app.reading.content.pdf

import android.graphics.Bitmap
import android.util.Size
import androidx.pdf.PdfDocument
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.content.TxtDocumentParser
import com.readme.app.reading.content.pdf.ocr.PdfOcrEngine
import com.readme.app.reading.content.pdf.ocr.PdfOcrException
import kotlinx.coroutines.isActive
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
        
        for (page in 0 until pageCount) {
            if (!coroutineContext.isActive) break
            
            val pageContent = pdfDocument.getPageContent(page)
            val textContents = pageContent?.textContents
            
            var pageText = ""
            var fromOcr = false
            
            if (textContents != null && textContents.isNotEmpty()) {
                val textBuilder = java.lang.StringBuilder()
                for (textContent in textContents) {
                    textBuilder.append(textContent.text).append(" ")
                }
                pageText = textBuilder.toString()
            }
            
            var cleanText = normalizeText(pageText)
            
            // If no native text, fallback to OCR
            if (cleanText.isBlank() && ocrEngine != null) {
                try {
                    val bitmapSource = pdfDocument.getPageBitmapSource(page)
                    val bitmap = bitmapSource.getBitmap(Size(1200, 1600)) // Use reasonable default size
                    
                    val ocrResult = ocrEngine.recognize(bitmap)
                    if (ocrResult.hasText) {
                        cleanText = normalizeOcrText(ocrResult.text)
                        fromOcr = true
                    }
                    bitmap.recycle()
                    bitmapSource.close()
                } catch (e: Exception) {
                    // Ignore OCR errors per page and continue
                }
            }
            
            // If we found any text, build segments
            if (cleanText.isNotBlank()) {
                hasValidText = true
                val segments = TxtDocumentParser.splitIntoSentences(cleanText)
                if (segments.isNotEmpty()) {
                    val sectionSegments = segments.mapIndexed { index, sentence ->
                        val segmentId = if (fromOcr) {
                            "pdf:$documentId:page:$page:ocr:$index"
                        } else {
                            "page:${page}:segment:${index}"
                        }
                        ReadingSegment(id = segmentId, text = sentence)
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
            throw PdfNoSelectableTextException("No selectable text was found in this PDF.")
        }
        
        return ReadingDocument(
            id = "pdf:$documentId",
            title = title,
            sections = sections
        )
    }

    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("(?<=\\w)-\\s*\\r?\\n\\s*(?=\\w)"), "") // Hyphenated word break
            .replace(Regex("\\r?\\n"), " ") // New lines to spaces
            .replace(Regex("\\s+"), " ") // Multiple spaces to single space
            .trim()
    }
    
    private fun normalizeOcrText(text: String): String {
        // More conservative normalisation for OCR
        return text
            .replace(Regex("(?<=\\w)-\\s*\\r?\\n\\s*(?=\\w)"), "") // Hyphenated word break
            .replace(Regex("([^\\r\\n])\\r?\\n([^\\r\\n])"), "$1 $2") // New lines within paragraphs to spaces
            .replace(Regex("\\s{2,}"), " ") // Multiple spaces to single space
            .trim()
    }
}

class PdfNoSelectableTextException(message: String) : Exception(message)
class PdfExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PdfPasswordRequiredException(message: String) : Exception(message)
