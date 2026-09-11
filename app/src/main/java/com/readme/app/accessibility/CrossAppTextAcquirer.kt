package com.readme.app.accessibility

/**
 * Narrow interface for acquiring text from the active external application.
 * Exposes immutable snapshots without leaking platform accessibility objects.
 */
interface CrossAppTextAcquirer {
    fun acquireCurrentText(): CrossAppTextSnapshot?
}
