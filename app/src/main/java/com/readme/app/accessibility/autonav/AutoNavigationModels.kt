package com.readme.app.accessibility.autonav

import android.graphics.Rect

/**
 * State machine for automatic screen advance according to Phase 9X Part B.
 *
 * States:
 * - [Idle]: Not currently advancing; awaiting trigger or disabled.
 * - [Advancing]: Performing accessibility page/scroll action on target node.
 * - [WaitingForContentChange]: Waiting for target window accessibility events (scroll/content change).
 * - [Acquiring]: Capturing screenshot of new screen content and running OCR.
 * - [Reading]: Reading new page content.
 * - [Failed]: Navigation or content validation failed.
 */
sealed interface AutoNavigationState {
    object Idle : AutoNavigationState
    object Advancing : AutoNavigationState
    object WaitingForContentChange : AutoNavigationState
    object Acquiring : AutoNavigationState
    object Reading : AutoNavigationState
    data class Failed(val reason: String) : AutoNavigationState
}

/**
 * Result of attempting to discover and execute an accessibility navigation action.
 */
sealed interface AutoAdvanceActionResult {
    data class Dispatched(val actionType: String, val nodeClass: String) : AutoAdvanceActionResult
    object NoCandidateNodeFound : AutoAdvanceActionResult
    object ActionRejectedByNode : AutoAdvanceActionResult
    object ServiceUnavailable : AutoAdvanceActionResult
    data class Error(val message: String) : AutoAdvanceActionResult
}

/**
 * High-level result of a complete auto-advance cycle.
 */
sealed interface AutoAdvanceCycleResult {
    data class Success(val segmentCount: Int, val generation: Long) : AutoAdvanceCycleResult
    object EndOfAccessibleContent : AutoAdvanceCycleResult
    object ContentUnchanged : AutoAdvanceCycleResult
    object Unavailable : AutoAdvanceCycleResult
    object Cancelled : AutoAdvanceCycleResult
    data class Error(val reason: String) : AutoAdvanceCycleResult
}

enum class NavigationActionType {
    PAGE_RIGHT,
    PAGE_DOWN,
    PAGE_LEFT,
    PAGE_UP,
    SCROLL_DOWN,
    SCROLL_FORWARD,
    SCROLL_RIGHT
}

data class DiscoveredNavigationAction(
    val actionType: NavigationActionType,
    val actionId: Int,
    val targetNodeBounds: Rect,
    val targetNodeClass: String,
    val score: Int
)
