package com.readme.app.ui.overlay

import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.reading.ReadingSessionState

/**
 * High-level system bubble visibility states.
 * Enforces predictable presence across permissions, settings, foreground/background, and user close.
 */
enum class BubbleVisibilityState {
    Visible,
    HiddenByForeground,
    HiddenByUser,
    Disabled,
    PermissionUnavailable,
    OverlayUnavailable,
    ServiceUnavailable
}

/**
 * Bubble UI state machine modeling user interaction and session context.
 * Distinct from [ReadingSessionState], which remains the sole authority for playback.
 */
sealed class BubbleUiState {
    object Hidden : BubbleUiState()
    object CompactIdle : BubbleUiState()
    object ExpandedIdle : BubbleUiState()
    object CompactReading : BubbleUiState()
    object ExpandedReading : BubbleUiState()
    object Paused : BubbleUiState()
}

/**
 * Deterministic rules governing bubble visibility and UI state transitions.
 */
object BubbleLifecyclePolicy {

    /**
     * Determines whether the floating bubble should be visible in the system window manager.
     */
    fun computeVisibilityState(
        isFloatingEnabled: Boolean,
        hasOverlayPermission: Boolean,
        isForeground: Boolean,
        isClosedByUser: Boolean,
        isDocumentPickerActive: Boolean = false
    ): BubbleVisibilityState {
        return when {
            !isFloatingEnabled -> BubbleVisibilityState.Disabled
            !hasOverlayPermission -> BubbleVisibilityState.PermissionUnavailable
            isForeground || isDocumentPickerActive -> BubbleVisibilityState.HiddenByForeground
            isClosedByUser -> BubbleVisibilityState.HiddenByUser
            else -> BubbleVisibilityState.Visible
        }
    }

    /**
     * Computes the current [BubbleUiState] from visibility, reading session, and expansion state.
     */
    fun computeUiState(
        visibilityState: BubbleVisibilityState,
        sessionState: ActiveReadingSessionState,
        documentState: ActiveDocumentState,
        isExpanded: Boolean
    ): BubbleUiState {
        if (visibilityState != BubbleVisibilityState.Visible) {
            return BubbleUiState.Hidden
        }

        val isReading = sessionState.isReading
        val isPaused = !isReading &&
            sessionState.sessionState == ReadingSessionState.Stopped &&
            (documentState.hasActiveDocument || sessionState.currentPosition != null) &&
            !sessionState.isCompleted

        return when {
            isReading -> if (isExpanded) BubbleUiState.ExpandedReading else BubbleUiState.CompactReading
            isPaused -> if (isExpanded) BubbleUiState.ExpandedReading else BubbleUiState.Paused
            else -> if (isExpanded) BubbleUiState.ExpandedIdle else BubbleUiState.CompactIdle
        }
    }
}
