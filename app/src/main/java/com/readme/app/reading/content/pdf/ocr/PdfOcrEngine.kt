package com.readme.app.reading.content.pdf.ocr

import android.graphics.Bitmap
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.ocr.playservices.MlKitOcrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext

/**
 * OCR Result for a single PDF page.
 */
data class PdfOcrPageResult(
    val pageIndex: Int = 0,
    val text: String = "",
    val confidenceOrNull: Float? = null,
    val hasText: Boolean = text.isNotBlank()
)

/**
 * Controlled readiness states for PDF OCR provider.
 */
sealed interface PdfOcrReadinessState {
    object Ready : PdfOcrReadinessState
    object Processing : PdfOcrReadinessState
    data class Unavailable(val message: String = "OCR is currently unavailable on this device.") : PdfOcrReadinessState
    data class ModelUnavailable(val message: String = "OCR text recognition is not ready yet.") : PdfOcrReadinessState
}

/**
 * Clean OCR provider abstraction for PDF page text recognition.
 * Separates ML Kit / AndroidX implementation details from document parsing.
 */
interface PdfOcrEngine : Closeable {
    /**
     * Current readiness state of this OCR engine.
     */
    val readinessState: PdfOcrReadinessState get() = PdfOcrReadinessState.Ready

    /**
     * Recognizes text from a rasterized PDF page bitmap.
     * @param bitmap The rasterized PDF page image.
     * @param pageIndex The 0-based physical PDF page index.
     * @return [PdfOcrPageResult] containing recognized text.
     */
    suspend fun recognize(bitmap: Bitmap, pageIndex: Int = 0): PdfOcrPageResult

    /**
     * Releases underlying OCR resources.
     */
    override fun close()
}

/**
 * Factory function creating the default [PdfOcrEngine] implementation.
 */
fun PdfOcrEngine(): PdfOcrEngine = AndroidxMlKitPdfOcrEngine()

/**
 * AndroidX ML Kit implementation of [PdfOcrEngine] using [MlKitOcrProvider].
 */
@OptIn(ExperimentalPdfApi::class)
class AndroidxMlKitPdfOcrEngine : PdfOcrEngine {
    private var isClosed = false
    private var ocrProvider: MlKitOcrProvider? = null

    override val readinessState: PdfOcrReadinessState
        get() {
            if (isClosed) return PdfOcrReadinessState.Unavailable("OCR engine is closed.")
            return try {
                getProvider()
                PdfOcrReadinessState.Ready
            } catch (e: PdfOcrModelUnavailableException) {
                PdfOcrReadinessState.ModelUnavailable(e.message ?: "OCR text recognition is not ready yet.")
            } catch (e: PdfOcrUnavailableException) {
                PdfOcrReadinessState.Unavailable(e.message ?: "OCR is currently unavailable on this device.")
            } catch (e: Exception) {
                PdfOcrReadinessState.Unavailable(e.message ?: "OCR is currently unavailable on this device.")
            }
        }

    private fun getProvider(): MlKitOcrProvider {
        if (isClosed) {
            throw PdfOcrException("OCR engine is closed.")
        }
        if (ocrProvider == null) {
            try {
                ocrProvider = MlKitOcrProvider()
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("model", ignoreCase = true) ||
                    msg.contains("download", ignoreCase = true) ||
                    msg.contains("not yet available", ignoreCase = true)
                ) {
                    throw PdfOcrModelUnavailableException("OCR text recognition is not ready yet.", e)
                }
                if (msg.contains("unavailable", ignoreCase = true) ||
                    msg.contains("play services", ignoreCase = true) ||
                    e is NoClassDefFoundError || e is ClassNotFoundException
                ) {
                    throw PdfOcrUnavailableException("OCR is currently unavailable on this device.", e)
                }
                throw PdfOcrInitializationException("Failed to initialize OCR provider.", e)
            }
        }
        return ocrProvider!!
    }

    override suspend fun recognize(bitmap: Bitmap, pageIndex: Int): PdfOcrPageResult = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        if (isClosed) {
            throw PdfOcrException("OCR engine is closed.")
        }

        try {
            val provider = getProvider()
            val result = provider.recognizeText(bitmap)
            val ocrText = result?.getAllText()
            val text = ocrText?.text ?: ""

            PdfOcrPageResult(
                pageIndex = pageIndex,
                text = text,
                confidenceOrNull = null,
                hasText = text.isNotBlank()
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: PdfOcrModelUnavailableException) {
            throw e
        } catch (e: PdfOcrUnavailableException) {
            throw e
        } catch (e: PdfOcrInitializationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: ""
            if (message.contains("model", ignoreCase = true) ||
                message.contains("download", ignoreCase = true) ||
                message.contains("not yet available", ignoreCase = true) ||
                message.contains("waiting for the text recognition optional module", ignoreCase = true)
            ) {
                throw PdfOcrModelUnavailableException("OCR text recognition is not ready yet.", e)
            }
            if (message.contains("unavailable", ignoreCase = true) ||
                message.contains("service missing", ignoreCase = true)
            ) {
                throw PdfOcrUnavailableException("OCR is currently unavailable on this device.", e)
            }
            throw PdfOcrProcessingException("Could not recognize text on page $pageIndex.", e)
        }
    }

    override fun close() {
        isClosed = true
        try {
            ocrProvider?.close()
        } catch (e: Exception) {
            // Ignore close errors
        } finally {
            ocrProvider = null
        }
    }
}

/**
 * Controlled exceptions for PDF OCR pipeline.
 */
open class PdfOcrException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PdfOcrUnavailableException(message: String = "OCR is currently unavailable on this device.", cause: Throwable? = null) : PdfOcrException(message, cause)
class PdfOcrModelUnavailableException(message: String = "OCR model is currently unavailable on this device.", cause: Throwable? = null) : PdfOcrException(message, cause)
class PdfOcrInitializationException(message: String = "Failed to initialize OCR provider.", cause: Throwable? = null) : PdfOcrException(message, cause)
class PdfOcrProcessingException(message: String = "Failed to recognize text on page.", cause: Throwable? = null) : PdfOcrException(message, cause)

