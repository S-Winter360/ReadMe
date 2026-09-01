package com.readme.app.reading.content.epub

/**
 * Represents an entry in the EPUB spine specifying the sequential reading order.
 *
 * @property idRef The ID of the referenced manifest item.
 * @property linear Whether this item is part of the primary linear reading order (defaults to true).
 * @property manifestItem The resolved [EpubManifestItem] if found in the manifest, or null if unresolvable.
 */
data class EpubSpineItem(
    val idRef: String,
    val linear: Boolean = true,
    val manifestItem: EpubManifestItem? = null
)
