package com.readme.app.accessibility

import android.graphics.Rect
import android.util.Log

/**
 * In-memory diagnostic record capturing the state of the screen OCR acquisition pipeline.
 *
 * All fields are kept strictly in-memory during debug/test execution.
 * No screenshots, bitmaps, or sensitive user screen contents are persisted to storage.
 */
data class ScreenOcrDiagnosticRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val targetPackageName: String,
    val windowId: Int,
    val windowBounds: Rect,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
    val selectionInUi: Rect,
    val normalizedSelection: Rect,
    val convertedCropRect: Rect,
    val croppedBitmapWidth: Int,
    val croppedBitmapHeight: Int,
    val scaleX: Float,
    val scaleY: Float,
    val densityDpi: Int,
    val ocrUpscaleFactor: Float,
    val ocrTextLength: Int,
    val ocrSentenceCount: Int,
    val errorReason: String? = null
)

/**
 * Thread-safe in-memory diagnostics registry for Screen OCR pipeline auditing.
 */
object ScreenOcrDiagnostics {
    private const val TAG = "ReadMeOcrDiagnostic"
    private const val MAX_HISTORY = 20

    private val history = java.util.ArrayDeque<ScreenOcrDiagnosticRecord>()

    @Volatile
    var lastDiagnostic: ScreenOcrDiagnosticRecord? = null
        private set

    @Synchronized
    fun record(record: ScreenOcrDiagnosticRecord) {
        lastDiagnostic = record
        if (history.size >= MAX_HISTORY) {
            history.pollFirst()
        }
        history.addLast(record)
        try {
            Log.d(
                TAG,
                "Target: ${record.targetPackageName} (winId=${record.windowId}, bounds=${record.windowBounds}), " +
                    "Screenshot: ${record.screenshotWidth}x${record.screenshotHeight}, " +
                    "SelectionUI: ${record.selectionInUi} -> Norm: ${record.normalizedSelection}, " +
                    "Crop: ${record.convertedCropRect} (${record.croppedBitmapWidth}x${record.croppedBitmapHeight}), " +
                    "Scale: (${record.scaleX}, ${record.scaleY}), Density: ${record.densityDpi}, " +
                    "UpscaleFactor: ${record.ocrUpscaleFactor}, " +
                    "OCR TextLen: ${record.ocrTextLength}, Sentences: ${record.ocrSentenceCount}, " +
                    "Error: ${record.errorReason ?: "None"}"
            )
        } catch (_: Throwable) {
            // Safe ignore in unit test environments where Log is not mocked
        }
    }

    @Synchronized
    fun getRecentRecords(): List<ScreenOcrDiagnosticRecord> {
        return history.toList()
    }

    @Synchronized
    fun clear() {
        lastDiagnostic = null
        history.clear()
    }
}
