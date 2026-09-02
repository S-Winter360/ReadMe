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
}
