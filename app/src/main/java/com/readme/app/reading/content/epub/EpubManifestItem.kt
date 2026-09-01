package com.readme.app.reading.content.epub

/**
 * Represents an item defined in the EPUB package manifest.
 *
 * @property id The unique ID of the manifest item.
 * @property href The relative path to the resource as declared in the OPF.
 * @property fullPath The resolved, normalized ZIP container path to the resource.
 * @property mediaType The MIME type of the resource (e.g. application/xhtml+xml).
 * @property properties Optional properties attribute (e.g. nav, cover-image).
 */
data class EpubManifestItem(
    val id: String,
    val href: String,
    val fullPath: String,
    val mediaType: String,
    val properties: String? = null
)
