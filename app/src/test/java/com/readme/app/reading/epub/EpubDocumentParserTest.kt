package com.readme.app.reading.epub

import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.content.epub.EpubDocumentParser
import com.readme.app.reading.content.epub.EpubDrmException
import com.readme.app.reading.content.epub.EpubPackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubDocumentParserTest {

    private fun createEpubZipBytes(
        containerXml: String = DEFAULT_CONTAINER_XML,
        opfPath: String = "OEBPS/content.opf",
        opfXml: String = DEFAULT_OPF_XML,
        entries: Map<String, String> = emptyMap()
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // mimetype
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            // container.xml
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // opf
            zos.putNextEntry(ZipEntry(opfPath))
            zos.write(opfXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // additional chapter entries
            for ((path, content) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    companion object {
        private const val DEFAULT_CONTAINER_XML = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
    <rootfiles>
        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
    </rootfiles>
</container>"""

        private const val DEFAULT_OPF_XML = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Farewell, Nikola</dc:title>
        <dc:creator>Guy Boothby</dc:creator>
        <dc:language>en</dc:language>
    </metadata>
    <manifest>
        <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
        <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    </manifest>
    <spine>
        <itemref idref="c1"/>
        <itemref idref="c2"/>
    </spine>
</package>"""
    }

    @Test
    fun parse_validMultiChapterEpub_producesReadingDocumentWithSectionsAndSegments() {
        val ch1Xhtml = """
            <html>
            <body>
                <h1>Chapter 1: The Arrival</h1>
                <p>Mr. Nikola stepped off the vessel at midnight. The air was cold and damp.</p>
                <p>He looked towards the distant hills.</p>
            </body>
            </html>
        """.trimIndent()

        val ch2Xhtml = """
            <html>
            <body>
                <h1>Chapter 2: The Secret</h1>
                <p>Inside the laboratory, Dr. Watson was waiting patiently.</p>
            </body>
            </html>
        """.trimIndent()

        val epubBytes = createEpubZipBytes(
            entries = mapOf(
                "OEBPS/text/ch1.xhtml" to ch1Xhtml,
                "OEBPS/text/ch2.xhtml" to ch2Xhtml
            )
        )

        val doc = EpubDocumentParser.parse(
            inputStream = ByteArrayInputStream(epubBytes),
            sourceIdentifier = "test_epub_1"
        )

        assertEquals("Farewell, Nikola", doc.title)
        assertEquals("Guy Boothby", doc.author)
        assertEquals(ReadingDocumentSourceType.EPUB, doc.sourceType)
        assertEquals(2, doc.sections.size)

        // Section 1 checks
        val sec1 = doc.sections[0]
        assertEquals("Chapter 1: The Arrival", sec1.title)
        // Chapter heading + 2 sentences in para 1 + 1 sentence in para 2 = 4 segments
        assertEquals(4, sec1.segments.size)
        assertEquals("Chapter 1: The Arrival", sec1.segments[0].text)
        assertEquals("Mr. Nikola stepped off the vessel at midnight.", sec1.segments[1].text)
        assertEquals("The air was cold and damp.", sec1.segments[2].text)
        assertEquals("He looked towards the distant hills.", sec1.segments[3].text)

        // Section 2 checks
        val sec2 = doc.sections[1]
        assertEquals("Chapter 2: The Secret", sec2.title)
        assertEquals(2, sec2.segments.size)
        assertEquals("Chapter 2: The Secret", sec2.segments[0].text)
        assertEquals("Inside the laboratory, Dr. Watson was waiting patiently.", sec2.segments[1].text)

        // Total segments
        assertEquals(6, doc.allSegments().size)
    }

    @Test
    fun parse_spineOrderPreserved_independentOfZipEntryOrder() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Spine Order Test</dc:title>
    </metadata>
    <manifest>
        <item id="item-b" href="ch_b.xhtml" media-type="application/xhtml+xml"/>
        <item id="item-a" href="ch_a.xhtml" media-type="application/xhtml+xml"/>
    </manifest>
    <spine>
        <itemref idref="item-a"/>
        <itemref idref="item-b"/>
    </spine>
</package>"""

        // ZIP entries added in reverse order (ch_b before ch_a)
        val epubBytes = createEpubZipBytes(
            opfXml = opf,
            entries = mapOf(
                "OEBPS/ch_b.xhtml" to "<html><body><h1>Second</h1><p>Content B.</p></body></html>",
                "OEBPS/ch_a.xhtml" to "<html><body><h1>First</h1><p>Content A.</p></body></html>"
            )
        )

        val doc = EpubDocumentParser.parse(ByteArrayInputStream(epubBytes))
        assertEquals(2, doc.sections.size)
        assertEquals("First", doc.sections[0].title)
        assertEquals("Second", doc.sections[1].title)
    }

    @Test
    fun parse_nonLinearSpineItem_isSkipped() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Non-Linear Test</dc:title>
    </metadata>
    <manifest>
        <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
        <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    </manifest>
    <spine>
        <itemref idref="cover" linear="no"/>
        <itemref idref="c1"/>
    </spine>
</package>"""

        val epubBytes = createEpubZipBytes(
            opfXml = opf,
            entries = mapOf(
                "OEBPS/cover.xhtml" to "<html><body><p>Cover Image Only</p></body></html>",
                "OEBPS/ch1.xhtml" to "<html><body><h1>Chapter One</h1><p>Story text.</p></body></html>"
            )
        )

        val doc = EpubDocumentParser.parse(ByteArrayInputStream(epubBytes))
        assertEquals(1, doc.sections.size)
        assertEquals("Chapter One", doc.sections[0].title)
    }

    @Test
    fun parse_deterministicIds_produceConsistentIdsAcrossParses() {
        val epubBytes = createEpubZipBytes(
            entries = mapOf(
                "OEBPS/text/ch1.xhtml" to "<html><body><p>Hello world.</p></body></html>"
            )
        )

        val doc1 = EpubDocumentParser.parse(ByteArrayInputStream(epubBytes), sourceIdentifier = "uri_consistent_123")
        val doc2 = EpubDocumentParser.parse(ByteArrayInputStream(epubBytes), sourceIdentifier = "uri_consistent_123")

        assertEquals(doc1.id, doc2.id)
        assertEquals(doc1.sections[0].id, doc2.sections[0].id)
        assertEquals(doc1.sections[0].segments[0].id, doc2.sections[0].segments[0].id)
    }

    @Test(expected = EpubDrmException::class)
    fun parse_drmProtectedEpub_throwsEpubDrmException() {
        val epubBytes = createEpubZipBytes(
            entries = mapOf(
                "META-INF/encryption.xml" to "<encryption></encryption>",
                "OEBPS/text/ch1.xhtml" to "<html><body><p>Encrypted</p></body></html>"
            )
        )

        EpubDocumentParser.parse(ByteArrayInputStream(epubBytes))
    }

    @Test
    fun parse_emptyChaptersSkipped_andFallbackSectionTitleUsedWhenNoHeading() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Fallback Title Book</dc:title>
    </metadata>
    <manifest>
        <item id="empty-ch" href="text/empty_page.xhtml" media-type="application/xhtml+xml"/>
        <item id="narrative-ch" href="text/chapter_intro.xhtml" media-type="application/xhtml+xml"/>
    </manifest>
    <spine>
        <itemref idref="empty-ch"/>
        <itemref idref="narrative-ch"/>
    </spine>
</package>"""

        val epubBytes = createEpubZipBytes(
            opfXml = opf,
            entries = mapOf(
                "OEBPS/text/empty_page.xhtml" to "<html><body></body></html>",
                "OEBPS/text/chapter_intro.xhtml" to "<html><body><p>No heading here, just story text.</p></body></html>"
            )
        )

        val doc = EpubDocumentParser.parse(ByteArrayInputStream(epubBytes))
        assertEquals(1, doc.sections.size)
        // cleanFallbackSectionTitle derived from "chapter_intro"
        assertEquals("Chapter Intro", doc.sections[0].title)
        assertEquals(1, doc.sections[0].segments.size)
        assertEquals("No heading here, just story text.", doc.sections[0].segments[0].text)
    }

    @Test(expected = EpubPackageException::class)
    fun parse_noReadableChapters_throwsEpubPackageException() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
    <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:title>Empty Book</dc:title>
    </metadata>
    <manifest>
        <item id="c1" href="empty.xhtml" media-type="application/xhtml+xml"/>
    </manifest>
    <spine>
        <itemref idref="c1"/>
    </spine>
</package>"""

        val epubBytes = createEpubZipBytes(
            opfXml = opf,
            entries = mapOf(
                "OEBPS/empty.xhtml" to "<html><body></body></html>"
            )
        )

        EpubDocumentParser.parse(ByteArrayInputStream(epubBytes))
    }
}
