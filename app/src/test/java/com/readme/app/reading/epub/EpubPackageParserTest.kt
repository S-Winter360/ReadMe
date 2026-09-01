package com.readme.app.reading.epub

import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.content.epub.EpubContainerException
import com.readme.app.reading.content.epub.EpubException
import com.readme.app.reading.content.epub.EpubPackageException
import com.readme.app.reading.content.epub.EpubPackageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubPackageParserTest {

    private fun createEpubZipBytes(
        containerXml: String? = DEFAULT_CONTAINER_XML,
        opfPath: String = "OEBPS/content.opf",
        opfXml: String? = DEFAULT_OPF_XML,
        additionalEntries: Map<String, String> = emptyMap()
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // mimetype
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            // container.xml
            if (containerXml != null) {
                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write(containerXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // opf
            if (opfXml != null) {
                zos.putNextEntry(ZipEntry(opfPath))
                zos.write(opfXml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            // additional
            for ((path, content) in additionalEntries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun parse_validMinimalEpub_extractsMetadataManifestAndSpine() {
        val epubBytes = createEpubZipBytes()
        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes), "Fallback.epub")

        assertEquals("3.0", pkg.version)
        assertEquals("OEBPS/content.opf", pkg.opfPath)
        assertEquals("Alice in Wonderland", pkg.metadata.title)
        assertEquals("Lewis Carroll", pkg.metadata.author)
        assertEquals("en", pkg.metadata.language)
        assertFalse(pkg.isDrmProtected)

        assertEquals(3, pkg.manifest.size)
        val chapter1Item = pkg.manifest["ch1"]
        assertNotNull(chapter1Item)
        assertEquals("chapter1.xhtml", chapter1Item!!.href)
        assertEquals("OEBPS/chapter1.xhtml", chapter1Item.fullPath)
        assertEquals("application/xhtml+xml", chapter1Item.mediaType)

        assertEquals(2, pkg.spine.size)
        assertEquals("ch1", pkg.spine[0].idRef)
        assertTrue(pkg.spine[0].linear)
        assertEquals("OEBPS/chapter1.xhtml", pkg.spine[0].manifestItem?.fullPath)

        assertEquals("ch2", pkg.spine[1].idRef)
        assertEquals("OEBPS/chapter2.xhtml", pkg.spine[1].manifestItem?.fullPath)
    }

    @Test
    fun parse_containerXmlDiscovery_respectsCustomOpfPath() {
        val customContainer = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="book/package.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>""".trimIndent()

        val epubBytes = createEpubZipBytes(
            containerXml = customContainer,
            opfPath = "book/package.opf",
            opfXml = DEFAULT_OPF_XML
        )

        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes))
        assertEquals("book/package.opf", pkg.opfPath)
        assertEquals("Alice in Wonderland", pkg.metadata.title)
        assertEquals("book/chapter1.xhtml", pkg.manifest["ch1"]?.fullPath)
    }

    @Test
    fun parse_spineOrder_preservedExactly() {
        val customOpf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Order Test</dc:title>
                </metadata>
                <manifest>
                    <item id="c" href="c.xhtml" media-type="application/xhtml+xml"/>
                    <item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
                    <item id="b" href="b.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="b"/>
                    <itemref idref="a"/>
                    <itemref idref="c"/>
                </spine>
            </package>""".trimIndent()

        val epubBytes = createEpubZipBytes(opfXml = customOpf)
        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes))

        assertEquals(3, pkg.spine.size)
        assertEquals("b", pkg.spine[0].idRef)
        assertEquals("a", pkg.spine[1].idRef)
        assertEquals("c", pkg.spine[2].idRef)
    }

    @Test
    fun parse_missingContainerXml_throwsEpubContainerException() {
        val epubBytes = createEpubZipBytes(containerXml = null)
        try {
            EpubPackageParser.parse(ByteArrayInputStream(epubBytes))
            fail("Expected EpubContainerException")
        } catch (e: EpubContainerException) {
            assertTrue(e.message!!.contains("Missing required container definition"))
        }
    }

    @Test
    fun parse_malformedContainerXml_throwsEpubContainerException() {
        val epubBytes = createEpubZipBytes(containerXml = "<not valid xml")
        try {
            EpubPackageParser.parse(ByteArrayInputStream(epubBytes))
            fail("Expected EpubContainerException")
        } catch (e: EpubContainerException) {
            assertTrue(e.message!!.contains("Malformed"))
        }
    }

    @Test
    fun parse_missingOpfDocument_throwsEpubPackageException() {
        val epubBytes = createEpubZipBytes(opfXml = null)
        try {
            EpubPackageParser.parse(ByteArrayInputStream(epubBytes))
            fail("Expected EpubPackageException")
        } catch (e: EpubPackageException) {
            assertTrue(e.message!!.contains("Package document not found"))
        }
    }

    @Test
    fun parse_malformedOpfDocument_throwsEpubPackageException() {
        val epubBytes = createEpubZipBytes(opfXml = "<broken opf content <<")
        try {
            EpubPackageParser.parse(ByteArrayInputStream(epubBytes))
            fail("Expected EpubPackageException")
        } catch (e: EpubPackageException) {
            assertTrue(e.message!!.contains("Malformed OPF"))
        }
    }

    @Test
    fun parse_missingManifestItemReferencedBySpine_handledGracefullyWithoutCrash() {
        val customOpf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Missing Item Test</dc:title>
                </metadata>
                <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="non_existent_id"/>
                </spine>
            </package>""".trimIndent()

        val epubBytes = createEpubZipBytes(opfXml = customOpf)
        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes))

        assertEquals(2, pkg.spine.size)
        assertNotNull(pkg.spine[0].manifestItem)
        assertNull(pkg.spine[1].manifestItem)
        assertEquals("non_existent_id", pkg.spine[1].idRef)
    }

    @Test
    fun parse_emptyOrCorruptZip_throwsEpubContainerException() {
        val corruptBytes = "Not a zip file at all".toByteArray()
        try {
            EpubPackageParser.parse(ByteArrayInputStream(corruptBytes))
            fail("Expected EpubContainerException")
        } catch (e: EpubContainerException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun parse_titleFallbackToFilename_whenDcTitleIsMissing() {
        val noTitleOpf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:language>en</dc:language>
                </metadata>
                <manifest>
                    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="ch1"/>
                </spine>
            </package>""".trimIndent()

        val epubBytes = createEpubZipBytes(opfXml = noTitleOpf)
        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes), fallbackTitle = "MyBook.epub")

        assertEquals("MyBook.epub", pkg.metadata.title)
        assertNull(pkg.metadata.author)
    }

    @Test
    fun parse_drmEncryptionFile_detectedGracefully() {
        val epubBytes = createEpubZipBytes(
            additionalEntries = mapOf(
                "META-INF/encryption.xml" to "<encryption><EncryptedData/></encryption>"
            )
        )
        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes))
        assertTrue(pkg.isDrmProtected)
    }

    @Test
    fun parse_pathTraversalInHref_preventedSafely() {
        val traversalOpf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Traversal Test</dc:title>
                </metadata>
                <manifest>
                    <item id="bad" href="../../etc/passwd" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="bad"/>
                </spine>
            </package>""".trimIndent()

        val epubBytes = createEpubZipBytes(opfXml = traversalOpf)
        try {
            EpubPackageParser.parse(ByteArrayInputStream(epubBytes))
            fail("Expected EpubPackageException on directory traversal href")
        } catch (e: EpubPackageException) {
            assertTrue(e.message!!.contains("Insecure path traversal") || e.message!!.contains("Malformed"))
        }
    }

    @Test
    fun parse_zipEntryPathTraversal_rejectedImmediately() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("../sneaky.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }

        try {
            EpubPackageParser.parse(ByteArrayInputStream(baos.toByteArray()))
            fail("Expected EpubContainerException for zip entry path traversal")
        } catch (e: EpubContainerException) {
            assertTrue(e.message!!.contains("Insecure path"))
        }
    }

    @Test
    fun parse_epub2Format_extractsMetadataSuccessfully() {
        val epub2Opf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Classic Book</dc:title>
                    <dc:creator>Arthur Conan Doyle</dc:creator>
                    <dc:publisher>Standard Publications</dc:publisher>
                </metadata>
                <manifest>
                    <item id="c1" href="text/ch1.html" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="c1"/>
                </spine>
            </package>""".trimIndent()

        val epubBytes = createEpubZipBytes(opfXml = epub2Opf)
        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes))

        assertEquals("2.0", pkg.version)
        assertEquals("Classic Book", pkg.metadata.title)
        assertEquals("Arthur Conan Doyle", pkg.metadata.author)
        assertEquals("Standard Publications", pkg.metadata.publisher)
        assertEquals("OEBPS/text/ch1.html", pkg.manifest["c1"]?.fullPath)
        assertEquals(1, pkg.spine.size)
    }

    @Test
    fun parse_pathNormalization_handlesRelativeDotSegments() {
        val opfWithDots = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Dot Test</dc:title>
                </metadata>
                <manifest>
                    <item id="ch1" href="./sub/../ch1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="ch1"/>
                </spine>
            </package>""".trimIndent()

        val epubBytes = createEpubZipBytes(opfXml = opfWithDots)
        val pkg = EpubPackageParser.parse(ByteArrayInputStream(epubBytes))

        assertEquals("OEBPS/ch1.xhtml", pkg.manifest["ch1"]?.fullPath)
    }

    companion object {
        private val DEFAULT_CONTAINER_XML = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>""".trimIndent()

        private val DEFAULT_OPF_XML = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Alice in Wonderland</dc:title>
                    <dc:creator>Lewis Carroll</dc:creator>
                    <dc:language>en</dc:language>
                    <dc:identifier id="pub-id">urn:uuid:12345</dc:identifier>
                </metadata>
                <manifest>
                    <item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="ch1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                </spine>
            </package>""".trimIndent()
    }
}
