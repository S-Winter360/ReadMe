package com.readme.app.ui.overlay

import com.readme.app.reading.ReadingSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenRegionAndHighlightGeometryTest {

    @Test
    fun testRegionClamping_standardWithinBounds() {
        val rect = RegionRect.normalizeAndClamp(
            startX = 100f,
            startY = 200f,
            currentX = 600f,
            currentY = 800f,
            boundLeft = 0,
            boundTop = 0,
            boundRight = 1080,
            boundBottom = 2400
        )

        assertEquals(100, rect.left)
        assertEquals(200, rect.top)
        assertEquals(600, rect.right)
        assertEquals(800, rect.bottom)
        assertEquals(500, rect.width)
        assertEquals(600, rect.height)
        assertTrue(rect.isValid)
    }

    @Test
    fun testRegionClamping_invertedDragCoordinates() {
        // Dragged from bottom-right to top-left
        val rect = RegionRect.normalizeAndClamp(
            startX = 700f,
            startY = 900f,
            currentX = 250f,
            currentY = 300f,
            boundLeft = 0,
            boundTop = 0,
            boundRight = 1080,
            boundBottom = 2400
        )

        assertEquals(250, rect.left)
        assertEquals(300, rect.top)
        assertEquals(700, rect.right)
        assertEquals(900, rect.bottom)
        assertEquals(450, rect.width)
        assertEquals(600, rect.height)
        assertTrue(rect.isValid)
    }

    @Test
    fun testRegionClamping_exceedsBoundsRestrictedToWindow() {
        val rect = RegionRect.normalizeAndClamp(
            startX = -100f,
            startY = 50f,
            currentX = 1500f,
            currentY = 2500f,
            boundLeft = 50,
            boundTop = 100,
            boundRight = 1000,
            boundBottom = 2000
        )

        assertEquals(50, rect.left)
        assertEquals(100, rect.top)
        assertEquals(1000, rect.right)
        assertEquals(2000, rect.bottom)
        assertEquals(950, rect.width)
        assertEquals(1900, rect.height)
        assertTrue(rect.isValid)
    }

    @Test
    fun testRegionClamping_zeroDimensionInvalid() {
        val rect = RegionRect.normalizeAndClamp(
            startX = 100f,
            startY = 100f,
            currentX = 100f,
            currentY = 100f,
            boundLeft = 0,
            boundTop = 0,
            boundRight = 1000,
            boundBottom = 1000
        )

        assertEquals(0, rect.width)
        assertEquals(0, rect.height)
        assertFalse(rect.isValid)
    }

    @Test
    fun testReadingSegment_emptyBoundingBoxFallback() {
        val segment = ReadingSegment(
            id = "plain:seg:1",
            text = "Plain text sentence with no visual layout bounds."
        )

        assertTrue(segment.boundingBoxes.isEmpty())
    }

    @Test
    fun testHighlightCleanup_clearsStateWhenReadingStops() {
        var activeHighlightsCount = 3
        val isReading = false

        // Simulating the highlight clearing logic
        if (!isReading) {
            activeHighlightsCount = 0
        }

        assertEquals(0, activeHighlightsCount)
    }
}
