package com.readme.app.reading.epub

import com.readme.app.reading.content.epub.EpubXhtmlExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubXhtmlExtractorTest {

    @Test
    fun extract_singleParagraph_extractsText() {
        val xhtml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <p>This is a single narrative paragraph.</p>
            </body>
            </html>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals(listOf("This is a single narrative paragraph."), result.paragraphs)
        assertNull(result.title)
    }

    @Test
    fun extract_multipleParagraphs_preservesOrderAndBoundaries() {
        val xhtml = """
            <html xmlns="http://www.w3.org/1999/xhtml">
            <body>
                <p>The first paragraph of the book.</p>
                <p>The second paragraph follows immediately.</p>
                <p>And here is the third paragraph.</p>
            </body>
            </html>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals(3, result.paragraphs.size)
        assertEquals("The first paragraph of the book.", result.paragraphs[0])
        assertEquals("The second paragraph follows immediately.", result.paragraphs[1])
        assertEquals("And here is the third paragraph.", result.paragraphs[2])
    }

    @Test
    fun extract_headingAndTitle_extractsFirstHeadingAsTitleAndParagraph() {
        val xhtml = """
            <html>
            <body>
                <h1>Chapter I. The Mysterious Island</h1>
                <p>It was a calm morning on the open sea.</p>
            </body>
            </html>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals("Chapter I. The Mysterious Island", result.title)
        assertEquals(2, result.paragraphs.size)
        assertEquals("Chapter I. The Mysterious Island", result.paragraphs[0])
        assertEquals("It was a calm morning on the open sea.", result.paragraphs[1])
    }

    @Test
    fun extract_inlineElements_preservesSentenceFlowWithoutFragmentation() {
        val xhtml = """
            <p>She <em>really</em> wanted to <strong>leave</strong> the room via <a href="door.html">the side door</a> immediately.</p>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals(1, result.paragraphs.size)
        assertEquals("She really wanted to leave the room via the side door immediately.", result.paragraphs[0])
    }

    @Test
    fun extract_htmlEntities_decodesNamedAndNumericEntities() {
        val xhtml = """
            <p>Dr. Watson &amp; Mr. Holmes said, &ldquo;It&apos;s elementary!&rdquo; &mdash; price was &euro;100 &#8212; test &#x26; check.</p>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals(1, result.paragraphs.size)
        assertEquals("Dr. Watson & Mr. Holmes said, “It's elementary!” — price was €100 — test & check.", result.paragraphs[0])
    }

    @Test
    fun extract_scriptAndStyleRemoval_completelyIgnoresScriptAndStyleContent() {
        val xhtml = """
            <html>
            <head>
                <style>body { font-size: 16px; color: red; }</style>
                <script type="text/javascript">var secret = "do not read me";</script>
            </head>
            <body>
                <script>console.log("embedded script");</script>
                <p>This is the real narrative text.</p>
                <style>.hidden { display: none; }</style>
                <noscript>JavaScript is required</noscript>
            </body>
            </html>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals(listOf("This is the real narrative text."), result.paragraphs)
        assertFalse(result.paragraphs.any { it.contains("secret") || it.contains("console") || it.contains("font-size") })
    }

    @Test
    fun extract_divsAndBlockquotesAndLists_preservesParagraphStructure() {
        val xhtml = """
            <div>
                <h2>Section A</h2>
                <div class="para">First inner text block.</div>
                <blockquote>“To be or not to be.”</blockquote>
                <ul>
                    <li>Item 1</li>
                    <li>Item 2</li>
                </ul>
            </div>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals("Section A", result.title)
        assertEquals(5, result.paragraphs.size)
        assertEquals("Section A", result.paragraphs[0])
        assertEquals("First inner text block.", result.paragraphs[1])
        assertEquals("“To be or not to be.”", result.paragraphs[2])
        assertEquals("Item 1", result.paragraphs[3])
        assertEquals("Item 2", result.paragraphs[4])
    }

    @Test
    fun extract_emptyOrWhitespaceOnlyXhtml_returnsEmptyParagraphs() {
        val xhtml = "   \n\t  "
        val result = EpubXhtmlExtractor.extract(xhtml, fallbackTitle = "Fallback")
        assertEquals("Fallback", result.title)
        assertTrue(result.paragraphs.isEmpty())
    }

    @Test
    fun extract_malformedXhtml_handledSafelyWithoutCrashing() {
        val malformed = "<p>Unclosed paragraph with <b>bold and <i>italic <br> broken <a href='foo'>links"
        val result = EpubXhtmlExtractor.extract(malformed)
        assertTrue(result.paragraphs.isNotEmpty())
        assertTrue(result.paragraphs[0].contains("Unclosed paragraph with bold and italic"))
    }

    @Test
    fun extract_imagesWithoutOcr_ignoresImageTagsSafely() {
        val xhtml = """
            <p>Look at this map: <img src="images/map.png" alt="Island Map" /> of the territory.</p>
        """.trimIndent()

        val result = EpubXhtmlExtractor.extract(xhtml)
        assertEquals(1, result.paragraphs.size)
        assertEquals("Look at this map: of the territory.", result.paragraphs[0])
    }

    @Test
    fun extract_latin1Encoding_detectsAndDecodesCorrectly() {
        val latin1Xml = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><body><p>Caf\u00E9</p></body></html>"
        val bytes = latin1Xml.toByteArray(Charsets.ISO_8859_1)

        val result = EpubXhtmlExtractor.extract(bytes)
        assertEquals(1, result.paragraphs.size)
        assertEquals("Café", result.paragraphs[0])
    }
}
