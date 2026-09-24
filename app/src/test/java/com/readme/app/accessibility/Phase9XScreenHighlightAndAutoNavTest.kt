package com.readme.app.accessibility

import android.graphics.Rect
import android.graphics.RectF
import com.readme.app.accessibility.autonav.AutoAdvanceActionResult
import com.readme.app.accessibility.autonav.AutoNavigator
import com.readme.app.accessibility.autonav.NavigationActionType
import com.readme.app.settings.ReadMeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase9XScreenHighlightAndAutoNavTest {

    // Helper mock AccessibleNode implementation for pure JVM testing
    private class TestAccessibleNode(
        override val packageName: CharSequence? = "com.test.reader",
        override val text: CharSequence? = null,
        override val contentDescription: CharSequence? = null,
        override val isPassword: Boolean = false,
        override val isVisibleToUser: Boolean = true,
        override val isHeading: Boolean = false,
        override val className: CharSequence? = "android.view.View",
        override val isScrollable: Boolean = false,
        override val isEnabled: Boolean = true,
        val bounds: Rect = Rect(0, 0, 1080, 1920),
        val actions: List<Int> = emptyList(),
        val children: List<AccessibleNode> = emptyList(),
        var actionExecutionResult: Boolean = true
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
            return actionExecutionResult
        }
    }

    private fun testRectF(l: Float, t: Float, r: Float, b: Float): RectF = RectF().apply {
        left = l
        top = t
        right = r
        bottom = b
    }

    // =========================================================================
    // PART A: GEOMETRY MAPPING & TIGHT BOUNDS TESTS
    // =========================================================================

    @Test
    fun `test single line sentence produces tight bounds matching elements`() {
        val elements = listOf(
            CrossAppOcrElement("Hello", testRectF(50f, 100f, 150f, 140f)),
            CrossAppOcrElement("world", testRectF(160f, 100f, 260f, 140f))
        )
        val line = CrossAppOcrLine("Hello world", testRectF(50f, 100f, 500f, 140f), elements)
        val block = CrossAppOcrBlock("Hello world", testRectF(50f, 100f, 500f, 140f), listOf(line))

        val sentences = OcrSentenceGeometryMapper.mapSentencesWithTightBounds(
            blocks = listOf(block),
            lines = listOf(line),
            rawOcrText = "Hello world.",
            bitmapWidth = 1080,
            bitmapHeight = 1920
        )

        assertEquals(1, sentences.size)
        val sentence = sentences.first()
        assertEquals(1, sentence.lineBounds.size)

        // Line bounding box should strictly bound the words (50f to 260f), NOT the entire line (50f to 500f)
        val tightLine = sentence.lineBounds.first()
        assertEquals(50f, tightLine.left, 0.1f)
        assertEquals(260f, tightLine.right, 0.1f)
        assertEquals(100f, tightLine.top, 0.1f)
        assertEquals(140f, tightLine.bottom, 0.1f)
    }

    @Test
    fun `test multi-line sentence preserves distinct tight line rectangles`() {
        // Line 1 has 3 words, only the last 2 belong to the sentence
        val line1Elements = listOf(
            CrossAppOcrElement("Previous.", testRectF(50f, 100f, 150f, 140f)),
            CrossAppOcrElement("First", testRectF(160f, 100f, 250f, 140f)),
            CrossAppOcrElement("sentence", testRectF(260f, 100f, 400f, 140f))
        )
        val line1 = CrossAppOcrLine("Previous. First sentence", testRectF(50f, 100f, 500f, 140f), line1Elements)

        // Line 2 has continuing words
        val line2Elements = listOf(
            CrossAppOcrElement("continues", testRectF(50f, 150f, 200f, 190f)),
            CrossAppOcrElement("here.", testRectF(210f, 150f, 300f, 190f))
        )
        val line2 = CrossAppOcrLine("continues here.", testRectF(50f, 150f, 500f, 190f), line2Elements)

        val block = CrossAppOcrBlock(
            "Previous. First sentence continues here.",
            testRectF(50f, 100f, 500f, 190f),
            listOf(line1, line2)
        )

        val sentences = OcrSentenceGeometryMapper.mapSentencesWithTightBounds(
            blocks = listOf(block),
            lines = listOf(line1, line2),
            rawOcrText = "Previous. First sentence continues here.",
            bitmapWidth = 1080,
            bitmapHeight = 1920
        )

        assertEquals(2, sentences.size)
        val secondSentence = sentences[1]
        assertEquals("First sentence continues here.", secondSentence.text)
        assertEquals(2, secondSentence.lineBounds.size)

        // Line 1 partial rectangle should start at "First" (160f), NOT "Previous" (50f)
        val firstLineRect = secondSentence.lineBounds[0]
        assertEquals(160f, firstLineRect.left, 0.1f)
        assertEquals(400f, firstLineRect.right, 0.1f)

        // Line 2 rectangle spans "continues here" (50f to 300f)
        val secondLineRect = secondSentence.lineBounds[1]
        assertEquals(50f, secondLineRect.left, 0.1f)
        assertEquals(300f, secondLineRect.right, 0.1f)
    }

    // =========================================================================
    // PART B: ACCESSIBILITY AUTO-NAVIGATOR TESTS
    // =========================================================================

    @Test
    fun `test auto navigator discovers page container with page right action`() {
        val pagedReaderNode = TestAccessibleNode(
            className = "androidx.viewpager2.widget.ViewPager2",
            isScrollable = true,
            bounds = Rect(0, 100, 1080, 1800),
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT, AutoNavigator.ID_PAGE_LEFT)
        )
        val rootNode = TestAccessibleNode(
            className = "android.widget.FrameLayout",
            children = listOf(pagedReaderNode)
        )

        val bestCandidate = AutoNavigator.findBestCandidate(rootNode, Rect(100, 200, 900, 1500))
        assertNotNull(bestCandidate)
        assertEquals(NavigationActionType.PAGE_RIGHT, bestCandidate?.actionType)
        assertEquals(AutoNavigator.ID_PAGE_RIGHT, bestCandidate?.actionId)
    }

    @Test
    fun `test auto navigator discovers scroll down action when no paged container`() {
        val scrollContainer = TestAccessibleNode(
            className = "android.widget.ScrollView",
            isScrollable = true,
            bounds = Rect(0, 100, 1080, 1800),
            actions = listOf(AutoNavigator.ID_SCROLL_DOWN, AutoNavigator.ID_SCROLL_FORWARD)
        )
        val rootNode = TestAccessibleNode(
            className = "android.widget.FrameLayout",
            children = listOf(scrollContainer)
        )

        val bestCandidate = AutoNavigator.findBestCandidate(rootNode, Rect(100, 200, 900, 1500))
        assertNotNull(bestCandidate)
        assertEquals(NavigationActionType.SCROLL_DOWN, bestCandidate?.actionType)
    }

    @Test
    fun `test auto navigator executes action directly without touch or gesture injection`() {
        val scrollNode = TestAccessibleNode(
            className = "androidx.recyclerview.widget.RecyclerView",
            isScrollable = true,
            bounds = Rect(0, 100, 1080, 1800),
            actions = listOf(AutoNavigator.ID_SCROLL_FORWARD),
            actionExecutionResult = true
        )
        val rootNode = TestAccessibleNode(children = listOf(scrollNode))

        val result = AutoNavigator.executeNavigation(rootNode, Rect(0, 100, 1080, 1800))
        assertTrue(result is AutoAdvanceActionResult.Dispatched)
        assertEquals(AutoNavigator.ID_SCROLL_FORWARD, scrollNode.performedActionId)
    }

    @Test
    fun `test auto navigator returns no candidate when container exposes no page or scroll actions`() {
        val staticNode = TestAccessibleNode(
            className = "android.widget.TextView",
            isScrollable = false,
            actions = emptyList()
        )
        val rootNode = TestAccessibleNode(children = listOf(staticNode))

        val result = AutoNavigator.executeNavigation(rootNode, null)
        assertTrue(result is AutoAdvanceActionResult.NoCandidateNodeFound)
    }

    @Test
    fun `test auto navigator detects when node rejects action`() {
        val pagedNode = TestAccessibleNode(
            className = "androidx.viewpager.widget.ViewPager",
            isScrollable = true,
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT),
            actionExecutionResult = false // Node at end of book rejects page advance
        )
        val rootNode = TestAccessibleNode(children = listOf(pagedNode))

        val result = AutoNavigator.executeNavigation(rootNode, null)
        assertTrue(result is AutoAdvanceActionResult.ActionRejectedByNode)
    }

    // =========================================================================
    // PART C: SETTINGS AND STATE MACHINE SAFETY TESTS
    // =========================================================================

    @Test
    fun `test auto advance setting defaults to false`() {
        val settings = ReadMeSettings()
        assertFalse(settings.isAutoAdvanceScreenReadingEnabled)
    }

    @Test
    fun `test settings data model preserves auto advance flag`() {
        val enabledSettings = ReadMeSettings(
            isCrossAppReadingEnabled = true,
            isAutoAdvanceScreenReadingEnabled = true
        )
        assertTrue(enabledSettings.isCrossAppReadingEnabled)
        assertTrue(enabledSettings.isAutoAdvanceScreenReadingEnabled)
    }
}
