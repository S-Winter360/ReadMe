package com.readme.app.accessibility

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.ocr.playservices.MlKitOcrProvider
import com.readme.app.reading.content.TxtDocumentParser
import com.readme.app.reading.content.pdf.ocr.PdfOcrTextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.coroutines.coroutineContext

/**
 * Geometry-aware model representing an individual recognized line.
 */
data class CrossAppOcrLine(
    val text: String,
    val bounds: RectF
)

/**
 * Geometry-aware model representing a recognized paragraph/block.
 */
data class CrossAppOcrBlock(
    val text: String,
    val bounds: RectF,
    val lines: List<CrossAppOcrLine> = emptyList()
)

/**
 * Geometry-aware model representing a speech-ready sentence with bounding boxes.
 */
data class CrossAppOcrSentence(
    val text: String,
    val bounds: RectF,
    val lineBounds: List<RectF> = emptyList()
)

/**
 * Plain result model for cross-app OCR recognition preserving bounding geometry.
 */
data class CrossAppOcrResult(
    val text: String = "",
    val hasText: Boolean = text.isNotBlank(),
    val blocks: List<CrossAppOcrBlock> = emptyList(),
    val lines: List<CrossAppOcrLine> = emptyList(),
    val sentences: List<CrossAppOcrSentence> = emptyList(),
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
            val allOcrText = result?.getAllText()
            val rawText = allOcrText?.text ?: ""
            val normalized = PdfOcrTextNormalizer.normalize(rawText)

            val sentencesList = mutableListOf<CrossAppOcrSentence>()
            if (normalized.isNotBlank()) {
                val sentences = TxtDocumentParser.splitIntoSentences(normalized)
                for (s in sentences) {
                    val trimmed = s.trim()
                    if (trimmed.isNotBlank()) {
                        val searchMatches = try {
                            result?.getSearchBounds(trimmed, false)
                        } catch (_: Throwable) {
                            null
                        }
                        val matchRects = searchMatches?.firstOrNull()?.map { RectF(it) } ?: emptyList()
                        val unionBounds = if (matchRects.isNotEmpty()) {
                            val union = RectF(matchRects.first())
                            matchRects.forEach { union.union(it) }
                            union
                        } else {
                            RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                        }
                        sentencesList.add(
                            CrossAppOcrSentence(
                                text = trimmed,
                                bounds = unionBounds,
                                lineBounds = matchRects
                            )
                        )
                    }
                }
            }

            CrossAppOcrResult(
                text = normalized,
                hasText = normalized.isNotBlank(),
                sentences = sentencesList,
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
