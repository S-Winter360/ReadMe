package com.readme.app.reading.content.epub

/**
 * Top-level immutable data model representing the parsed structure of an EPUB package.
 *
 * @property version The EPUB standard version (e.g. "2.0" or "3.0").
 * @property opfPath The normalized path to the OPF package document inside the ZIP container.
 * @property metadata Basic Dublin Core metadata (title, author, etc.).
 * @property manifest Map of manifest items keyed by their item ID.
 * @property spine Ordered list of spine items defining the reading sequence.
 * @property isDrmProtected True if DRM or encryption metadata was detected in the container.
 */
data class EpubPackage(
    val version: String = "3.0",
    val opfPath: String,
    val metadata: EpubMetadata,
    val manifest: Map<String, EpubManifestItem>,
    val spine: List<EpubSpineItem>,
    val isDrmProtected: Boolean = false
)
