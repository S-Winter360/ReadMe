package com.readme.app.accessibility

/**
 * Interface defining gesture dispatching capabilities for paged-reader edge-tap navigation.
 *
 * Implements Phase 9AC Section 7 requirements:
 * - Restricted strictly to user-enabled automatic page advance.
 * - Single deterministic tap (DOWN -> short duration -> UP).
 * - Decoupled from Android framework for deterministic testing.
 */
interface CrossAppGestureDispatcher {
    val canDispatchGestures: Boolean
    suspend fun performTap(x: Float, y: Float, durationMs: Long = 80L): Boolean
}
