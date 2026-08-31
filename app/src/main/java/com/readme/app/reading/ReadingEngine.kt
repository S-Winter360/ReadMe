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

    var currentDocument: ReadingDocument = initialDocument
        private set

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
     * Starts a new reading session from the beginning.
     * Skips initial blank segments if any.
     * Returns the first playable segment or null if the document has no valid content.
     */
    fun startSession(): ReadingSegment? {
        val segments = getSegments()
        if (segments.isEmpty()) {
            _currentSegmentIndex.value = 0
            _currentSegment.value = null
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
            _readingState.value = ReadingSessionState.Completed
            return null
        }

        _currentSegmentIndex.value = index
        _currentSegment.value = segments[index]
        _readingState.value = ReadingSessionState.Reading
        return segments[index]
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
     */
    fun stop() {
        _readingState.value = ReadingSessionState.Stopped
        _currentSegment.value = null
    }

    /**
     * Resets the reading session to Idle and position to 0.
     */
    fun reset() {
        _currentSegmentIndex.value = 0
        _currentSegment.value = null
        _readingState.value = ReadingSessionState.Idle
    }

    /**
     * Sets error state on the reading session.
     */
    fun setError() {
        _readingState.value = ReadingSessionState.Error
        _currentSegment.value = null
    }
}
