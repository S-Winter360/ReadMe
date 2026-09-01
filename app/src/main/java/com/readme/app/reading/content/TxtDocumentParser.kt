package com.readme.app.reading.content

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment

/**
 * Pure Kotlin parser that converts raw plain text into structured [ReadingDocument]
 * containing ordered [ReadingSection]s and [ReadingSegment]s.
 *
 * Implements deterministic, speech-friendly sentence segmentation tailored for TextToSpeech:
 * - Preserves abbreviations (Mr., Mrs., Dr., Prof., e.g., i.e., etc.) without awkward splits
 * - Preserves decimal numbers (3.14, 10.30)
 * - Preserves single-letter initials (J. K. Rowling, George W. Bush)
 * - Handles dialogue with straight and smart quotation marks
 * - Handles ellipses (... and …)
 * - Handles question and exclamation marks
 * - Preserves headings and fragments without terminal punctuation
 * - Normalizes whitespace and line endings without creating empty segments
 */
object TxtDocumentParser {

    private val TITLE_ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "miss", "dr", "prof", "sr", "jr", "rev",
        "gen", "col", "lt", "capt", "maj", "sgt", "gov", "sen", "rep",
        "st", "mt", "pres", "amb"
    )

    private val COMMON_ABBREVIATIONS = setOf(
        "etc", "eg", "e.g", "ie", "i.e", "vs", "approx", "est", "dept", "univ",
        "no", "vol", "pp", "p", "fig", "al", "jan", "feb", "mar", "apr", "jun",
        "jul", "aug", "sep", "sept", "oct", "nov", "dec", "am", "a.m", "pm", "p.m"
    )

    /**
     * Parses [rawText] with a given [title] into a [ReadingDocument].
     */
    fun parse(title: String, rawText: String, documentId: String = "txt_doc_${System.currentTimeMillis()}"): ReadingDocument {
        val cleanTitle = title.trim().ifBlank { "Untitled Document" }
        val metadata = ReadingDocumentMetadata(
            title = cleanTitle,
            author = null,
            sourceType = ReadingDocumentSourceType.TXT
        )

        if (rawText.isBlank()) {
            return ReadingDocument(
                id = documentId,
                metadata = metadata,
                sections = emptyList()
            )
        }

        val normalizedText = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val rawParagraphs = normalizedText.split(Regex("\n\\s*\n+"))

        val segments = mutableListOf<ReadingSegment>()
        var segmentCounter = 0

        for (paragraph in rawParagraphs) {
            val cleanParagraph = paragraph.trim()
            if (cleanParagraph.isBlank()) continue

            val sentences = splitIntoSentences(cleanParagraph)
            for (sentence in sentences) {
                val trimmed = sentence.trim()
                if (trimmed.isNotBlank()) {
                    segments.add(
                        ReadingSegment(
                            id = "${documentId}_seg_$segmentCounter",
                            text = trimmed
                        )
                    )
                    segmentCounter++
                }
            }
        }

        val section = ReadingSection(
            id = "${documentId}_sec_0",
            title = cleanTitle,
            segments = segments
        )

        return ReadingDocument(
            id = documentId,
            metadata = metadata,
            sections = if (segments.isEmpty()) emptyList() else listOf(section)
        )
    }

    /**
     * Splits a single cleaned paragraph into individual sentence units suitable for speech.
     */
    internal fun splitIntoSentences(paragraph: String): List<String> {
        val clean = paragraph.replace(Regex("[ \t\n\r]+"), " ").trim()
        if (clean.isEmpty()) return emptyList()

        val results = mutableListOf<String>()
        var sentenceStart = 0
        var i = 0

        while (i < clean.length) {
            val c = clean[i]

            if (c == '!' || c == '?') {
                // Consume consecutive exclamation/question marks
                var endIdx = i + 1
                while (endIdx < clean.length && (clean[endIdx] == '!' || clean[endIdx] == '?' || clean[endIdx] == '.')) {
                    endIdx++
                }
                // Consume closing quotes or brackets
                var hadClosingQuote = false
                while (endIdx < clean.length && isClosingQuoteOrBracket(clean[endIdx])) {
                    hadClosingQuote = true
                    endIdx++
                }

                if (endIdx >= clean.length) {
                    val s = clean.substring(sentenceStart, endIdx).trim()
                    if (s.isNotEmpty()) results.add(s)
                    sentenceStart = clean.length
                    break
                } else if (clean[endIdx] == ' ') {
                    val nextNonSpace = findNextNonSpace(clean, endIdx)
                    if (nextNonSpace >= clean.length) {
                        val s = clean.substring(sentenceStart, endIdx).trim()
                        if (s.isNotEmpty()) results.add(s)
                        sentenceStart = clean.length
                        break
                    } else {
                        val nextChar = clean[nextNonSpace]
                        val insideQuote = !hadClosingQuote && isInsideUnclosedQuote(clean, sentenceStart, i)
                        if (!insideQuote && (nextChar.isUpperCase() || isOpeningQuoteOrBracket(nextChar) || nextChar.isDigit())) {
                            val s = clean.substring(sentenceStart, endIdx).trim()
                            if (s.isNotEmpty()) results.add(s)
                            sentenceStart = nextNonSpace
                            i = nextNonSpace
                            continue
                        }
                    }
                }
                i = endIdx
                continue
            } else if (c == '…') {
                // Unicode ellipsis
                var endIdx = i + 1
                var hadClosingQuote = false
                while (endIdx < clean.length && isClosingQuoteOrBracket(clean[endIdx])) {
                    hadClosingQuote = true
                    endIdx++
                }
                if (endIdx >= clean.length) {
                    val s = clean.substring(sentenceStart, endIdx).trim()
                    if (s.isNotEmpty()) results.add(s)
                    sentenceStart = clean.length
                    break
                } else if (clean[endIdx] == ' ') {
                    val nextNonSpace = findNextNonSpace(clean, endIdx)
                    if (nextNonSpace >= clean.length) {
                        val s = clean.substring(sentenceStart, endIdx).trim()
                        if (s.isNotEmpty()) results.add(s)
                        sentenceStart = clean.length
                        break
                    } else {
                        val nextChar = clean[nextNonSpace]
                        val insideQuote = !hadClosingQuote && isInsideUnclosedQuote(clean, sentenceStart, i)
                        if (!insideQuote && (nextChar.isUpperCase() || isOpeningQuoteOrBracket(nextChar) || nextChar.isDigit())) {
                            val s = clean.substring(sentenceStart, endIdx).trim()
                            if (s.isNotEmpty()) results.add(s)
                            sentenceStart = nextNonSpace
                            i = nextNonSpace
                            continue
                        }
                    }
                }
                i = endIdx
                continue
            } else if (c == '.') {
                // Check if it's an ASCII ellipsis (... or ..)
                var dotCount = 0
                var dotScan = i
                while (dotScan < clean.length && clean[dotScan] == '.') {
                    dotCount++
                    dotScan++
                }

                if (dotCount >= 2) {
                    var endIdx = dotScan
                    var hadClosingQuote = false
                    while (endIdx < clean.length && isClosingQuoteOrBracket(clean[endIdx])) {
                        hadClosingQuote = true
                        endIdx++
                    }
                    if (endIdx >= clean.length) {
                        val s = clean.substring(sentenceStart, endIdx).trim()
                        if (s.isNotEmpty()) results.add(s)
                        sentenceStart = clean.length
                        break
                    } else if (clean[endIdx] == ' ') {
                        val nextNonSpace = findNextNonSpace(clean, endIdx)
                        if (nextNonSpace >= clean.length) {
                            val s = clean.substring(sentenceStart, endIdx).trim()
                            if (s.isNotEmpty()) results.add(s)
                            sentenceStart = clean.length
                            break
                        } else {
                            val nextChar = clean[nextNonSpace]
                            val insideQuote = !hadClosingQuote && isInsideUnclosedQuote(clean, sentenceStart, i)
                            if (!insideQuote && (nextChar.isUpperCase() || isOpeningQuoteOrBracket(nextChar) || nextChar.isDigit())) {
                                val s = clean.substring(sentenceStart, endIdx).trim()
                                if (s.isNotEmpty()) results.add(s)
                                sentenceStart = nextNonSpace
                                i = nextNonSpace
                                continue
                            }
                        }
                    }
                    i = endIdx
                    continue
                }

                // Single period
                // 1. Decimal number check (e.g., 3.14, 10.30)
                if (i > 0 && clean[i - 1].isDigit() && i + 1 < clean.length && clean[i + 1].isDigit()) {
                    i++
                    continue
                }

                // 2. Preceding word check (abbreviations and initials)
                val wordBefore = extractPrecedingWord(clean, i)
                val wordLower = wordBefore.lowercase()

                // Single-letter initial check
                if (isInitial(clean, sentenceStart, wordBefore, i)) {
                    i++
                    continue
                }

                // Title abbreviations (e.g., "Dr.", "Mr.", "Mrs.", "Prof.")
                if (TITLE_ABBREVIATIONS.contains(wordLower)) {
                    i++
                    continue
                }

                // Consume closing quotes or brackets
                var endIdx = i + 1
                var hadClosingQuote = false
                while (endIdx < clean.length && isClosingQuoteOrBracket(clean[endIdx])) {
                    hadClosingQuote = true
                    endIdx++
                }

                if (endIdx >= clean.length) {
                    val s = clean.substring(sentenceStart, endIdx).trim()
                    if (s.isNotEmpty()) results.add(s)
                    sentenceStart = clean.length
                    break
                } else if (clean[endIdx] == ' ') {
                    val nextNonSpace = findNextNonSpace(clean, endIdx)
                    if (nextNonSpace >= clean.length) {
                        val s = clean.substring(sentenceStart, endIdx).trim()
                        if (s.isNotEmpty()) results.add(s)
                        sentenceStart = clean.length
                        break
                    } else {
                        val nextChar = clean[nextNonSpace]

                        // If it's a common abbreviation followed by lowercase (e.g., "e.g. apples"), don't split
                        if (COMMON_ABBREVIATIONS.contains(wordLower) && nextChar.isLowerCase()) {
                            i = endIdx
                            continue
                        }

                        val insideQuote = !hadClosingQuote && isInsideUnclosedQuote(clean, sentenceStart, i)
                        // Sentence boundary if followed by uppercase, opening quote, or digit
                        if (!insideQuote && (nextChar.isUpperCase() || isOpeningQuoteOrBracket(nextChar) || nextChar.isDigit())) {
                            val s = clean.substring(sentenceStart, endIdx).trim()
                            if (s.isNotEmpty()) results.add(s)
                            sentenceStart = nextNonSpace
                            i = nextNonSpace
                            continue
                        }
                    }
                }
                i = endIdx
                continue
            }

            i++
        }

        if (sentenceStart < clean.length) {
            val remaining = clean.substring(sentenceStart).trim()
            if (remaining.isNotEmpty()) {
                results.add(remaining)
            }
        }

        return results
    }

    private fun isInsideUnclosedQuote(text: String, sentenceStart: Int, currentIndex: Int): Boolean {
        var straightQuoteCount = 0
        var smartCurlyOpenCount = 0

        for (k in sentenceStart..currentIndex) {
            val ch = text[k]
            if (ch == '"') {
                straightQuoteCount++
            } else if (ch == '“' || ch == '‘' || ch == '«') {
                smartCurlyOpenCount++
            } else if (ch == '”' || ch == '’' || ch == '»') {
                if (smartCurlyOpenCount > 0) smartCurlyOpenCount--
            }
        }

        return (straightQuoteCount % 2 != 0) || (smartCurlyOpenCount > 0)
    }

    private val NON_INITIAL_LABELS = setOf(
        "sentence", "appendix", "figure", "fig", "chapter", "section", "option",
        "type", "group", "model", "class", "level", "grade", "phase", "part",
        "exhibit", "item", "table", "volume", "version", "rule", "plan", "case"
    )

    private fun isInitial(text: String, sentenceStart: Int, wordBefore: String, dotIndex: Int): Boolean {
        if (wordBefore.length != 1 || !wordBefore[0].isUpperCase()) return false

        val textSoFar = text.substring(sentenceStart, dotIndex).trim()
        if (textSoFar.length <= 2) return true

        val wordPriorToInitial = extractPrecedingWord(text, dotIndex - 2)
        val wordPriorLower = wordPriorToInitial.lowercase()

        // If explicitly preceded by a label like "Sentence", "Option", "Model", it's a label, not a name initial
        if (NON_INITIAL_LABELS.contains(wordPriorLower)) return false

        // Check if followed by a capitalized word or another initial (e.g. "K.", "Rowling", "Bush")
        val nextNonSpace = findNextNonSpace(text, dotIndex + 1)
        if (nextNonSpace < text.length && text[nextNonSpace].isUpperCase()) {
            return true
        }

        return false
    }

    private fun isClosingQuoteOrBracket(c: Char): Boolean {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == '»' || c == ')' || c == ']' || c == '}'
    }

    private fun isOpeningQuoteOrBracket(c: Char): Boolean {
        return c == '"' || c == '\'' || c == '“' || c == '‘' || c == '«' || c == '(' || c == '[' || c == '{'
    }

    private fun findNextNonSpace(text: String, startIndex: Int): Int {
        var idx = startIndex
        while (idx < text.length && text[idx] == ' ') {
            idx++
        }
        return idx
    }

    private fun extractPrecedingWord(text: String, dotIdx: Int): String {
        var start = dotIdx - 1
        while (start >= 0 && (text[start].isLetter() || text[start] == '.')) {
            start--
        }
        return text.substring(start + 1, dotIdx)
    }
}

