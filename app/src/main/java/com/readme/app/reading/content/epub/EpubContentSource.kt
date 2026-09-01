package com.readme.app.reading.content.epub

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

/**
 * Concrete [ReadingContentSource] that opens an EPUB file from an Android [Uri]
 * via [ContentResolver] and parses its package-level metadata and spine structure.
 *
 * In Phase 7B, this establishes the container and package analysis foundation.
 * Later phases transform the extracted chapter contents into full [ReadingDocument] sections.
 */
class EpubContentSource(
    private val context: Context,
    val uri: Uri,
    val customDisplayName: String = ""
) : ReadingContentSource {

    /**
     * Parses and returns the underlying [EpubPackage] model from the EPUB container.
     */
    suspend fun parsePackage(): EpubPackage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = if (customDisplayName.isNotBlank()) {
            customDisplayName
        } else {
            resolveDisplayName(resolver, uri)
        }

        val inputStream = resolver.openInputStream(uri)
            ?: throw IOException("Cannot open input stream for EPUB URI: $uri")

        inputStream.use { stream ->
            EpubPackageParser.parse(inputStream = stream, fallbackTitle = displayName)
        }
    }

    override suspend fun load(): ReadingDocument = withContext(Dispatchers.IO) {
        val epubPackage = parsePackage()

        ReadingDocument(
            id = "epub_doc_${System.currentTimeMillis()}",
            metadata = ReadingDocumentMetadata(
                title = epubPackage.metadata.title,
                author = epubPackage.metadata.author,
                sourceType = ReadingDocumentSourceType.EPUB
            ),
            sections = emptyList()
        )
    }

    companion object {
        fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String {
            var name = "Untitled Document.epub"
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
