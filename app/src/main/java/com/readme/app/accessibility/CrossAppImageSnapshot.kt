package com.readme.app.accessibility

import android.graphics.Bitmap

/**
 * Plain image snapshot model wrapping the captured window bitmap.
 *
 * Enforces strict ephemeral lifecycle: the bitmap is never stored in DataStore, Room,
 * or persistent repositories, and must be released/recycled promptly via [recycle] after OCR.
 */
data class CrossAppImageSnapshot(
    val packageName: String,
    val windowId: Int,
    val requestId: Long,
    val generation: Long,
    val bitmap: Bitmap,
    val width: Int = bitmap.width,
    val height: Int = bitmap.height
) {
    fun recycle() {
        try {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (_: Throwable) {
            // Safe ignore on recycled
        }
    }
}
