package com.readme.app.accessibility

import android.graphics.Rect
import com.readme.app.accessibility.autonav.AutoNavigationDiagnostics
import com.readme.app.accessibility.autonav.AutoNavigator
import com.readme.app.accessibility.autonav.ContentFingerprint
import com.readme.app.accessibility.autonav.NavigationActionType
import com.readme.app.accessibility.autonav.NavigationDiagnosticRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase9AANovelReaderNavDiagnosticTest {

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
        override val viewIdResourceName: String? = null,
        val bounds: Rect = Rect(),
        val actions: List<Int> = emptyList(),
        val children: List<AccessibleNode> = emptyList(),
        var actionExecutionResult: (Int) -> Boolean = { true }
    ) : AccessibleNode {
        override val childCount: Int get() = children.size
        override fun getChild(index: Int): AccessibleNode? = children.getOrNull(index)
        override fun recycle() {}
        override fun getBoundsInScreen(outBounds: Rect) {
            outBounds.left = bounds.left
            outBounds.top = bounds.top
            outBounds.right = bounds.right
            outBounds.bottom = bounds.bottom
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

    @Before
    fun setUp() {
        AutoNavigationDiagnostics.clear()
    }

    // =========================================================================
    // 1. CANDIDATE DISCOVERY & NOVEL READER SCORING
    // =========================================================================

    @Test
    fun `test findCandidates prioritizes ViewPager2 container over inner child RecyclerView`() {
        // Novel reader structure where ViewPager2 wraps an inner RecyclerView.
        // Direct scrolling on child RecyclerView can trigger snap-back, while ViewPager2 turns the page cleanly.
        val childRecyclerView = TestNode(
            className = "androidx.recyclerview.widget.RecyclerView",
            isScrollable = true,
            bounds = testRect(0, 100, 1080, 2300),
            actions = listOf(AutoNavigator.ID_SCROLL_FORWARD)
        )

        val outerViewPager2 = TestNode(
            className = "androidx.viewpager2.widget.ViewPager2",
            isScrollable = false, // ViewPager2 delegates scrolling to child, but handles page turning actions
            bounds = testRect(0, 100, 1080, 2300),
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT, AutoNavigator.ID_SCROLL_FORWARD),
            children = listOf(childRecyclerView)
        )

        val root = TestNode(
            className = "android.widget.FrameLayout",
            bounds = testRect(0, 0, 1080, 2400),
            children = listOf(outerViewPager2)
        )

        val readingRegion = testRect(50, 300, 1000, 1800)
        val candidates = AutoNavigator.findCandidates(root, readingRegion)

        assertFalse(candidates.isEmpty())
        val best = candidates.first()
        // Must select the outer ViewPager2, NOT the child RecyclerView!
        assertEquals("androidx.viewpager2.widget.ViewPager2", best.className)
        assertEquals(NavigationActionType.PAGE_RIGHT, best.actionType)
        assertEquals(AutoNavigator.ID_PAGE_RIGHT, best.actionId)
        // Fallback action should be available
        assertTrue(best.fallbackActions.any { it.first == NavigationActionType.SCROLL_FORWARD })
    }

    @Test
    fun `test candidate scoring penalizes non-reading controls like SeekBars and ProgressBars`() {
        val chapterSeekBar = TestNode(
            className = "android.widget.SeekBar",
            isScrollable = true,
            bounds = testRect(50, 2100, 1000, 2200),
            actions = listOf(AutoNavigator.ID_SCROLL_FORWARD)
        )

        val progressBar = TestNode(
            className = "android.widget.ProgressBar",
            isScrollable = true,
            bounds = testRect(0, 2250, 1080, 2280),
            actions = listOf(AutoNavigator.ID_SCROLL_FORWARD)
        )

        val customReaderView = TestNode(
            className = "com.novel.reader.CustomReaderView",
            isScrollable = true,
            bounds = testRect(0, 100, 1080, 2050),
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT, AutoNavigator.ID_SCROLL_FORWARD)
        )

        val root = TestNode(
            bounds = testRect(0, 0, 1080, 2400),
            children = listOf(chapterSeekBar, progressBar, customReaderView)
        )

        val readingRegion = testRect(50, 300, 1000, 1800)
        val candidates = AutoNavigator.findCandidates(root, readingRegion)

        // Custom reader view must win by a wide margin
        assertFalse(candidates.isEmpty())
        assertEquals("com.novel.reader.CustomReaderView", candidates.first().className)

        // SeekBars and ProgressBars must not appear in valid candidates
        assertFalse(candidates.any { it.className.contains("SeekBar") })
        assertFalse(candidates.any { it.className.contains("ProgressBar") })
    }

    // =========================================================================
    // 2. NODE GEOMETRY VALIDATION
    // =========================================================================

    @Test
    fun `test validateCandidateGeometry accepts candidate containing reading region`() {
        val candidateBounds = testRect(0, 100, 1080, 2300)
        val readingRegion = testRect(50, 400, 1000, 1800)

        val (isValid, reason) = AutoNavigator.validateCandidateGeometry(candidateBounds, readingRegion)
        assertTrue(isValid)
        assertEquals(null, reason)
    }

    @Test
    fun `test validateCandidateGeometry rejects candidate outside reading region`() {
        // Bottom toolbar or navigation bar outside the user-selected reading rectangle
        val candidateBounds = testRect(0, 2200, 1080, 2400)
        val readingRegion = testRect(50, 300, 1000, 1800)

        val (isValid, reason) = AutoNavigator.validateCandidateGeometry(candidateBounds, readingRegion)
        assertFalse(isValid)
        assertNotNull(reason)
        assertTrue(reason!!.contains("do not intersect"))
    }

    @Test
    fun `test validateCandidateGeometry rejects tiny non-reading controls`() {
        // Tiny floating button or indicator
        val candidateBounds = testRect(100, 500, 130, 530)
        val readingRegion = testRect(50, 300, 1000, 1800)

        val (isValid, reason) = AutoNavigator.validateCandidateGeometry(candidateBounds, readingRegion)
        assertFalse(isValid)
        assertNotNull(reason)
        assertTrue(reason!!.contains("below interactive minimum"))
    }

    @Test
    fun `test validateCandidateGeometry rejects empty bounds`() {
        val emptyBounds = testRect(0, 0, 0, 0)
        val readingRegion = testRect(50, 300, 1000, 1800)

        val (isValid, reason) = AutoNavigator.validateCandidateGeometry(emptyBounds, readingRegion)
        assertFalse(isValid)
        assertNotNull(reason)
        assertTrue(reason!!.contains("Empty or invalid"))
    }

    // =========================================================================
    // 3. CONTENT FINGERPRINTING & NAVIGATION DIAGNOSTICS
    // =========================================================================

    @Test
    fun `test ContentFingerprint generates deterministic non-sensitive metadata`() {
        val text1 = "The autumn leaves fell softly upon the ancient stone pavement of the temple courtyard."
        val text2 = "The autumn leaves fell softly upon the ancient stone pavement of the temple courtyard."
        val textDifferent = "A cold northern wind blew across the mountain peaks as twilight approached."

        val fp1 = ContentFingerprint.create(text1)
        val fp2 = ContentFingerprint.create(text2)
        val fpDiff = ContentFingerprint.create(textDifferent)

        assertEquals(fp1.length, fp2.length)
        assertEquals(fp1.wordCount, fp2.wordCount)
        assertEquals(fp1.sampleHash, fp2.sampleHash)
        assertEquals(fp1.previewSnippet, fp2.previewSnippet)

        // Different text produces different hash and preview
        assertFalse(fp1.sampleHash == fpDiff.sampleHash)
        assertFalse(fp1.previewSnippet == fpDiff.previewSnippet)

        // Empty/null text handles gracefully
        val fpEmpty = ContentFingerprint.create(null)
        assertEquals(0, fpEmpty.length)
        assertEquals("empty", fpEmpty.sampleHash)
    }

    @Test
    fun `test NavigationDiagnosticRecord captures all Phase 9AA required fields`() {
        val preFp = ContentFingerprint.create("Chapter 1: The Beginning")
        val postFp = ContentFingerprint.create("Chapter 2: The Path Ahead")

        val record = NavigationDiagnosticRecord(
            timestamp = 1727280000000L,
            targetPackage = "com.novel.reader",
            targetWindowId = 42,
            selectedNodeClass = "androidx.viewpager2.widget.ViewPager2",
            selectedNodeBounds = testRect(0, 100, 1080, 2300),
            selectedNodeResourceId = "com.novel.reader:id/story_pager",
            selectedNodeContentDescription = "Story Reader Pager",
            selectedNodeTextSnippet = "Chapter 1",
            isScrollable = true,
            availableActions = listOf(AutoNavigator.ID_PAGE_RIGHT, AutoNavigator.ID_SCROLL_FORWARD),
            selectedNavigationAction = "PAGE_RIGHT",
            performActionReturnValue = true,
            timestampBeforeAction = 1727280000100L,
            timestampAfterAction = 1727280000120L,
            accessibilityEventsReceived = listOf("TYPE_VIEW_SCROLLED", "TYPE_WINDOW_CONTENT_CHANGED"),
            preNavigationFingerprint = preFp,
            postNavigationFingerprint = postFp,
            pageChangeDetected = true,
            finalNavigationResult = "SUCCESS"
        )

        AutoNavigationDiagnostics.record(record)

        val latest = AutoNavigationDiagnostics.latestRecord
        assertNotNull(latest)
        assertEquals("com.novel.reader", latest?.targetPackage)
        assertEquals(42, latest?.targetWindowId)
        assertEquals("androidx.viewpager2.widget.ViewPager2", latest?.selectedNodeClass)
        assertEquals("com.novel.reader:id/story_pager", latest?.selectedNodeResourceId)
        assertEquals("PAGE_RIGHT", latest?.selectedNavigationAction)
        assertTrue(latest?.performActionReturnValue == true)
        assertTrue(latest?.pageChangeDetected == true)
        assertEquals("SUCCESS", latest?.finalNavigationResult)
        assertEquals(2, latest?.accessibilityEventsReceived?.size)
    }

    @Test
    fun `test AutoNavigationDiagnostics maintains bounded capacity without unbounded memory growth`() {
        val dummyFp = ContentFingerprint.create("sample")
        for (i in 1..30) {
            AutoNavigationDiagnostics.record(
                NavigationDiagnosticRecord(
                    targetPackage = "com.novel.reader",
                    targetWindowId = i,
                    selectedNodeClass = "ViewPager",
                    selectedNodeBounds = testRect(0, 0, 1080, 2400),
                    selectedNodeResourceId = null,
                    selectedNodeContentDescription = null,
                    selectedNodeTextSnippet = null,
                    isScrollable = true,
                    availableActions = emptyList(),
                    selectedNavigationAction = "PAGE_RIGHT",
                    performActionReturnValue = true,
                    timestampBeforeAction = 0L,
                    timestampAfterAction = 0L,
                    accessibilityEventsReceived = emptyList(),
                    preNavigationFingerprint = dummyFp,
                    postNavigationFingerprint = dummyFp,
                    pageChangeDetected = true,
                    finalNavigationResult = "SUCCESS"
                )
            )
        }

        // Bounded capacity must cap history (MAX_HISTORY = 20)
        val history = AutoNavigationDiagnostics.getHistory()
        assertEquals(20, history.size)
        // Most recent record should be windowId 30
        assertEquals(30, AutoNavigationDiagnostics.latestRecord?.targetWindowId)
    }

    // =========================================================================
    // 4. CANDIDATE FALLBACK ORDERING & ACTION CHAINS
    // =========================================================================

    @Test
    fun `test candidate discovery creates fallback action chain for custom reader container`() {
        // A custom novel reader container that exposes multiple page/scroll actions:
        // Primary: PAGE_RIGHT, Fallbacks: SCROLL_FORWARD, PAGE_DOWN
        val customReader = TestNode(
            className = "com.novel.reader.PageView",
            isScrollable = true,
            bounds = testRect(0, 150, 1080, 2200),
            actions = listOf(
                AutoNavigator.ID_PAGE_RIGHT,
                AutoNavigator.ID_SCROLL_FORWARD,
                AutoNavigator.ID_PAGE_DOWN
            )
        )
        val root = TestNode(children = listOf(customReader))

        val readingRegion = testRect(50, 300, 1000, 1900)
        val candidates = AutoNavigator.findCandidates(root, readingRegion)

        assertFalse(candidates.isEmpty())
        val candidate = candidates.first()
        assertEquals("com.novel.reader.PageView", candidate.className)
        assertEquals(NavigationActionType.PAGE_RIGHT, candidate.actionType)
        assertEquals(AutoNavigator.ID_PAGE_RIGHT, candidate.actionId)

        // Fallbacks must include SCROLL_FORWARD and PAGE_DOWN in proper reading order
        val fallbackTypes = candidate.fallbackActions.map { it.first }
        assertTrue(fallbackTypes.contains(NavigationActionType.SCROLL_FORWARD))
        assertTrue(fallbackTypes.contains(NavigationActionType.PAGE_DOWN))
    }

    @Test
    fun `test candidate scoring ranks container with reading region containment above partial overlapping child`() {
        val childPage = TestNode(
            className = "android.widget.FrameLayout",
            isScrollable = false,
            bounds = testRect(50, 1200, 1000, 2200), // Only covers lower half of reading region
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT)
        )

        val fullContainer = TestNode(
            className = "androidx.viewpager.widget.ViewPager",
            isScrollable = true,
            bounds = testRect(0, 100, 1080, 2300), // Encloses entire reading region
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT),
            children = listOf(childPage)
        )

        val root = TestNode(children = listOf(fullContainer))
        val readingRegion = testRect(50, 300, 1000, 1900)

        val candidates = AutoNavigator.findCandidates(root, readingRegion)
        assertFalse(candidates.isEmpty())
        // Top candidate must be the full container enclosing the reading region
        assertEquals("androidx.viewpager.widget.ViewPager", candidates.first().className)
    }
}
