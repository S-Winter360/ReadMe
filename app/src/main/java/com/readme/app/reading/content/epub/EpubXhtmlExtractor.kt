package com.readme.app.reading.content.epub

import java.nio.charset.Charset
import java.util.regex.Pattern

/**
 * Pure Kotlin parser that extracts clean, speech-friendly text and chapter titles
 * from EPUB XHTML / HTML document content.
 *
 * Design principles:
 * - Pure content processing: No Android UI, no TTS, no ViewModel coupling.
 * - Security: Never executes scripts, interprets CSS, or performs network requests.
 * - Paragraph preservation: Emits distinct paragraphs for block elements (<p>, <div>, <h1>-<h6>, <li>, <blockquote>, etc.).
 * - Inline coherence: Smoothly joins inline elements (<em>, <strong>, <a>, <span>, etc.) without speech fragmentation.
 * - Entity decoding: Decodes standard XML and HTML entities into proper Unicode characters.
 * - Safe title extraction: Extracts first meaningful <h1>-<h3> heading as the section title.
 */
object EpubXhtmlExtractor {

    data class ExtractedChapter(
        val title: String?,
        val paragraphs: List<String>
    )

    private val SCRIPT_STYLE_REGEX = Pattern.compile(
        "<(?:script|style|noscript|svg|head)[^>]*?>.*?</(?:script|style|noscript|svg|head)>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    private val COMMENT_REGEX = Pattern.compile("<!--.*?-->", Pattern.DOTALL)

    private val XML_ENCODING_REGEX = Pattern.compile(
        "<\\?xml[^>]+encoding=[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    )

    private val HTML_CHARSET_REGEX = Pattern.compile(
        "<meta[^>]+charset=[\"']?([^\"'\\s/>]+)",
        Pattern.CASE_INSENSITIVE
    )

    private val HEADING_REGEX = Pattern.compile(
        "<h([1-6])[^>]*>(.*?)</h\\1>",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )

    private val BLOCK_TAG_START_REGEX = Pattern.compile(
        "<(?:p|div|h[1-6]|li|blockquote|pre|section|article|header|footer|aside|tr|caption|figcaption|dt|dd|br|hr)(?:\\s[^>]*)?>",
        Pattern.CASE_INSENSITIVE
    )

    private val BLOCK_TAG_END_REGEX = Pattern.compile(
        "</(?:p|div|h[1-6]|li|blockquote|pre|section|article|header|footer|aside|tr|caption|figcaption|dt|dd)>",
        Pattern.CASE_INSENSITIVE
    )

    private val ANY_TAG_REGEX = Pattern.compile("<[^>]+>")

    private val HTML_ENTITIES = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&apos;" to "'",
        "&lsquo;" to "‘",
        "&rsquo;" to "’",
        "&ldquo;" to "“",
        "&rdquo;" to "”",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…",
        "&bull;" to "•",
        "&copy;" to "©",
        "&reg;" to "®",
        "&trade;" to "™",
        "&euro;" to "€",
        "&pound;" to "£",
        "&yen;" to "¥",
        "&cent;" to "¢",
        "&deg;" to "°",
        "&plusmn;" to "±",
        "&micro;" to "µ",
        "&sect;" to "§",
        "&para;" to "¶"
    )

    /**
     * Extracts chapter title and readable paragraphs from raw byte data.
     */
    fun extract(bytes: ByteArray, fallbackTitle: String? = null): ExtractedChapter {
        if (bytes.isEmpty()) {
            return ExtractedChapter(title = fallbackTitle, paragraphs = emptyList())
        }

        val encoding = detectEncoding(bytes)
        val text = try {
            String(bytes, Charset.forName(encoding))
        } catch (_: Exception) {
            String(bytes, Charsets.UTF_8)
        }

        return extract(text, fallbackTitle)
    }

    /**
     * Extracts chapter title and readable paragraphs from an XHTML / HTML string.
     */
    fun extract(xhtml: String, fallbackTitle: String? = null): ExtractedChapter {
        if (xhtml.isBlank()) {
            return ExtractedChapter(title = fallbackTitle, paragraphs = emptyList())
        }

        // 1. Remove XML/HTML comments
        var clean = COMMENT_REGEX.matcher(xhtml).replaceAll("")

        // 2. Extract first meaningful heading before stripping tags
        val extractedHeading = findFirstHeading(clean)

        // 3. Remove non-readable script, style, head, and svg blocks
        clean = SCRIPT_STYLE_REGEX.matcher(clean).replaceAll(" ")

        // 4. Transform block boundaries into distinct paragraph separator markers
        clean = BLOCK_TAG_START_REGEX.matcher(clean).replaceAll("\n\n")
        clean = BLOCK_TAG_END_REGEX.matcher(clean).replaceAll("\n\n")

        // 5. Replace remaining tags (inline tags like <em>, <strong>, <span>, <a>) with empty strings or spaces
        clean = ANY_TAG_REGEX.matcher(clean).replaceAll("")

        // 6. Decode entities
        clean = decodeHtmlEntities(clean)

        // 7. Split into paragraphs and normalize whitespace
        val rawParagraphs = clean.split(Regex("\n+"))
        val paragraphs = mutableListOf<String>()

        for (rawPara in rawParagraphs) {
            val normalized = rawPara.replace(Regex("[ \t\r]+"), " ").trim()
            if (normalized.isNotBlank()) {
                paragraphs.add(normalized)
            }
        }

        val finalTitle = extractedHeading ?: fallbackTitle

        return ExtractedChapter(
            title = finalTitle,
            paragraphs = paragraphs
        )
    }

    /**
     * Detects charset from XML header or HTML meta tag, defaulting to UTF-8.
     */
    private fun detectEncoding(bytes: ByteArray): String {
        val previewLength = minOf(bytes.size, 1024)
        val preview = String(bytes, 0, previewLength, Charsets.US_ASCII)

        val xmlMatcher = XML_ENCODING_REGEX.matcher(preview)
        if (xmlMatcher.find()) {
            val enc = xmlMatcher.group(1)?.trim()
            if (!enc.isNullOrEmpty()) return enc
        }

        val htmlMatcher = HTML_CHARSET_REGEX.matcher(preview)
        if (htmlMatcher.find()) {
            val enc = htmlMatcher.group(1)?.trim()
            if (!enc.isNullOrEmpty()) return enc
        }

        return "UTF-8"
    }

    /**
     * Scans for the first <h1>, <h2>, or <h3> element and extracts its plain-text content.
     */
    private fun findFirstHeading(xhtml: String): String? {
        val matcher = HEADING_REGEX.matcher(xhtml)
        while (matcher.find()) {
            val level = matcher.group(1)?.toIntOrNull() ?: 1
            // Prefer h1, h2, h3 for chapter titles
            if (level in 1..3) {
                val innerHtml = matcher.group(2) ?: ""
                val clean = ANY_TAG_REGEX.matcher(innerHtml).replaceAll("")
                val decoded = decodeHtmlEntities(clean).replace(Regex("\\s+"), " ").trim()
                if (decoded.isNotBlank() && decoded.length < 200) {
                    return decoded
                }
            }
        }
        return null
    }

    /**
     * Decodes standard named and numeric XML/HTML entities.
     */
    fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input

        var result = input
        for ((entity, replacement) in HTML_ENTITIES) {
            if (result.contains(entity, ignoreCase = true)) {
                result = result.replace(entity, replacement, ignoreCase = true)
            }
        }

        // Decode numeric decimal entities (e.g. &#8212;) and hex entities (e.g. &#x2014;)
        if (result.contains("&#")) {
            val numericPattern = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);")
            val matcher = numericPattern.matcher(result)
            val sb = StringBuffer()
            while (matcher.find()) {
                val entityValue = matcher.group(1) ?: ""
                try {
                    val codePoint = if (entityValue.startsWith("x", ignoreCase = true)) {
                        entityValue.substring(1).toInt(16)
                    } else {
                        entityValue.toInt(10)
                    }
                    if (Character.isValidCodePoint(codePoint)) {
                        val chars = Character.toChars(codePoint)
                        matcher.appendReplacement(sb, MatcherQuote(String(chars)))
                    } else {
                        matcher.appendReplacement(sb, MatcherQuote(matcher.group(0) ?: ""))
                    }
                } catch (_: Exception) {
                    matcher.appendReplacement(sb, MatcherQuote(matcher.group(0) ?: ""))
                }
            }
            matcher.appendTail(sb)
            result = sb.toString()
        }

        return result
    }

    private fun MatcherQuote(str: String): String {
        return java.util.regex.Matcher.quoteReplacement(str)
    }
}
