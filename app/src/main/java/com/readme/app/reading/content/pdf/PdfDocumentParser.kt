package com.readme.app.reading.content.pdf

import androidx.pdf.PdfDocument
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.content.TxtDocumentParser

class PdfDocumentParser {
    
    suspend fun parse(
        pdfDocument: PdfDocument,
        documentId: String,
        title: String,
        author: String? = null
    ): ReadingDocument {
        val sections = mutableListOf<ReadingSection>()
        val pageCount = pdfDocument.pageCount
        
        var hasSelectableText = false

        for (page in 0 until pageCount) {
            val pageContent = pdfDocument.getPageContent(page) ?: continue
            val textContents = pageContent.textContents
            
            if (textContents.isEmpty()) {
                continue
            }
            
            val pageText = StringBuilder()
            for (textContent in textContents) {
                pageText.append(textContent.text).append(" ")
            }
            
            val cleanText = normalizeText(pageText.toString())
            if (cleanText.isNotBlank()) {
                hasSelectableText = true
                val segments = TxtDocumentParser.splitIntoSentences(cleanText)
                if (segments.isNotEmpty()) {
                    val sectionSegments = segments.mapIndexed { index, sentence ->
                        ReadingSegment(id = "page:${page}:segment:${index}", text = sentence)
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
        
        if (!hasSelectableText) {
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
}

class PdfNoSelectableTextException(message: String) : Exception(message)
class PdfExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PdfPasswordRequiredException(message: String) : Exception(message)
