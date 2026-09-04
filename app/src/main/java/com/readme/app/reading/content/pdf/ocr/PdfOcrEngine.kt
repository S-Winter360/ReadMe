package com.readme.app.reading.content.pdf.ocr

import android.graphics.Bitmap
import android.util.Size
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.ocr.playservices.MlKitOcrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF OCR Engine abstraction.
 * Wraps the AndroidX PDF MlKitOcrProvider for Reading document processing.
 */
@OptIn(ExperimentalPdfApi::class)
class PdfOcrEngine {
    private var isClosed = false
    
    private var ocrProvider: MlKitOcrProvider? = null

    private fun getProvider(): MlKitOcrProvider {
        if (ocrProvider == null) {
            ocrProvider = MlKitOcrProvider()
        }
        return ocrProvider!!
    }

    /**
     * Recognizes text from a rasterized PDF page bitmap.
     */
    suspend fun recognize(bitmap: Bitmap): PdfOcrPageResult = withContext(Dispatchers.Default) {
        if (isClosed) throw PdfOcrException("OCR engine is closed.")
        
        try {
            val result = getProvider().recognizeText(bitmap)
            val ocrText = result?.getAllText()
            val text = ocrText?.text ?: ""
            
            PdfOcrPageResult(
                text = text,
                hasText = text.isNotBlank()
            )
        } catch (e: Exception) {
            throw PdfOcrException("Could not recognize text on this page.", e)
        }
    }
    
    fun close() {
        isClosed = true
        try {
            ocrProvider?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
    }
}

data class PdfOcrPageResult(
    val text: String,
    val hasText: Boolean
)

class PdfOcrException(message: String, cause: Throwable? = null) : Exception(message, cause)
class PdfOcrModelUnavailableException(message: String) : Exception(message)
class PdfOcrNoTextException(message: String) : Exception(message)
