package com.readme.app.reading.content.pdf.ocr

/**
 * Conservative text normalizer for OCR-recognized PDF text.
 *
 * Normalizes common OCR artifacts (broken hyphenated words across line breaks,
 * accidental single line breaks within sentences, stray non-printable control characters,
 * repeated whitespace) while strictly preserving:
 * - punctuation marks
 * - paragraph breaks (double newlines)
 * - single and double quotation marks (straight and curly)
 * - numeric digits and symbols
 */
object PdfOcrTextNormalizer {

    // Common prefixes that legitimately retain hyphenation across line breaks
    private val COMPOUND_PREFIX_REGEX = Regex("(?i)(?<=\\b(?:well|self|half|cross|quasi|all|ex))-\\s*\\n\\s*(?=[a-zA-Z])")

    /**
     * Conservatively normalizes [rawText] produced by OCR engine.
     */
    fun normalize(rawText: String): String {
        if (rawText.isBlank()) return ""

        // 1. Remove non-printable control characters, preserving \t and \n
        var text = rawText.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")

        // 2. Standardize all line endings to \n
        text = text.replace("\r\n", "\n").replace("\r", "\n")

        // 3. Preserve legitimate hyphenated compounds across line breaks (e.g. "well-\nknown" -> "well-known")
        text = text.replace(COMPOUND_PREFIX_REGEX, "-")

        // 4. Preserve capitalized compound words across line breaks (e.g. "Anglo-\nAmerican" -> "Anglo-American")
        text = text.replace(Regex("(?<=[a-zA-Z])-\\s*\\n\\s*(?=[A-Z])"), "-")

        // 5. Dehyphenate broken words split across line breaks (e.g. "read-\ning" -> "reading")
        text = text.replace(Regex("(?<=[a-zA-Z])-\\s*\\n\\s*(?=[a-z])"), "")

        // 6. Preserve numeric punctuation across accidental line breaks (e.g. "10.\n30" -> "10.30", "1,\n250" -> "1,250")
        text = text.replace(Regex("(?<=\\d)\\.\\s*\\n\\s*(?=\\d)"), ".")
        text = text.replace(Regex("(?<=\\d),\\s*\\n\\s*(?=\\d)"), ",")

        // 7. Preserve intentional paragraph breaks (2 or more newlines) using a unique token
        val paragraphMarker = "\u0000__OCR_PARA__\u0000"
        text = text.replace(Regex("\\n\\s*\\n+"), paragraphMarker)

        // 8. Replace single soft line breaks within a paragraph with spaces (e.g. "Dr.\nSmith" -> "Dr. Smith")
        text = text.replace(Regex("([^\\n])\\n([^\\n])"), "$1 $2")

        // 9. Restore paragraph breaks as clean double newlines
        text = text.replace(paragraphMarker, "\n\n")

        // 10. Collapse multiple horizontal spaces and tabs into a single space
        text = text.replace(Regex("[ \\t]+"), " ")

        // 11. Collapse 3+ newlines into standard double newline
        text = text.replace(Regex("\\n{3,}"), "\n\n")

        // 12. Trim edges
        return text.trim()
    }
}
