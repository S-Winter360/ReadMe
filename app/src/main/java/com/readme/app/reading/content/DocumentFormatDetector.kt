package com.readme.app.reading.content

enum class DetectedFormat {
    TXT,
    EPUB,
    PDF,
    UNKNOWN
}

/**
 * Robust format detector that inspects both MIME type and filename extension.
 */
object DocumentFormatDetector {
    fun detect(mimeType: String?, displayName: String?): DetectedFormat {
        val cleanMime = mimeType?.lowercase()?.trim() ?: ""
        val cleanName = displayName?.lowercase()?.trim() ?: ""

        return when {
            cleanMime == "application/pdf" || cleanName.endsWith(".pdf") -> DetectedFormat.PDF
            cleanMime == "application/epub+zip" || cleanName.endsWith(".epub") -> DetectedFormat.EPUB
            cleanMime == "text/plain" || cleanMime.startsWith("text/") || cleanName.endsWith(".txt") -> DetectedFormat.TXT
            else -> DetectedFormat.UNKNOWN
        }
    }

    fun resolveDisplayName(context: android.content.Context, uri: android.net.Uri): String {
        var name = "Document"
        try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val resolved = cursor.getString(nameIndex)
                        if (!resolved.isNullOrBlank()) {
                            name = resolved
                        }
                    }
                }
            }
        } catch (_: Throwable) {
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
