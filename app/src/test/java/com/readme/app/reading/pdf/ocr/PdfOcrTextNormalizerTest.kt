package com.readme.app.reading.pdf.ocr

import com.readme.app.reading.content.pdf.ocr.PdfOcrTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfOcrTextNormalizerTest {

    @Test
    fun normalize_emptyOrBlank_returnsEmpty() {
        assertEquals("", PdfOcrTextNormalizer.normalize(""))
        assertEquals("", PdfOcrTextNormalizer.normalize("   "))
        assertEquals("", PdfOcrTextNormalizer.normalize("\n\t\r  \n"))
    }

    @Test
    fun normalize_removesHyphenationAcrossLineBreaks() {
        val input = "This is a demon-\nstration of dehyphen-\n  ation in OCR text."
        val expected = "This is a demonstration of dehyphenation in OCR text."
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_convertsSingleSoftLineBreaksToSpaces() {
        val input = "The quick brown fox\njumps over\nthe lazy dog."
        val expected = "The quick brown fox jumps over the lazy dog."
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_preservesParagraphBreaks() {
        val input = "First paragraph ends here.\n\nSecond paragraph begins here."
        val expected = "First paragraph ends here.\n\nSecond paragraph begins here."
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_preservesPunctuationAndQuotes() {
        val input = "\"She said, 'Look at section 4.2!'\" (100% accurate, no?)"
        val expected = "\"She said, 'Look at section 4.2!'\" (100% accurate, no?)"
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_preservesNumbersAndFormulas() {
        val input = "Item 1: 45.67 units, Model #X-900; Total = $1,250.00."
        val expected = "Item 1: 45.67 units, Model #X-900; Total = $1,250.00."
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_collapsesMultipleSpacesAndTabs() {
        val input = "Word1   \t   Word2 \t\t  Word3"
        val expected = "Word1 Word2 Word3"
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_removesStrayNonPrintableControlCharacters() {
        val input = "\u0000Hello\u0007 \u001FWorld\u007F!"
        val expected = "Hello World!"
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_normalizesWindowsAndMacCarriageReturns() {
        val input = "Line 1.\r\nLine 2.\rLine 3."
        val expected = "Line 1. Line 2. Line 3."
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_collapsesExcessiveNewlinesToDoubleNewlines() {
        val input = "Paragraph one.\n\n\n\n\nParagraph two."
        val expected = "Paragraph one.\n\nParagraph two."
        assertEquals(expected, PdfOcrTextNormalizer.normalize(input))
    }

    @Test
    fun normalize_edgeCases_preservesLegitimateHyphenationAndDehyphenatesObviousWrappedWords() {
        // "read-\ning" -> "reading"
        assertEquals("reading", PdfOcrTextNormalizer.normalize("read-\ning"))

        // "well-known" (inline) -> "well-known"
        assertEquals("well-known", PdfOcrTextNormalizer.normalize("well-known"))

        // "well-\nknown" (compound across line break) -> "well-known"
        assertEquals("well-known", PdfOcrTextNormalizer.normalize("well-\nknown"))

        // "Anglo-\nAmerican" (capitalized compound) -> "Anglo-American"
        assertEquals("Anglo-American", PdfOcrTextNormalizer.normalize("Anglo-\nAmerican"))

        // Bullet lists and minus signs preserved
        assertEquals("List: - Item A - Item B", PdfOcrTextNormalizer.normalize("List:\n- Item A\n- Item B"))
        assertEquals("List:\n\n- Item A\n\n- Item B", PdfOcrTextNormalizer.normalize("List:\n\n- Item A\n\n- Item B"))
        assertEquals("Equation: 10 - 5 = 5", PdfOcrTextNormalizer.normalize("Equation: 10 - 5 = 5"))
    }

    @Test
    fun normalize_edgeCases_preservesTitleAndNumericPunctuation() {
        // "Dr.\nSmith" -> "Dr. Smith"
        assertEquals("Dr. Smith", PdfOcrTextNormalizer.normalize("Dr.\nSmith"))

        // "10.\n30" -> "10.30"
        assertEquals("10.30", PdfOcrTextNormalizer.normalize("10.\n30"))

        // "1,\n250" -> "1,250"
        assertEquals("1,250", PdfOcrTextNormalizer.normalize("1,\n250"))
    }
}
