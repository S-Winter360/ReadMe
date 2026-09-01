package com.readme.app.reading

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foundation for the Reading Engine.
 * Responsible for holding the current ReadingDocument, managing reading session state,
 * and progressing through segments in deterministic reading order.
 * Does not interact directly with Android TextToSpeech.
 */
class ReadingEngine(
    initialDocument: ReadingDocument = ReadingDocument(id = "", title = "", sections = emptyList())
) {
    private val _readingState = MutableStateFlow(ReadingSessionState.Idle)
    val readingState: StateFlow<ReadingSessionState> = _readingState.asStateFlow()

    private val _currentSegmentIndex = MutableStateFlow(0)
    val currentSegmentIndex: StateFlow<Int> = _currentSegmentIndex.asStateFlow()

    private val _currentSegment = MutableStateFlow<ReadingSegment?>(null)
    val currentSegment: StateFlow<ReadingSegment?> = _currentSegment.asStateFlow()

    private val _currentPosition = MutableStateFlow<ReadingPosition?>(null)
    val currentPosition: StateFlow<ReadingPosition?> = _currentPosition.asStateFlow()

    var currentDocument: ReadingDocument = initialDocument
        private set

    /**
     * Resolves a [ReadingPosition] against the currently loaded document.
     */
    fun resolvePosition(position: ReadingPosition?): ReadingSegment? {
        return currentDocument.resolvePosition(position)
    }

    /**
     * Loads a new document and resets session state.
     */
    fun loadDocument(document: ReadingDocument) {
        stop()
        currentDocument = document
        reset()
    }

    /**
     * Returns all reading segments in deterministic reading order.
     */
    fun getSegments(): List<ReadingSegment> = currentDocument.allSegments()

    /**
     * Total number of segments in the current document.
     */
    fun totalSegments(): Int = getSegments().size

    /**
     * Returns the segment at the specified index, or null if out of bounds.
     */
    fun getSegment(index: Int): ReadingSegment? {
        val segments = getSegments()
        return if (index in segments.indices) segments[index] else null
    }

    /**
     * Starts a new reading session from the beginning (segment 0).
     * Skips initial blank segments if any.
     * Returns the first playable segment or null if the document has no valid content.
     */
    fun startFromBeginning(): ReadingSegment? {
        val segments = getSegments()
        if (segments.isEmpty()) {
            _currentSegmentIndex.value = 0
            _currentSegment.value = null
            _currentPosition.value = null
            _readingState.value = ReadingSessionState.Completed
            return null
        }

        var index = 0
        while (index < segments.size && segments[index].text.isBlank()) {
            index++
        }

        if (index >= segments.size) {
            _currentSegmentIndex.value = segments.size
            _currentSegment.value = null
            _currentPosition.value = null
            _readingState.value = ReadingSessionState.Completed
            return null
        }

        _currentSegmentIndex.value = index
        _currentSegment.value = segments[index]
        _currentPosition.value = currentDocument.positionForIndex(index)
        _readingState.value = ReadingSessionState.Reading
        return segments[index]
    }

    /**
     * Legacy / convenience alias for starting a session from the beginning.
     */
    fun startSession(): ReadingSegment? = startFromBeginning()

    /**
     * Resumes reading from [currentPosition] if a valid stopped position exists for the current document.
     * If [currentPosition] is null, state is Completed, or position resolution fails,
     * safely falls back to [startFromBeginning].
     * Returns the segment to be spoken, or null if document has no content.
     */
    fun resumeFromCurrentPosition(): ReadingSegment? {
        val pos = _currentPosition.value
        if (pos == null || _readingState.value == ReadingSessionState.Completed) {
            return startFromBeginning()
        }

        // Verify document match
        if (pos.documentId != currentDocument.id || currentDocument.id.isEmpty()) {
            return startFromBeginning()
        }

        val segments = getSegments()
        if (segments.isEmpty()) {
            return startFromBeginning()
        }

        // Attempt to resolve position against current document
        val resolvedSegment = currentDocument.resolvePosition(pos)
        if (resolvedSegment == null) {
            return startFromBeginning()
        }

        // Find matching segment index
        val index = if (pos.segmentIndex in segments.indices && segments[pos.segmentIndex].id == resolvedSegment.id) {
            pos.segmentIndex
        } else {
            segments.indexOfFirst { it.id == resolvedSegment.id }
        }

        if (index == -1 || index >= segments.size) {
            return startFromBeginning()
        }

        // If the targeted segment is blank, advance to the next non-blank segment
        var targetIndex = index
        while (targetIndex < segments.size && segments[targetIndex].text.isBlank()) {
            targetIndex++
        }

        if (targetIndex >= segments.size) {
            _currentSegmentIndex.value = segments.size
            _currentSegment.value = null
            _currentPosition.value = null
            _readingState.value = ReadingSessionState.Completed
            return null
        }

        _currentSegmentIndex.value = targetIndex
        _currentSegment.value = segments[targetIndex]
        _currentPosition.value = currentDocument.positionForIndex(targetIndex)
        _readingState.value = ReadingSessionState.Reading
        return segments[targetIndex]
    }

    /**
     * Advances to the next non-blank segment in the document.
     * If all segments are exhausted, transitions state to Completed.
     * Returns the next segment or null if finished.
     */
    fun advance(): ReadingSegment? {
        if (_readingState.value != ReadingSessionState.Reading) return null

        val segments = getSegments()
        var nextIndex = _currentSegmentIndex.value + 1

        while (nextIndex < segments.size && segments[nextIndex].text.isBlank()) {
            nextIndex++
        }

        if (nextIndex < segments.size) {
            _currentSegmentIndex.value = nextIndex
            _currentSegment.value = segments[nextIndex]
            _currentPosition.value = currentDocument.positionForIndex(nextIndex)
            return segments[nextIndex]
        } else {
            _currentSegmentIndex.value = segments.size
            _currentSegment.value = null
            _readingState.value = ReadingSessionState.Completed
            return null
        }
    }

    /**
     * Stops the active reading session.
     * The last active position remains recorded.
     */
    fun stop() {
        _readingState.value = ReadingSessionState.Stopped
        _currentSegment.value = null
    }

    /**
     * Resets the reading session to Idle and position to null.
     */
    fun reset() {
        _currentSegmentIndex.value = 0
        _currentSegment.value = null
        _currentPosition.value = null
        _readingState.value = ReadingSessionState.Idle
    }

    /**
     * Sets error state on the reading session.
     */
    fun setError() {
        _readingState.value = ReadingSessionState.Error
        _currentSegment.value = null
        _currentPosition.value = null
    }
}
