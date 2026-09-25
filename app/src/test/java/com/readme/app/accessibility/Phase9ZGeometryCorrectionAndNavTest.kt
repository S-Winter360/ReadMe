package com.readme.app.accessibility

import android.graphics.Rect
import android.graphics.RectF
import com.readme.app.accessibility.autonav.AutoAdvanceActionResult
import com.readme.app.accessibility.autonav.AutoNavigationCoordinator
import com.readme.app.accessibility.autonav.AutoNavigator
import com.readme.app.accessibility.autonav.NavigationActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase9ZGeometryCorrectionAndNavTest {

    private class TestNode(
        override val packageName: CharSequence? = "com.novel.reader",
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val isPassword: Boolean = false,
        override val isVisibleToUser: Boolean = true,
        override val isHeading: Boolean = false,
        override val className: CharSequence? = "android.view.View",
        override val isScrollable: Boolean = false,
        override val isEnabled: Boolean = true,
        val bounds: Rect = Rect(0, 0, 1080, 2400),
        val actions: List<Int> = emptyList(),
        val children: List<AccessibleNode> = emptyList(),
        var actionExecutionResult: (Int) -> Boolean = { true }
    ) : AccessibleNode {
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): AccessibleNode? = children.getOrNull(index)
        override fun recycle() {}
        override fun getBoundsInScreen(outBounds: Rect) {
            outBounds.set(bounds)
        }
        override fun availableActions(): List<Int> = actions
        var performedActionId: Int? = null
        override fun performAction(actionId: Int): Boolean {
            performedActionId = actionId
            return actionExecutionResult(actionId)
        }
    }

    private fun testRect(l: Int, t: Int, r: Int, b: Int): Rect = Rect().apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    private fun testRectF(l: Float, t: Float, r: Float, b: Float): RectF = RectF().apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    // =========================================================================
    // PART A: COORDINATE-SPACE ROOT-CAUSE TESTS
    // =========================================================================

    @Test
    fun `test window origin with status bar offset maps crop to exact screen coordinates`() {
        // Screen: 1080x2400, Status bar height: 104px.
        // Target app window begins below status bar: [0, 104, 1080, 2400] (height = 2296).
        val windowBounds = testRect(0, 104, 1080, 2400)
        val bitmapWidth = 1080
        val bitmapHeight = 2296
        val displayWidth = 1080
        val displayHeight = 2400

        // User selects a sentence on screen from Y=600 to Y=660
        val userSelectionOnScreen = testRect(100, 600, 980, 660)

        val originMode = ScreenGeometryMapper.determineOriginMode(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            windowBounds = windowBounds,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
        assertEquals(ScreenGeometryMapper.ScreenshotOriginMode.WINDOW_ORIGIN, originMode)

        // Calculate crop bounds in window bitmap coordinates
        val cropRect = ScreenGeometryMapper.calculateCropRect(
            selectionOnScreen = userSelectionOnScreen,
            windowBounds = windowBounds,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            originMode = originMode
        )

        // In window bitmap coordinates, cropTop must be selectionOnScreen.top - windowTop = 600 - 104 = 496
        assertEquals(100, cropRect.left)
        assertEquals(496, cropRect.top)
        assertEquals(980, cropRect.right)
        assertEquals(556, cropRect.bottom)

        // Inside the cropped bitmap (which has height 60px), OCR finds the text line at y=8 to y=48
        val rectInCrop = testRectF(10f, 8f, 500f, 48f)

        // Map back to screen
        val screenRect = ScreenGeometryMapper.mapRectFromCropToScreen(
            rectInCrop = rectInCrop,
            cropRect = cropRect,
            scaleX = 1.0f,
            scaleY = 1.0f,
            windowLeft = 0f,
            windowTop = 104f,
            ocrUpscaleFactor = 1.0f,
            originMode = originMode
        )

        // Actual screen position must be: selectionTop + rectInCropTop = 600 + 8 = 608
        // NOT 608 + 104 = 712 (which was the bug!)
        assertEquals(110f, screenRect.left, 0.01f)
        assertEquals(608f, screenRect.top, 0.01f)
        assertEquals(600f, screenRect.right, 0.01f)
        assertEquals(648f, screenRect.bottom, 0.01f)
    }

    @Test
    fun `test display origin fullscreen screenshot maps crop to exact screen coordinates`() {
        val windowBounds = testRect(0, 0, 1080, 2400)
        val bitmapWidth = 1080
        val bitmapHeight = 2400
        val displayWidth = 1080
        val displayHeight = 2400

        val userSelectionOnScreen = testRect(80, 500, 1000, 700)

        val originMode = ScreenGeometryMapper.determineOriginMode(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            windowBounds = windowBounds,
            displayWidth = displayWidth,
            displayHeight = displayHeight
        )
        assertEquals(ScreenGeometryMapper.ScreenshotOriginMode.DISPLAY_ORIGIN, originMode)

        val cropRect = ScreenGeometryMapper.calculateCropRect(
            selectionOnScreen = userSelectionOnScreen,
            windowBounds = windowBounds,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            originMode = originMode
        )

        assertEquals(80, cropRect.left)
        assertEquals(500, cropRect.top)

        // Inside crop bitmap, word is at (20, 30, 300, 70)
        val rectInCrop = testRectF(20f, 30f, 300f, 70f)
        val screenRect = ScreenGeometryMapper.mapRectFromCropToScreen(
            rectInCrop = rectInCrop,
            cropRect = cropRect,
            scaleX = 1.0f,
            scaleY = 1.0f,
            windowLeft = 0f,
            windowTop = 0f,
            ocrUpscaleFactor = 1.0f,
            originMode = originMode
        )

        assertEquals(100f, screenRect.left, 0.01f)
        assertEquals(530f, screenRect.top, 0.01f)
        assertEquals(380f, screenRect.right, 0.01f)
        assertEquals(570f, screenRect.bottom, 0.01f)
    }

    @Test
    fun `test ocr upscale factor is correctly unscaled when mapping back to screen`() {
        // Very small selection upscaled 2x for ML Kit
        val cropRect = testRect(100, 200, 200, 240)
        // In the 2x upscaled image (size 200x80), word bounds are (20, 10, 80, 50)
        val rectInUpscaledCrop = testRectF(20f, 10f, 80f, 50f)

        val screenRect = ScreenGeometryMapper.mapRectFromCropToScreen(
            rectInCrop = rectInUpscaledCrop,
            cropRect = cropRect,
            scaleX = 1.0f,
            scaleY = 1.0f,
            windowLeft = 0f,
            windowTop = 0f,
            ocrUpscaleFactor = 2.0f,
            originMode = ScreenGeometryMapper.ScreenshotOriginMode.DISPLAY_ORIGIN
        )

        // Unscaled coordinates: left = 20 / 2 = 10, top = 10 / 2 = 5
        // Screen coordinates: 100 + 10 = 110, 200 + 5 = 205
        assertEquals(110f, screenRect.left, 0.01f)
        assertEquals(205f, screenRect.top, 0.01f)
        assertEquals(140f, screenRect.right, 0.01f)
        assertEquals(225f, screenRect.bottom, 0.01f)
    }

    // =========================================================================
    // PART B: AUTO-NAVIGATION CONTENT CHANGE VALIDATION
    // =========================================================================

    @Test
    fun `test content practically identical detects minor ocr noise on unchanged page`() {
        val text1 = "Chapter 12: The Journey Continues. The rain had ceased by daybreak, leaving the streets washed in cool grey light."
        // Same text with minor punctuation and whitespace variation from OCR scan
        val text2 = "Chapter 12: The Journey Continues . The rain had ceased by daybreak , leaving the streets washed in cool grey light ."

        assertTrue(AutoNavigationCoordinator.isContentPracticallyIdentical(text1, text2))

        // Same text with one OCR letter misread ("grey" vs "gray")
        val text3 = "Chapter 12: The Journey Continues. The rain had ceased by daybreak, leaving the streets washed in cool gray light."
        assertTrue(AutoNavigationCoordinator.isContentPracticallyIdentical(text1, text3))
    }

    @Test
    fun `test content practically identical returns false for different page`() {
        val page1 = "Chapter 12: The Journey Continues. The rain had ceased by daybreak, leaving the streets washed in cool grey light."
        val page2 = "He stepped out onto the cobblestones and pulled his cloak tighter against the morning breeze, watching the carts roll by."

        assertFalse(AutoNavigationCoordinator.isContentPracticallyIdentical(page1, page2))
    }

    // =========================================================================
    // PART C: AUTO-NAVIGATOR CONTAINER SELECTION & FALLBACK
    // =========================================================================

    @Test
    fun `test auto navigator prioritizes scrollable container over non-scrollable text view`() {
        val textViewNode = TestNode(
            className = "android.widget.TextView",
            isScrollable = false,
            bounds = Rect(50, 200, 1000, 1800),
            actions = listOf(AutoNavigator.ID_SCROLL_FORWARD) // Spurious action on text
        )
        val viewPagerNode = TestNode(
            className = "androidx.viewpager2.widget.ViewPager2",
            isScrollable = true,
            bounds = Rect(0, 100, 1080, 2200),
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT, AutoNavigator.ID_SCROLL_FORWARD),
            children = listOf(textViewNode)
        )
        val root = TestNode(children = listOf(viewPagerNode))

        val best = AutoNavigator.findBestCandidate(root, Rect(100, 300, 900, 1500))
        assertNotNull(best)
        // Must select the scrollable ViewPager2, NOT the TextView!
        assertEquals("androidx.viewpager2.widget.ViewPager2", best?.className)
        assertEquals(AutoNavigator.ID_PAGE_RIGHT, best?.actionId)
    }

    @Test
    fun `test auto navigator attempts fallback action if primary action fails`() {
        val pagerNode = TestNode(
            className = "androidx.viewpager.widget.ViewPager",
            isScrollable = true,
            bounds = Rect(0, 100, 1080, 2200),
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT, AutoNavigator.ID_SCROLL_FORWARD),
            actionExecutionResult = { actionId ->
                // Simulate: PAGE_RIGHT returns false (unsupported by custom reader), but SCROLL_FORWARD returns true!
                actionId == AutoNavigator.ID_SCROLL_FORWARD
            }
        )
        val root = TestNode(children = listOf(pagerNode))

        val result = AutoNavigator.executeNavigation(root, Rect(100, 300, 900, 1500))
        assertTrue(result is AutoAdvanceActionResult.Dispatched)
        assertEquals(NavigationActionType.SCROLL_FORWARD.name, (result as AutoAdvanceActionResult.Dispatched).actionType)
    }

    // =========================================================================
    // PART D: SELECTION OVERLAY PREVIOUS-REGION MEMORY & CLAMPING
    // =========================================================================

    @Test
    fun `test previous selection region is preserved and clamped within window bounds`() {
        val windowBounds = testRect(0, 100, 1080, 2300)
        // User's previous selection from last session
        val previousSelection = testRect(50, 400, 1000, 1800)

        val clamped = ScreenGeometryMapper.clampRegion(previousSelection, windowBounds)
        assertNotNull(clamped)
        assertEquals(50, clamped?.left)
        assertEquals(400, clamped?.top)
        assertEquals(1000, clamped?.right)
        assertEquals(1800, clamped?.bottom)
    }

    @Test
    fun `test previous selection region extending outside window bounds is safely constrained`() {
        val windowBounds = testRect(0, 200, 1080, 2200)
        // Previous selection that spills over top and bottom
        val previousSelection = testRect(-50, 50, 1200, 2400)

        val clamped = ScreenGeometryMapper.clampRegion(previousSelection, windowBounds)
        assertNotNull(clamped)
        assertEquals(0, clamped?.left)
        assertEquals(200, clamped?.top)
        assertEquals(1080, clamped?.right)
        assertEquals(2200, clamped?.bottom)
    }

    @Test
    fun `test inverted drag rectangle is normalized before clamping`() {
        // Inverted drag gesture (dragged bottom-right to top-left)
        val inverted = testRect(800, 1200, 200, 400)
        val normalized = ScreenGeometryMapper.normalizeRect(inverted)

        assertEquals(200, normalized.left)
        assertEquals(400, normalized.top)
        assertEquals(800, normalized.right)
        assertEquals(1200, normalized.bottom)
    }
}
