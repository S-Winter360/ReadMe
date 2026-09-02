package com.readme.app.reading.content.epub

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingDocumentMetadata
import com.readme.app.reading.ReadingDocumentSourceType
import com.readme.app.reading.ReadingSection
import com.readme.app.reading.ReadingSegment
import com.readme.app.reading.content.TxtDocumentParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Pure Kotlin parser that converts an EPUB ZIP container into a fully structured [ReadingDocument]
 * containing ordered [ReadingSection]s and speech-ready [ReadingSegment]s.
 *
 * Execution flow:
 * 1. Safely inspects the container and resolves the OPF package document.
 * 2. Validates package metadata, manifest items, and the linear spine sequence.
 * 3. Extracts XHTML content for each linear spine item in strict reading order.
 * 4. Extracts chapter titles and paragraphs using [EpubXhtmlExtractor].
 * 5. Segments paragraphs into sentences using shared [TxtDocumentParser] logic.
 * 6. Generates deterministic IDs for documents, sections, and segments.
 */
object EpubDocumentParser {

    /**
     * Parses an EPUB container from [inputStream] into a structured [ReadingDocument].
     *
     * @param inputStream Stream pointing to the EPUB ZIP container.
     * @param fallbackTitle Title to use if the EPUB lacks a dc:title element.
     * @param sourceIdentifier Stable identifier used to generate deterministic document and section IDs.
     * @return A fully populated [ReadingDocument].
     * @throws EpubException if parsing fails, DRM is detected, or no readable text is found.
     */
    fun parse(
        inputStream: InputStream,
        fallbackTitle: String = "Untitled EPUB",
        sourceIdentifier: String = "epub_doc"
    ): ReadingDocument {
        // Read stream into a byte array once so we can safely perform package analysis and chapter extraction
        val epubBytes = if (inputStream is ByteArrayInputStream) {
            inputStream.readBytes()
        } else {
            val baos = ByteArrayOutputStream()
            inputStream.copyTo(baos)
            baos.toByteArray()
        }

        if (epubBytes.isEmpty()) {
            throw EpubContainerException("EPUB container is empty.")
        }

        // 1. Parse EPUB package structure
        val epubPackage = EpubPackageParser.parse(ByteArrayInputStream(epubBytes), fallbackTitle)

        if (epubPackage.isDrmProtected) {
            throw EpubDrmException("DRM-protected EPUB files are not supported.")
        }

        val linearSpine = epubPackage.spine.filter { it.linear }
        if (linearSpine.isEmpty()) {
            throw EpubPackageException("EPUB spine contains no readable linear content.")
        }

        // 2. Identify required resource paths
        val neededPaths = mutableSetOf<String>()
        for (spineItem in linearSpine) {
            val manifestItem = spineItem.manifestItem ?: epubPackage.manifest[spineItem.idRef]
            if (manifestItem != null) {
                neededPaths.add(manifestItem.fullPath)
            }
        }

        // 3. Extract only required chapter entries from the ZIP
        val chapterBytesMap = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(epubBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name
                if (!rawName.contains("../") && !rawName.startsWith("/") && !rawName.startsWith("\\")) {
                    val normalized = EpubPackageParser.normalizePath("", rawName)
                    if (neededPaths.contains(normalized)) {
                        chapterBytesMap[normalized] = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 4. Generate deterministic document ID
        val cleanTitleSlug = epubPackage.metadata.title
            .filter { it.isLetterOrDigit() }
            .lowercase()
            .take(30)
            .ifEmpty { "epub" }
        val sourceHash = sourceIdentifier.hashCode().toUInt()
        val docId = "epub_${cleanTitleSlug}_$sourceHash"

        // 5. Convert each spine item into a ReadingSection in strict spine order
        val sections = mutableListOf<ReadingSection>()
        var globalSegmentIndex = 0

        for ((spineIndex, spineItem) in linearSpine.withIndex()) {
            val manifestItem = spineItem.manifestItem ?: epubPackage.manifest[spineItem.idRef] ?: continue
            val chapterBytes = chapterBytesMap[manifestItem.fullPath] ?: continue

            val fallbackSectionTitle = cleanFallbackSectionTitle(manifestItem.href, spineIndex + 1)
            val extracted = EpubXhtmlExtractor.extract(chapterBytes, fallbackSectionTitle)

            val sectionTitle = extracted.title ?: fallbackSectionTitle
            val sectionSegments = mutableListOf<ReadingSegment>()

            for (paragraph in extracted.paragraphs) {
                val sentences = TxtDocumentParser.splitIntoSentences(paragraph)
                for (sentence in sentences) {
                    val trimmed = sentence.trim()
                    if (trimmed.isNotBlank()) {
                        sectionSegments.add(
                            ReadingSegment(
                                id = "${docId}_sec_${spineIndex}_seg_$globalSegmentIndex",
                                text = trimmed
                            )
                        )
                        globalSegmentIndex++
                    }
                }
            }

            if (sectionSegments.isNotEmpty()) {
                sections.add(
                    ReadingSection(
                        id = "${docId}_sec_$spineIndex",
                        title = sectionTitle,
                        segments = sectionSegments
                    )
                )
            }
        }

        if (sections.isEmpty()) {
            throw EpubPackageException("No readable chapter text could be extracted from this EPUB.")
        }

        return ReadingDocument(
            id = docId,
            metadata = ReadingDocumentMetadata(
                title = epubPackage.metadata.title,
                author = epubPackage.metadata.author,
                sourceType = ReadingDocumentSourceType.EPUB
            ),
            sections = sections
        )
    }

    private fun cleanFallbackSectionTitle(href: String, sectionNumber: Int): String {
        val base = href.substringAfterLast('/').substringBeforeLast('.')
        val clean = base.replace(Regex("[-_]+"), " ").trim()
        return if (clean.isNotBlank() && clean.length <= 40 && !clean.all { it.isDigit() }) {
            clean.split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        } else {
            "Section $sectionNumber"
        }
    }
}
