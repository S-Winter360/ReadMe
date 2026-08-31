package com.readme.app.reading.content

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.readme.app.reading.ReadingDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Concrete [ReadingContentSource] that reads a text file from an Android [Uri]
 * via [ContentResolver] and parses it into a [ReadingDocument].
 */
class TxtContentSource(
    private val context: Context,
    val uri: Uri,
    val customDisplayName: String = ""
) : ReadingContentSource {

    override suspend fun load(): ReadingDocument = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val fileName = if (customDisplayName.isNotBlank()) {
            customDisplayName
        } else {
            resolveDisplayName(resolver, uri)
        }

        val inputStream = resolver.openInputStream(uri)
            ?: throw IOException("Cannot open input stream for URI: $uri")

        val rawText = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        TxtDocumentParser.parse(title = fileName, rawText = rawText)
    }

    companion object {
        fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String {
            var name = "Untitled Document.txt"
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
                // If query fails, fallback to uri last path segment or default
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
