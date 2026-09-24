package com.readme.app.accessibility

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
 * Geometry-aware model representing an individual recognized word or element.
 */
data class CrossAppOcrElement(
    val text: String,
    val bounds: RectF
)

/**
 * Geometry-aware model representing an individual recognized line.
 */
data class CrossAppOcrLine(
    val text: String,
    val bounds: RectF,
    val elements: List<CrossAppOcrElement> = emptyList()
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
    val confidenceOrNull: Float? = null,
    val errorMessage: String? = null
)

/**
 * Result of the OCR engine isolation test using a known in-memory test bitmap.
 */
sealed interface OcrIsolationTestResult {
    data class Passed(
        val recognizedText: String,
        val blockCount: Int,
        val lineCount: Int,
        val durationMs: Long
    ) : OcrIsolationTestResult

    data class Failed(
        val reason: String,
        val exception: Throwable? = null
    ) : OcrIsolationTestResult
}

/**
 * Clean OCR provider abstraction for cross-app image text recognition.
 * Separates ML Kit / AndroidX implementation details from document parsing and coordinators.
 */
interface CrossAppOcrEngine : Closeable {
    suspend fun recognize(bitmap: Bitmap): CrossAppOcrResult
    override fun close() {}
}

/**
 * Utility to verify ML Kit OCR health in isolation using a synthetic, high-contrast test image.
 */
object OcrIsolationTester {
    fun createTestBitmap(text: String = "ReadMe Test"): Bitmap {
        val width = 480
        val height = 160
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 44f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val yPos = (height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, yPos, paint)
        return bitmap
    }

    suspend fun runIsolationTest(
        engine: CrossAppOcrEngine,
        expectedKeyword: String = "ReadMe"
    ): OcrIsolationTestResult = withContext(Dispatchers.Default) {
        val testBitmap = createTestBitmap("ReadMe Test")
        val start = System.currentTimeMillis()
        try {
            val result = engine.recognize(testBitmap)
            val duration = System.currentTimeMillis() - start
            if (result.hasText && result.text.contains(expectedKeyword, ignoreCase = true)) {
                OcrIsolationTestResult.Passed(
                    recognizedText = result.text.trim(),
                    blockCount = result.blocks.size,
                    lineCount = result.lines.size,
                    durationMs = duration
                )
            } else if (!result.hasText) {
                OcrIsolationTestResult.Failed(
                    reason = result.errorMessage ?: "ML Kit returned no text on known test image",
                    exception = null
                )
            } else {
                OcrIsolationTestResult.Failed(
                    reason = "Expected text containing '$expectedKeyword', but got '${result.text.trim()}'",
                    exception = null
                )
            }
        } catch (e: Throwable) {
            OcrIsolationTestResult.Failed(
                reason = e.message ?: e.javaClass.simpleName,
                exception = e
            )
        } finally {
            try {
                testBitmap.recycle()
            } catch (_: Throwable) {}
        }
    }
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

            val (blocksList, linesList) = extractHierarchyReflectively(result, bitmap.width, bitmap.height)

            val sentencesList = if (normalized.isNotBlank()) {
                OcrSentenceGeometryMapper.mapSentencesWithTightBounds(
                    blocks = blocksList,
                    lines = linesList,
                    rawOcrText = normalized,
                    bitmapWidth = bitmap.width,
                    bitmapHeight = bitmap.height
                )
            } else {
                emptyList()
            }

            CrossAppOcrResult(
                text = normalized,
                hasText = normalized.isNotBlank(),
                blocks = blocksList,
                lines = linesList,
                sentences = sentencesList,
                confidenceOrNull = null
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CrossAppOcrResult(
                text = "",
                hasText = false,
                confidenceOrNull = null,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun extractHierarchyReflectively(
        result: Any?,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Pair<List<CrossAppOcrBlock>, List<CrossAppOcrLine>> {
        val blocksList = mutableListOf<CrossAppOcrBlock>()
        val linesList = mutableListOf<CrossAppOcrLine>()
        if (result == null) return Pair(blocksList, linesList)

        try {
            val textObj = try {
                val field = result.javaClass.getDeclaredField("text")
                field.isAccessible = true
                field.get(result)
            } catch (_: Throwable) {
                try {
                    val method = result.javaClass.getMethod("getText")
                    method.invoke(result)
                } catch (_: Throwable) {
                    null
                }
            } ?: result

            val getBlocksMethod = try {
                textObj.javaClass.getMethod("getTextBlocks")
            } catch (_: Throwable) {
                null
            }

            if (getBlocksMethod != null) {
                val textBlocks = getBlocksMethod.invoke(textObj) as? Iterable<*>
                if (textBlocks != null) {
                    for (block in textBlocks) {
                        if (block == null) continue
                        val blockText = invokeStringMethod(block, "getText") ?: ""
                        val blockBounds = invokeRectMethod(block, "getBoundingBox") ?: Rect(0, 0, bitmapWidth, bitmapHeight)
                        val blockRect = RectF(blockBounds)
                        val blkLines = mutableListOf<CrossAppOcrLine>()

                        val getLinesMethod = try { block.javaClass.getMethod("getLines") } catch (_: Throwable) { null }
                        val lines = getLinesMethod?.invoke(block) as? Iterable<*>
                        if (lines != null) {
                            for (line in lines) {
                                if (line == null) continue
                                val lineText = invokeStringMethod(line, "getText") ?: ""
                                val lineBounds = invokeRectMethod(line, "getBoundingBox") ?: blockBounds
                                val lineRect = RectF(lineBounds)
                                val elements = mutableListOf<CrossAppOcrElement>()

                                val getElemsMethod = try { line.javaClass.getMethod("getElements") } catch (_: Throwable) { null }
                                val elems = getElemsMethod?.invoke(line) as? Iterable<*>
                                if (elems != null) {
                                    for (elem in elems) {
                                        if (elem == null) continue
                                        val elemText = invokeStringMethod(elem, "getText") ?: ""
                                        val elemBounds = invokeRectMethod(elem, "getBoundingBox") ?: lineBounds
                                        elements.add(CrossAppOcrElement(elemText, RectF(elemBounds)))
                                    }
                                }

                                val ocrLine = CrossAppOcrLine(lineText, lineRect, elements)
                                blkLines.add(ocrLine)
                                linesList.add(ocrLine)
                            }
                        }
                        blocksList.add(CrossAppOcrBlock(blockText, blockRect, blkLines))
                    }
                }
            }
        } catch (_: Throwable) {
            // Safe fallback
        }

        return Pair(blocksList, linesList)
    }

    private fun invokeStringMethod(target: Any, methodName: String): String? {
        return try {
            val m = target.javaClass.getMethod(methodName)
            m.invoke(target) as? String
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeRectMethod(target: Any, methodName: String): Rect? {
        return try {
            val m = target.javaClass.getMethod(methodName)
            m.invoke(target) as? Rect
        } catch (_: Throwable) {
            null
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
