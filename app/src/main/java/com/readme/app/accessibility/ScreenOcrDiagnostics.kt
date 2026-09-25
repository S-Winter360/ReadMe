package com.readme.app.accessibility

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.readme.app.BuildConfig

/**
 * In-memory diagnostic record capturing the state of the screen OCR acquisition pipeline.
 *
 * All fields are kept strictly in-memory during debug/test execution.
 * No screenshots, bitmaps, or sensitive user screen contents are persisted to storage.
 */
data class ScreenOcrDiagnosticRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val targetPackageName: String,
    val targetWindowId: Int,
    val windowBounds: Rect,
    val screenshotWidth: Int,
    val screenshotHeight: Int,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,
    val densityDpi: Int,
    val selectionInUi: Rect,
    val normalizedSelection: Rect,
    val convertedCropRect: Rect,
    val cropWidth: Int = convertedCropRect.width(),
    val cropHeight: Int = convertedCropRect.height(),
    val ocrInputWidth: Int = 0,
    val ocrInputHeight: Int = 0,
    val ocrBlockCount: Int = 0,
    val ocrLineCount: Int = 0,
    val ocrElementCount: Int = 0,
    val scaleX: Float,
    val scaleY: Float,
    val ocrUpscaleFactor: Float,
    val ocrTextLength: Int,
    val ocrSentenceCount: Int,
    val originMode: String = "Unknown",
    val firstSentenceCropBounds: String = "",
    val firstHighlightBounds: String = "",
    val acquisitionResultType: String = "Unknown",
    val errorReason: String? = null
)

/**
 * Temporary in-memory inspector for raw screenshot and cropped bitmap during DEBUG mode.
 * Bitmaps are strictly held in-memory and only when BuildConfig.DEBUG is true.
 * In release builds, no bitmaps are retained or captured.
 */
object DebugOcrCaptureInspector {
    @Volatile
    var lastRawScreenshot: Bitmap? = null
        private set

    @Volatile
    var lastCroppedBitmap: Bitmap? = null
        private set

    @Synchronized
    fun updateCaptures(raw: Bitmap?, crop: Bitmap?) {
        if (!BuildConfig.DEBUG) return
        try { lastRawScreenshot?.recycle() } catch (_: Throwable) {}
        try { lastCroppedBitmap?.recycle() } catch (_: Throwable) {}

        lastRawScreenshot = raw?.let {
            try {
                it.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                null
            }
        }
        lastCroppedBitmap = crop?.let {
            try {
                it.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                null
            }
        }
    }

    @Synchronized
    fun clear() {
        try { lastRawScreenshot?.recycle() } catch (_: Throwable) {}
        try { lastCroppedBitmap?.recycle() } catch (_: Throwable) {}
        lastRawScreenshot = null
        lastCroppedBitmap = null
    }
}

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
                "Target: ${record.targetPackageName} (winId=${record.targetWindowId}, bounds=${record.windowBounds}), " +
                    "Display: ${record.displayWidth}x${record.displayHeight}, " +
                    "Screenshot: ${record.screenshotWidth}x${record.screenshotHeight} (originMode=${record.originMode}), " +
                    "SelectionUI: ${record.selectionInUi} -> Norm: ${record.normalizedSelection}, " +
                    "Crop: ${record.convertedCropRect} (${record.cropWidth}x${record.cropHeight}), " +
                    "OCR Input: ${record.ocrInputWidth}x${record.ocrInputHeight} (scale=${record.ocrUpscaleFactor}), " +
                    "Scale: (${record.scaleX}, ${record.scaleY}), Density: ${record.densityDpi}, " +
                    "OCR Blocks: ${record.ocrBlockCount}, Lines: ${record.ocrLineCount}, Elements: ${record.ocrElementCount}, " +
                    "OCR TextLen: ${record.ocrTextLength}, Sentences: ${record.ocrSentenceCount}, " +
                    "OCR Bounds In Crop: ${record.firstSentenceCropBounds}, " +
                    "Highlight Bounds: ${record.firstHighlightBounds}, " +
                    "Result: ${record.acquisitionResultType}, " +
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
        DebugOcrCaptureInspector.clear()
    }
}
