package com.readme.app.accessibility

import android.graphics.Bitmap
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.ocr.playservices.MlKitOcrProvider
import com.readme.app.reading.content.pdf.ocr.PdfOcrTextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext

/**
 * Plain result model for cross-app OCR recognition.
 */
data class CrossAppOcrResult(
    val text: String = "",
    val hasText: Boolean = text.isNotBlank(),
    val confidenceOrNull: Float? = null
)

/**
 * Clean OCR provider abstraction for cross-app image text recognition.
 * Separates ML Kit / AndroidX implementation details from document parsing and coordinators.
 */
interface CrossAppOcrEngine : Closeable {
    suspend fun recognize(bitmap: Bitmap): CrossAppOcrResult
    override fun close() {}
}

/**
 * AndroidX / ML Kit implementation of [CrossAppOcrEngine] using the shared official [MlKitOcrProvider].
 */
@OptIn(ExperimentalPdfApi::class)
class OnDeviceCrossAppOcrEngine : CrossAppOcrEngine {
    private var isClosed = false
    private var provider: MlKitOcrProvider? = null

    private fun getProvider(): MlKitOcrProvider {
        if (isClosed) throw IllegalStateException("OCR engine is closed.")
        if (provider == null) {
            provider = MlKitOcrProvider()
        }
        return provider!!
    }

    override suspend fun recognize(bitmap: Bitmap): CrossAppOcrResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        if (isClosed) throw IllegalStateException("OCR engine is closed.")

        try {
            val p = getProvider()
            val result = p.recognizeText(bitmap)
            val rawText = result?.getAllText()?.text ?: ""
            val normalized = PdfOcrTextNormalizer.normalize(rawText)
            CrossAppOcrResult(
                text = normalized,
                hasText = normalized.isNotBlank(),
                confidenceOrNull = null
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CrossAppOcrResult(
                text = "",
                hasText = false,
                confidenceOrNull = null
            )
        }
    }

    override fun close() {
        isClosed = true
        try {
            provider?.close()
        } catch (_: Exception) {
            // Safe ignore
        } finally {
            provider = null
        }
    }
}
