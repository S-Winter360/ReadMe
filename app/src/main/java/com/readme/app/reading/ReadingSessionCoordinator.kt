package com.readme.app.reading

import com.readme.app.reading.progress.ReadingProgress
import com.readme.app.reading.progress.ReadingProgressRepository
import com.readme.app.reading.progress.ReadingProgressValidator
import com.readme.app.speech.TtsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates format-agnostic document state and reading-session lifecycle
 * between [ReadingEngine], document loading, and speech state.
 *
 * Enforces session invariants:
 * 1. If activeDocument == null, readingPosition is null and does not resolve to an active document.
 * 2. ReadingPosition.documentId == activeDocument.documentId.
 * 3. Document replacement invalidates previous session operations and callbacks.
 * 4. Failed replacement clears active document and does not leave obsolete document active.
 */
class ReadingSessionCoordinator(
    val readingEngine: ReadingEngine = ReadingEngine(),
    val progressRepository: ReadingProgressRepository? = null
) {
    private val loadTokenCounter = AtomicLong(0L)

    private val _activeDocumentState = MutableStateFlow(ActiveDocumentState.None)
    val activeDocumentState: StateFlow<ActiveDocumentState> = _activeDocumentState.asStateFlow()

    private val _readingSessionState = MutableStateFlow(ActiveReadingSessionState.Idle)
    val readingSessionState: StateFlow<ActiveReadingSessionState> = _readingSessionState.asStateFlow()

    val hasActiveDocument: Boolean
        get() = _activeDocumentState.value.hasActiveDocument

    val currentDocumentId: String
        get() = _activeDocumentState.value.documentId

    val currentDocument: ReadingDocument
        get() = readingEngine.currentDocument

    /**
     * Initiates loading of a document, invalidating previous load operations,
     * resetting the active document state to [DocumentLoadState.Loading],
     * and clearing the previous document from the engine to prevent stale progression.
     *
     * @return a unique load operation token used to detect stale completions.
     */
    fun startLoading(displayName: String): Long {
        val token = loadTokenCounter.incrementAndGet()
        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
        _activeDocumentState.value = ActiveDocumentState(
            documentId = "",
            title = "",
            author = null,
            displayName = displayName,
            loadState = DocumentLoadState.Loading(displayName)
        )
        syncSessionState(speechState = TtsState.Stopped, errorMessage = null)
        return token
    }

    /**
     * Checks whether [token] matches the most recently issued load token.
     */
    fun isCurrentLoadToken(token: Long): Boolean {
        return token == loadTokenCounter.get()
    }

    /**
     * Called when a document has successfully parsed and loaded.
     * Validates [token] to reject stale async loads.
     */
    fun onDocumentLoaded(
        token: Long,
        document: ReadingDocument,
        displayName: String,
        isEphemeral: Boolean = false,
        sourcePackageName: String? = null,
        sourceAppLabel: String? = null,
        hasSuspendedPrimary: Boolean = false,
        suspendedPrimaryTitle: String? = null
    ): Boolean {
        if (!isCurrentLoadToken(token)) return false

        readingEngine.loadDocument(document)
        _activeDocumentState.value = ActiveDocumentState.fromDocument(
            document = document,
            displayName = displayName,
            loadState = DocumentLoadState.Loaded,
            isEphemeral = isEphemeral,
            sourcePackageName = sourcePackageName,
            sourceAppLabel = sourceAppLabel,
            hasSuspendedPrimary = hasSuspendedPrimary,
            suspendedPrimaryTitle = suspendedPrimaryTitle
        )
        syncSessionState(speechState = _readingSessionState.value.speechState, errorMessage = null)
        return true
    }

    /**
     * Called when document loading fails.
     * Validates [token] to reject stale async failures.
     * Clears any active document so an obsolete document is never presented as active.
     */
    fun onDocumentLoadFailed(token: Long, errorMessage: String): Boolean {
        if (!isCurrentLoadToken(token)) return false

        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
        readingEngine.setError()
        _activeDocumentState.value = ActiveDocumentState(
            documentId = "",
            title = "",
            author = null,
            displayName = "",
            loadState = DocumentLoadState.Error(errorMessage)
        )
        syncSessionState(speechState = TtsState.Stopped, errorMessage = errorMessage)
        return true
    }

    /**
     * Explicitly clears the active document and resets session state to Idle.
     */
    fun clearActiveDocument() {
        loadTokenCounter.incrementAndGet()
        readingEngine.loadDocument(ReadingDocument(id = "", title = "", sections = emptyList()))
        _activeDocumentState.value = ActiveDocumentState.None
        _readingSessionState.value = ActiveReadingSessionState.Idle
    }

    /**
     * Starts reading session according to unified semantics:
     * - If no active document: returns null safely without speaking.
     * - If stopped or idle with a valid restored position: resumes from current position.
     * - If completed: restarts from the beginning.
     * - If idle / newly loaded: starts from the beginning.
     */
    fun startReading(): ReadingSegment? {
        if (!hasActiveDocument) return null

        val hasPosition = readingEngine.currentPosition.value != null
        val isStoppedOrRestored = readingEngine.readingState.value == ReadingSessionState.Stopped ||
            (readingEngine.readingState.value == ReadingSessionState.Idle && hasPosition)

        val segment = if (isStoppedOrRestored && hasPosition) {
            readingEngine.resumeFromCurrentPosition()
        } else {
            readingEngine.startFromBeginning()
        }

        syncSessionState(speechState = TtsState.Speaking)
        return segment
    }

    /**
     * Stops the active reading session, preserving current position and active document.
     */
    fun stopReading() {
        readingEngine.stop()
        syncSessionState(speechState = TtsState.Stopped)
    }

    /**
     * Advances to the next reading segment.
     */
    fun advanceReading(): ReadingSegment? {
        val segment = readingEngine.advance()
        syncSessionState()
        return segment
    }

    /**
     * Called when speech completes for the final segment.
     */
    fun onReadingCompleted() {
        syncSessionState(speechState = TtsState.Stopped)
    }

    /**
     * Called when speech or reading encounters an error.
     */
    fun onReadingError(errorMessage: String? = null) {
        readingEngine.setError()
        syncSessionState(speechState = TtsState.Error, errorMessage = errorMessage)
    }

    /**
     * Updates speech engine state in the unified session.
     */
    fun updateSpeechState(ttsState: TtsState) {
        syncSessionState(speechState = ttsState)
    }

    /**
     * Sets position explicitly, enforcing that the position belongs to the active document.
     */
    fun setPosition(position: ReadingPosition) {
        if (!hasActiveDocument || position.documentId != currentDocumentId) return
        readingEngine.setPosition(position)
        syncSessionState()
    }

    /**
     * Synchronizes [ActiveReadingSessionState] with [ReadingEngine] and [ActiveDocumentState],
     * enforcing document-position invariants.
     */
    fun syncSessionState(
        speechState: TtsState = _readingSessionState.value.speechState,
        errorMessage: String? = _readingSessionState.value.errorMessage
    ) {
        val activeDocId = if (hasActiveDocument) currentDocumentId else null
        val position = readingEngine.currentPosition.value
        val safePosition = if (activeDocId != null && position?.documentId == activeDocId) {
            position
        } else {
            null
        }

        _readingSessionState.value = ActiveReadingSessionState(
            activeDocumentId = activeDocId,
            sessionState = readingEngine.readingState.value,
            currentPosition = safePosition,
            speechState = speechState,
            errorMessage = errorMessage,
            isEphemeral = _activeDocumentState.value.isEphemeral
        )
    }

    /**
     * Restores saved progress for [document] if valid.
     * Enforces that completed documents are not resumed mid-reading,
     * and invalid progress is safely rejected.
     */
    suspend fun restoreProgress(document: ReadingDocument): ReadingProgress? {
        val repo = progressRepository ?: return null
        val progress = repo.loadProgress(document.id) ?: return null
        if (!ReadingProgressValidator.validate(progress, document)) {
            return null
        }
        if (!progress.isCompleted) {
            val position = progress.toReadingPosition()
            setPosition(position)
        }
        return progress
    }

    /**
     * Persists current reading position to the repository.
     */
    suspend fun saveCurrentProgress(isCompleted: Boolean = false): ReadingProgress? {
        val repo = progressRepository ?: return null
        val position = readingEngine.currentPosition.value ?: return null
        val doc = readingEngine.currentDocument
        if (doc.id.isBlank() || position.documentId != doc.id) return null

        val progress = ReadingProgress.fromPosition(
            position = position,
            sourceType = doc.metadata.sourceType,
            isCompleted = isCompleted,
            lastUpdated = System.currentTimeMillis()
        )
        repo.saveProgress(progress)
        return progress
    }

    /**
     * Clears persisted progress for [documentId].
     */
    suspend fun clearProgress(documentId: String) {
        progressRepository?.clearProgress(documentId)
    }
}
