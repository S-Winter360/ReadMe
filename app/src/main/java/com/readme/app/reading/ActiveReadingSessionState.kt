package com.readme.app.reading

import com.readme.app.speech.TtsState

/**
 * Unified reading-session state that consolidates overall reading lifecycle,
 * current position, active document identity, and speech state across all content formats.
 *
 * @property activeDocumentId ID of the document currently being read, or null if no document is loaded.
 * @property sessionState High-level lifecycle state from [ReadingSessionState].
 * @property currentPosition Current reading position belonging to [activeDocumentId], or null.
 * @property speechState Current lifecycle state of the speech engine.
 * @property errorMessage Descriptive error message if [sessionState] is [ReadingSessionState.Error].
 */
data class ActiveReadingSessionState(
    val activeDocumentId: String? = null,
    val sessionState: ReadingSessionState = ReadingSessionState.Idle,
    val currentPosition: ReadingPosition? = null,
    val speechState: TtsState = TtsState.Uninitialized,
    val errorMessage: String? = null,
    val isEphemeral: Boolean = false
) {
    val isReading: Boolean get() = sessionState == ReadingSessionState.Reading || speechState == TtsState.Speaking
    val isStopped: Boolean get() = sessionState == ReadingSessionState.Stopped
    val isCompleted: Boolean get() = sessionState == ReadingSessionState.Completed
    val isIdle: Boolean get() = sessionState == ReadingSessionState.Idle
    val isError: Boolean get() = sessionState == ReadingSessionState.Error

    companion object {
        val Idle = ActiveReadingSessionState()
    }
}
