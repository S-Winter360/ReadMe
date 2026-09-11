package com.readme.app.reading.session

import com.readme.app.reading.ReadingDocument
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSessionState

/**
 * Phase 9H: High-level classification of the active reading session context.
 */
enum class ReadingContextType {
    PRIMARY,
    EPHEMERAL_EXTERNAL
}

/**
 * Context state for a persistent primary ReadMe document (TXT, EPUB, PDF, OCR PDF).
 */
data class PrimaryReadingContext(
    val document: ReadingDocument,
    val displayName: String,
    val savedPosition: ReadingPosition? = null,
    val sessionState: ReadingSessionState = ReadingSessionState.Idle,
    val runtimeGeneration: Long = 0L
)

/**
 * In-memory snapshot of a primary reading session suspended to read external app content.
 * Does not duplicate large raw binaries or persistent files; retains the in-memory
 * [ReadingDocument] structure to allow zero-loss restoration upon return.
 */
data class SuspendedPrimaryReadingContext(
    val document: ReadingDocument,
    val displayName: String,
    val savedPosition: ReadingPosition?,
    val priorSessionState: ReadingSessionState,
    val runtimeGeneration: Long
)

/**
 * Context state for a temporary, in-memory cross-app reading session.
 * Exclusively ephemeral: never persisted to disk, DataStore, or progress repositories.
 */
data class EphemeralReadingContext(
    val document: ReadingDocument,
    val sourcePackageName: String,
    val sourceAppLabel: String? = null,
    val snapshotIdentity: Long = 0L,
    val generation: Long = 0L,
    val currentPosition: ReadingPosition? = null
)
