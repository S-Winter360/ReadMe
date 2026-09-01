package com.readme.app.reading.content.epub

import java.io.IOException

/**
 * Base exception for all EPUB container and package parsing failures.
 */
open class EpubException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Thrown when the EPUB ZIP container is corrupted, empty, or missing required container files.
 */
class EpubContainerException(message: String, cause: Throwable? = null) : EpubException(message, cause)

/**
 * Thrown when the OPF package document cannot be parsed or lacks essential manifest/spine declarations.
 */
class EpubPackageException(message: String, cause: Throwable? = null) : EpubException(message, cause)

/**
 * Thrown when the EPUB file contains DRM or encryption metadata that prevents reading.
 */
class EpubDrmException(message: String, cause: Throwable? = null) : EpubException(message, cause)
