package com.readme.app.reading.pdf.ocr

import com.readme.app.reading.content.pdf.ocr.PdfPageRasterizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageRasterizerTest {

    @Test
    fun calculateRasterSize_invalidOrZeroDimensions_returnsDefault() {
        val size1 = PdfPageRasterizer.calculateDimensions(0, 0)
        assertEquals(PdfPageRasterizer.DEFAULT_DIMENSIONS, size1)

        val size2 = PdfPageRasterizer.calculateDimensions(-500, 800)
        assertEquals(PdfPageRasterizer.DEFAULT_DIMENSIONS, size2)
    }

    @Test
    fun calculateRasterSize_standardA4Page_scalesWithinCap() {
        // Standard A4 in points: 595 x 842
        val size = PdfPageRasterizer.calculateDimensions(595, 842)
        assertTrue(size.width <= PdfPageRasterizer.MAX_DIMENSION)
        assertTrue(size.height <= PdfPageRasterizer.MAX_DIMENSION)
        assertTrue(size.height >= PdfPageRasterizer.MIN_DIMENSION)
    }

    @Test
    fun calculateRasterSize_hugePage_cappedAtMaxDimension() {
        val size = PdfPageRasterizer.calculateDimensions(3000, 4000)
        assertTrue(size.width <= PdfPageRasterizer.MAX_DIMENSION)
        assertTrue(size.height <= PdfPageRasterizer.MAX_DIMENSION)
        assertEquals(PdfPageRasterizer.MAX_DIMENSION, size.height)
    }

    @Test
    fun calculateRasterSize_tinyPage_upscaledForLegibility() {
        val size = PdfPageRasterizer.calculateDimensions(300, 400)
        // 400 scaled by 2.0 = 800
        assertTrue(size.width >= 300)
        assertTrue(size.height >= 400)
    }
}

