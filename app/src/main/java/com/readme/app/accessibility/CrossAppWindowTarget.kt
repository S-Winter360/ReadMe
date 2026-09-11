package com.readme.app.accessibility

/**
 * Immutable target descriptor for an active window requested for explicit screen capture.
 *
 * Stores only minimal identifiers, avoiding long retention of platform window references.
 */
data class CrossAppWindowTarget(
    val packageName: String,
    val windowId: Int,
    val displayId: Int = 0,
    val requestId: Long = System.currentTimeMillis(),
    val generation: Long = 0L,
    val isSensitiveOrPassword: Boolean = false
)
