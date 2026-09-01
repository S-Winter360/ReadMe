package com.readme.app.reading.content.epub

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Pure Kotlin parser that safely opens an EPUB ZIP container, resolves the OPF package,
 * extracts package-level metadata, manifest items, and the linear spine sequence.
 */
object EpubPackageParser {

    private const val CONTAINER_XML_PATH = "META-INF/container.xml"
    private const val ENCRYPTION_XML_PATH = "META-INF/encryption.xml"
    private const val RIGHTS_XML_PATH = "META-INF/rights.xml"

    private val secureDocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (_: Throwable) {}
            try {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            } catch (_: Throwable) {}
            try {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            } catch (_: Throwable) {}
            isExpandEntityReferences = false
        }
    }

    /**
     * Parses an EPUB package directly from an [InputStream].
     *
     * @param inputStream Stream pointing to the EPUB ZIP container.
     * @param fallbackTitle Fallback title to use if the OPF lacks a dc:title element.
     * @return Fully parsed [EpubPackage] model.
     * @throws EpubException if the container is invalid, corrupted, DRM-protected, or missing key files.
     */
    fun parse(inputStream: InputStream, fallbackTitle: String = "Untitled EPUB"): EpubPackage {
        val smallXmlFiles = mutableMapOf<String, ByteArray>()
        var hasDrmFiles = false
        var entryCount = 0

        try {
            ZipInputStream(inputStream.buffered()).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    val rawName = entry.name

                    // Path traversal protection on zip entry names
                    if (rawName.contains("../") || rawName.startsWith("/") || rawName.startsWith("\\")) {
                        throw EpubContainerException("Insecure path in EPUB ZIP entry: $rawName")
                    }

                    val normalizedName = normalizePath("", rawName)

                    if (normalizedName == ENCRYPTION_XML_PATH || normalizedName == RIGHTS_XML_PATH) {
                        hasDrmFiles = true
                    }

                    // Only buffer small structural XML and OPF files to avoid high memory consumption
                    if (normalizedName.endsWith(".xml", ignoreCase = true) ||
                        normalizedName.endsWith(".opf", ignoreCase = true) ||
                        normalizedName == CONTAINER_XML_PATH
                    ) {
                        smallXmlFiles[normalizedName] = zis.readBytes()
                    }

                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: EpubException) {
            throw e
        } catch (e: Exception) {
            throw EpubContainerException("Failed to read EPUB ZIP container: ${e.message}", e)
        }

        if (entryCount == 0) {
            throw EpubContainerException("EPUB container is empty or not a valid ZIP file.")
        }

        // 1. Locate and parse META-INF/container.xml
        val containerBytes = smallXmlFiles[CONTAINER_XML_PATH]
            ?: throw EpubContainerException("Missing required container definition: $CONTAINER_XML_PATH")

        val opfPath = extractOpfPathFromContainer(containerBytes)
        val normalizedOpfPath = normalizePath("", opfPath)

        // 2. Locate OPF package content
        val opfBytes = smallXmlFiles[normalizedOpfPath]
            ?: throw EpubPackageException("Package document not found at: $normalizedOpfPath")

        // 3. Parse OPF package XML
        return parseOpfDocument(
            opfBytes = opfBytes,
            opfPath = normalizedOpfPath,
            fallbackTitle = fallbackTitle,
            isDrmProtected = hasDrmFiles
        )
    }

    /**
     * Parses container.xml to locate the package OPF full-path attribute.
     */
    private fun extractOpfPathFromContainer(containerBytes: ByteArray): String {
        return try {
            val builder = secureDocumentBuilderFactory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(containerBytes))
            doc.documentElement.normalize()

            val rootfiles = doc.getElementsByTagName("rootfile")
            if (rootfiles.length == 0) {
                throw EpubContainerException("No <rootfile> entries found in $CONTAINER_XML_PATH")
            }

            var opfPath: String? = null
            for (i in 0 until rootfiles.length) {
                val elem = rootfiles.item(i) as? Element ?: continue
                val fullPath = elem.getAttribute("full-path").trim()
                if (fullPath.isNotEmpty()) {
                    opfPath = fullPath
                    break
                }
            }

            opfPath?.takeIf { it.isNotBlank() }
                ?: throw EpubContainerException("Empty or invalid full-path attribute in $CONTAINER_XML_PATH")
        } catch (e: EpubException) {
            throw e
        } catch (e: Exception) {
            throw EpubContainerException("Malformed $CONTAINER_XML_PATH XML: ${e.message}", e)
        }
    }

    /**
     * Parses the OPF document extracting version, metadata, manifest items, and ordered spine.
     */
    private fun parseOpfDocument(
        opfBytes: ByteArray,
        opfPath: String,
        fallbackTitle: String,
        isDrmProtected: Boolean
    ): EpubPackage {
        return try {
            val builder = secureDocumentBuilderFactory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(opfBytes))
            doc.documentElement.normalize()

            val rootElement = doc.documentElement
            val version = rootElement.getAttribute("version").ifBlank { "3.0" }
            val opfDir = if (opfPath.contains('/')) opfPath.substringBeforeLast('/') else ""

            // 1. Metadata
            val metadata = extractMetadata(doc, fallbackTitle)

            // 2. Manifest
            val manifest = extractManifest(doc, opfDir)

            // 3. Spine
            val spine = extractSpine(doc, manifest)

            EpubPackage(
                version = version,
                opfPath = opfPath,
                metadata = metadata,
                manifest = manifest,
                spine = spine,
                isDrmProtected = isDrmProtected
            )
        } catch (e: EpubException) {
            throw e
        } catch (e: Exception) {
            throw EpubPackageException("Malformed OPF package XML at $opfPath: ${e.message}", e)
        }
    }

    private fun extractMetadata(doc: org.w3c.dom.Document, fallbackTitle: String): EpubMetadata {
        val titleNodes = getElementsByTagNameLocalOrPrefixed(doc, "title")
        val creatorNodes = getElementsByTagNameLocalOrPrefixed(doc, "creator")
        val identifierNodes = getElementsByTagNameLocalOrPrefixed(doc, "identifier")
        val languageNodes = getElementsByTagNameLocalOrPrefixed(doc, "language")
        val publisherNodes = getElementsByTagNameLocalOrPrefixed(doc, "publisher")

        val title = titleNodes.firstOrNull()?.textContent?.trim()?.ifBlank { null }
            ?: fallbackTitle.trim().ifBlank { "Untitled Document" }

        val author = creatorNodes.firstOrNull()?.textContent?.trim()?.ifBlank { null }
        val identifier = identifierNodes.firstOrNull()?.textContent?.trim()?.ifBlank { null }
        val language = languageNodes.firstOrNull()?.textContent?.trim()?.ifBlank { null }
        val publisher = publisherNodes.firstOrNull()?.textContent?.trim()?.ifBlank { null }

        return EpubMetadata(
            title = title,
            author = author,
            identifier = identifier,
            language = language,
            publisher = publisher
        )
    }

    private fun extractManifest(doc: org.w3c.dom.Document, opfDir: String): Map<String, EpubManifestItem> {
        val manifestMap = mutableMapOf<String, EpubManifestItem>()
        val itemNodes = getElementsByTagNameLocalOrPrefixed(doc, "item")

        for (i in itemNodes.indices) {
            val itemElem = itemNodes[i] as? Element ?: continue
            val id = itemElem.getAttribute("id").trim()
            val href = itemElem.getAttribute("href").trim()
            val mediaType = itemElem.getAttribute("media-type").trim()
            val properties = itemElem.getAttribute("properties").trim().ifBlank { null }

            if (id.isNotEmpty() && href.isNotEmpty()) {
                val fullPath = normalizePath(opfDir, href)
                manifestMap[id] = EpubManifestItem(
                    id = id,
                    href = href,
                    fullPath = fullPath,
                    mediaType = mediaType,
                    properties = properties
                )
            }
        }

        if (manifestMap.isEmpty()) {
            throw EpubPackageException("EPUB manifest is empty or missing valid <item> declarations.")
        }

        return manifestMap
    }

    private fun extractSpine(
        doc: org.w3c.dom.Document,
        manifest: Map<String, EpubManifestItem>
    ): List<EpubSpineItem> {
        val spineList = mutableListOf<EpubSpineItem>()
        val itemrefNodes = getElementsByTagNameLocalOrPrefixed(doc, "itemref")

        for (i in itemrefNodes.indices) {
            val itemrefElem = itemrefNodes[i] as? Element ?: continue
            val idref = itemrefElem.getAttribute("idref").trim()
            val linearAttr = itemrefElem.getAttribute("linear").trim().lowercase()
            val linear = linearAttr != "no" && linearAttr != "false"

            if (idref.isNotEmpty()) {
                val manifestItem = manifest[idref]
                spineList.add(
                    EpubSpineItem(
                        idRef = idref,
                        linear = linear,
                        manifestItem = manifestItem
                    )
                )
            }
        }

        if (spineList.isEmpty()) {
            throw EpubPackageException("EPUB spine is empty or missing valid <itemref> declarations.")
        }

        return spineList
    }

    /**
     * Resolves and normalizes relative paths inside the EPUB container, preventing path traversal attacks.
     */
    fun normalizePath(baseDir: String, relativePath: String): String {
        val cleanRelative = relativePath.replace('\\', '/').trim()
        val combined = if (baseDir.isBlank() || cleanRelative.startsWith('/')) {
            cleanRelative.removePrefix("/")
        } else {
            "${baseDir.removeSuffix("/")}/$cleanRelative"
        }

        val segments = combined.split('/')
        val resolved = mutableListOf<String>()

        for (segment in segments) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (resolved.isNotEmpty()) {
                        resolved.removeAt(resolved.size - 1)
                    } else {
                        throw EpubPackageException("Insecure path traversal in resource href: $relativePath")
                    }
                }
                else -> resolved.add(segment)
            }
        }

        return resolved.joinToString("/")
    }

    /**
     * Helper to find elements matching either the local name or prefixed name (e.g. "dc:title" or "title").
     */
    private fun getElementsByTagNameLocalOrPrefixed(doc: org.w3c.dom.Document, localName: String): List<Node> {
        val result = mutableListOf<Node>()

        // Check namespace-aware tag
        val dcNodes = doc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", localName)
        for (i in 0 until dcNodes.length) {
            result.add(dcNodes.item(i))
        }

        // Check prefixed tag
        val prefixed = doc.getElementsByTagName("dc:$localName")
        for (i in 0 until prefixed.length) {
            val node = prefixed.item(i)
            if (!result.contains(node)) result.add(node)
        }

        // Check un-prefixed tag
        val rawNodes = doc.getElementsByTagName(localName)
        for (i in 0 until rawNodes.length) {
            val node = rawNodes.item(i)
            if (!result.contains(node)) result.add(node)
        }

        return result
    }
}
