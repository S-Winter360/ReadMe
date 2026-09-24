package com.readme.app.accessibility

import android.graphics.RectF
import com.readme.app.reading.content.TxtDocumentParser

/**
 * Deterministic mapper that maps OCR blocks, lines, and individual word elements
 * to speech-ready sentences, calculating tight bounding boxes for each visual line
 * contributing to the sentence.
 *
 * Implements Phase 9X Part A requirements:
 * 1. Preserves OCR element/word geometry through sentence segmentation.
 * 2. Deterministically maps source element ranges to sentence text.
 * 3. Calculates tight highlight rectangles per visual line without including unrelated text or whitespace.
 * 4. Multi-line sentences produce separate tight rectangles for each line.
 * 5. Partial-line sentences highlight only the contributing words on that line.
 */
object OcrSentenceGeometryMapper {

    data class FlatOcrWord(
        val text: String,
        val bounds: RectF,
        val lineIndex: Int,
        val blockIndex: Int
    )

    private data class ElementCharSpan(
        val startChar: Int,
        val endChar: Int,
        val element: FlatOcrWord
    )

    /**
     * Maps OCR hierarchy into speech-ready [CrossAppOcrSentence] items with tight multi-line bounds.
     */
    fun mapSentencesWithTightBounds(
        blocks: List<CrossAppOcrBlock>,
        lines: List<CrossAppOcrLine>,
        rawOcrText: String,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<CrossAppOcrSentence> {
        val flatWords = extractFlatWords(blocks, lines, bitmapWidth, bitmapHeight)
        if (flatWords.isEmpty()) {
            val fallbackSentences = TxtDocumentParser.splitIntoSentences(rawOcrText.trim())
            return fallbackSentences.map { s ->
                val fullRect = RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
                CrossAppOcrSentence(text = s.trim(), bounds = fullRect, lineBounds = listOf(fullRect))
            }
        }

        // Build continuous text while tracking element char spans
        val fullTextBuilder = StringBuilder()
        val spans = mutableListOf<ElementCharSpan>()

        for (word in flatWords) {
            val trimmedWord = word.text.trim()
            if (trimmedWord.isEmpty()) continue

            if (fullTextBuilder.isNotEmpty()) {
                fullTextBuilder.append(" ")
            }
            val start = fullTextBuilder.length
            fullTextBuilder.append(trimmedWord)
            val end = fullTextBuilder.length
            spans.add(ElementCharSpan(start, end, word))
        }

        val fullText = fullTextBuilder.toString()
        if (fullText.isBlank()) return emptyList()

        val sentenceTexts = TxtDocumentParser.splitIntoSentences(fullText)
        val result = mutableListOf<CrossAppOcrSentence>()
        var textCursor = 0
        var spanCursor = 0

        for (sentence in sentenceTexts) {
            val trimmedSentence = sentence.trim()
            if (trimmedSentence.isEmpty()) continue

            val matchStart = fullText.indexOf(trimmedSentence, textCursor)
            val sentenceStart = if (matchStart >= 0) matchStart else textCursor
            val sentenceEnd = sentenceStart + trimmedSentence.length
            textCursor = sentenceEnd

            // Identify all contributing words for this sentence
            val contributingWords = mutableListOf<FlatOcrWord>()
            var i = spanCursor
            while (i < spans.size) {
                val span = spans[i]
                if (span.startChar >= sentenceEnd) {
                    break // Beyond this sentence
                }
                if (span.endChar > sentenceStart) {
                    contributingWords.add(span.element)
                }
                i++
            }

            if (contributingWords.isNotEmpty()) {
                // Advance span cursor for sequential sentences
                val lastMatchedIdx = spans.indexOfFirst { it.element === contributingWords.last() }
                if (lastMatchedIdx >= 0) {
                    spanCursor = lastMatchedIdx + 1
                }
            }

            val tightLineRects = computeTightLineRectangles(contributingWords)
            val unionBounds = if (tightLineRects.isNotEmpty()) {
                val minL = tightLineRects.minOf { it.left }
                val minT = tightLineRects.minOf { it.top }
                val maxR = tightLineRects.maxOf { it.right }
                val maxB = tightLineRects.maxOf { it.bottom }
                RectF(minL, minT, maxR, maxB).apply {
                    left = minL
                    top = minT
                    right = maxR
                    bottom = maxB
                }
            } else {
                RectF(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat()).apply {
                    left = 0f
                    top = 0f
                    right = bitmapWidth.toFloat()
                    bottom = bitmapHeight.toFloat()
                }
            }

            result.add(
                CrossAppOcrSentence(
                    text = trimmedSentence,
                    bounds = unionBounds,
                    lineBounds = tightLineRects.ifEmpty { listOf(unionBounds) }
                )
            )
        }

        return result
    }

    /**
     * Extracts an ordered flat list of word elements with non-empty bounds.
     * If lines do not contain elements (e.g. fallback reflection), synthesizes word elements
     * proportionally across the line bounds.
     */
    fun extractFlatWords(
        blocks: List<CrossAppOcrBlock>,
        lines: List<CrossAppOcrLine>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<FlatOcrWord> {
        val flat = mutableListOf<FlatOcrWord>()
        var currentLineGlobalIdx = 0

        if (lines.isNotEmpty()) {
            for ((lineIdx, line) in lines.withIndex()) {
                val lineText = line.text.trim()
                if (lineText.isEmpty()) continue

                if (line.elements.isNotEmpty()) {
                    for (elem in line.elements) {
                        val elemText = elem.text.trim()
                        if (elemText.isNotEmpty()) {
                            val b = elem.bounds
                            flat.add(
                                FlatOcrWord(
                                    text = elemText,
                                    bounds = RectF(b).apply {
                                        left = b.left
                                        top = b.top
                                        right = b.right
                                        bottom = b.bottom
                                    },
                                    lineIndex = lineIdx,
                                    blockIndex = 0
                                )
                            )
                        }
                    }
                } else {
                    // Synthesize elements for lines without explicit element children
                    val tokens = lineText.split(Regex("\\s+")).filter { it.isNotBlank() }
                    val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
                    val lineWidth = (line.bounds.right - line.bounds.left).coerceAtLeast(1f)
                    var currentX = line.bounds.left

                    for (token in tokens) {
                        val tokenWidth = (token.length.toFloat() / totalChars) * lineWidth
                        val rightX = (currentX + tokenWidth).coerceAtMost(line.bounds.right)
                        val wordRect = RectF(
                            currentX,
                            line.bounds.top,
                            rightX,
                            line.bounds.bottom
                        ).apply {
                            left = currentX
                            top = line.bounds.top
                            right = rightX
                            bottom = line.bounds.bottom
                        }
                        flat.add(
                            FlatOcrWord(
                                text = token,
                                bounds = wordRect,
                                lineIndex = lineIdx,
                                blockIndex = 0
                            )
                        )
                        currentX += tokenWidth
                    }
                }
                currentLineGlobalIdx++
            }
        } else if (blocks.isNotEmpty()) {
            for ((blkIdx, block) in blocks.withIndex()) {
                val blkText = block.text.trim()
                if (blkText.isEmpty()) continue
                val tokens = blkText.split(Regex("\\s+")).filter { it.isNotBlank() }
                val b = block.bounds
                for (token in tokens) {
                    flat.add(
                        FlatOcrWord(
                            text = token,
                            bounds = RectF(b).apply {
                                left = b.left
                                top = b.top
                                right = b.right
                                bottom = b.bottom
                            },
                            lineIndex = blkIdx,
                            blockIndex = blkIdx
                        )
                    )
                }
            }
        }

        return flat
    }

    /**
     * Computes tight rectangles per visual line from only the contributing words.
     * Does NOT include other words on the line, unused margins, or whole paragraphs.
     */
    fun computeTightLineRectangles(words: List<FlatOcrWord>): List<RectF> {
        if (words.isEmpty()) return emptyList()

        // Group words by lineIndex preserving line appearance order
        val lineGroups = mutableListOf<MutableList<FlatOcrWord>>()
        var currentGroup = mutableListOf<FlatOcrWord>()
        var currentLineId = words.first().lineIndex

        for (word in words) {
            // Also check geometric vertical alignment to prevent merging words on distant visual lines
            val sameLineIndex = word.lineIndex == currentLineId
            val lastWord = currentGroup.lastOrNull()
            val verticalOverlap = if (lastWord != null) {
                val overlap = minOf(lastWord.bounds.bottom, word.bounds.bottom) - maxOf(lastWord.bounds.top, word.bounds.top)
                val h1 = lastWord.bounds.bottom - lastWord.bounds.top
                val h2 = word.bounds.bottom - word.bounds.top
                val minH = minOf(h1, h2).coerceAtLeast(1f)
                overlap > (minH * 0.4f)
            } else true

            if (sameLineIndex && verticalOverlap) {
                currentGroup.add(word)
            } else {
                if (currentGroup.isNotEmpty()) {
                    lineGroups.add(currentGroup)
                }
                currentGroup = mutableListOf(word)
                currentLineId = word.lineIndex
            }
        }
        if (currentGroup.isNotEmpty()) {
            lineGroups.add(currentGroup)
        }

        // For each line group, build a tight bounding rectangle covering strictly its words
        val resultRects = mutableListOf<RectF>()
        for (group in lineGroups) {
            val minLeft = group.minOf { it.bounds.left }
            val minTop = group.minOf { it.bounds.top }
            val maxRight = group.maxOf { it.bounds.right }
            val maxBottom = group.maxOf { it.bounds.bottom }

            val width = maxRight - minLeft
            val height = maxBottom - minTop
            if (width > 0f && height > 0f) {
                val tightRect = RectF(minLeft, minTop, maxRight, maxBottom).apply {
                    left = minLeft
                    top = minTop
                    right = maxRight
                    bottom = maxBottom
                }
                resultRects.add(tightRect)
            }
        }

        return resultRects
    }
}
