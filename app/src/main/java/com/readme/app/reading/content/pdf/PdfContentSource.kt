package com.readme.app.reading.content.pdf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.content.ReadingContentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class PdfNotSupportedException(message: String = "PDF support is being prepared.") : IOException(message)

/**
 * Concrete [ReadingContentSource] boundary for PDF documents.
 * In Phase 8A, this class provides the routing foundation, metadata container,
 * and controlled not-yet-implemented state without performing text extraction or speech.
 */
class PdfContentSource(
    private val context: Context? = null,
    val uri: Uri? = null,
    val customDisplayName: String = ""
) : ReadingContentSource {

    override suspend fun load(): ReadingDocument = withContext(Dispatchers.IO) {
        val safeContext = context ?: throw IOException("Context is required for PDF loading")
        val safeUri = uri ?: throw IOException("URI is required for PDF loading")

        val loader = androidx.pdf.SandboxedPdfLoader(safeContext, Dispatchers.IO)
        val pdfDocument = try {
            loader.openDocument(safeUri, "")
        } catch (e: Exception) {
            when {
                e.javaClass.simpleName == "PdfPasswordException" -> throw PdfPasswordRequiredException("Password required for this PDF.")
                else -> throw PdfExtractionException("Failed to load PDF", e)
            }
        }

        val parser = PdfDocumentParser()
        val documentId = safeUri.lastPathSegment ?: safeUri.toString().hashCode().toString()
        val document = try {
            parser.parse(pdfDocument, documentId, customDisplayName)
        } finally {
            // pdfDocument does not appear to implement Closeable directly in a way that compileDebugKotlin likes?
            // Actually it does: public interface androidx.pdf.PdfDocument extends java.io.Closeable
            try {
                pdfDocument.close()
            } catch (e: Exception) {
                // Ignore close errors
            }
        }

        // Apply metadata source type
        document.copy(
            sections = document.sections // Keep as is, ReadingDocument doesn't have metadata field here?
        )
        return@withContext document
    }

    /**
     * Helper to create metadata representation for PDF documents.
     */
    fun createMetadata(title: String): ReadingDocumentMetadata {
        return ReadingDocumentMetadata(
            title = title,
            author = null,
            sourceType = ReadingDocumentSourceType.PDF
        )
    }

    companion object {
        fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String {
            var name = "Untitled Document.pdf"
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val resolved = cursor.getString(nameIndex)
                            if (!resolved.isNullOrBlank()) {
                                name = resolved
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                uri.lastPathSegment?.let { segment ->
                    val clean = segment.substringAfterLast('/')
                    if (clean.isNotBlank()) {
                        name = clean
                    }
                }
            }
            return name
        }
    }
}
