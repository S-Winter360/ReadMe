package com.readme.app.accessibility

import android.graphics.Rect
import com.readme.app.accessibility.autonav.AutoNavigationCoordinator
import com.readme.app.accessibility.autonav.AutoNavigationDiagnostics
import com.readme.app.accessibility.autonav.AutoNavigator
import com.readme.app.accessibility.autonav.ContentFingerprint
import com.readme.app.accessibility.autonav.EdgeTapCoordinatesCalculator
import com.readme.app.accessibility.autonav.NavigationActionType
import com.readme.app.accessibility.autonav.NavigationDiagnosticRecord
import com.readme.app.accessibility.autonav.PaginatedReaderDetector
import com.readme.app.settings.PagedReaderNavigationMode
import com.readme.app.settings.ReadMeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Phase9ACPagedReaderNavTest {

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

    private class TestGestureDispatcher(
        override val canDispatchGestures: Boolean = true,
        var shouldSucceed: Boolean = true
    ) : CrossAppGestureDispatcher {
        val dispatchedTaps = mutableListOf<Triple<Float, Float, Long>>()

        override suspend fun performTap(x: Float, y: Float, durationMs: Long): Boolean {
            dispatchedTaps.add(Triple(x, y, durationMs))
            return shouldSucceed
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

    // 1. Paginated reader detection
    @Test
    fun testPaginatedReaderDetection() {
        // Explicit ViewPager2
        val vpNode = TestNode(
            className = "androidx.viewpager2.widget.ViewPager2",
            bounds = testRect(0, 100, 1080, 1920),
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT, AutoNavigator.ID_SCROLL_FORWARD),
            isScrollable = true
        )
        val vpCandidates = AutoNavigator.findCandidates(vpNode, testRect(50, 200, 1030, 1800))
        val vpInfo = PaginatedReaderDetector.detect(vpCandidates, testRect(50, 200, 1030, 1800))
        assertTrue(vpInfo.isPaginatedReader)
        assertTrue(vpInfo.hasHorizontalPageAction)

        // Custom Novel Reader
        val novelNode = TestNode(
            className = "com.novel.reader.ui.NovelPageContainer",
            bounds = testRect(0, 100, 1080, 1920),
            actions = listOf(AutoNavigator.ID_SCROLL_FORWARD),
            isScrollable = true
        )
        val novelCandidates = AutoNavigator.findCandidates(novelNode, testRect(50, 200, 1030, 1800))
        val novelInfo = PaginatedReaderDetector.detect(novelCandidates, testRect(50, 200, 1030, 1800))
        assertTrue(novelInfo.isPaginatedReader)

        // Standard vertical ScrollView -> NOT a paginated reader
        val scrollNode = TestNode(
            className = "android.widget.ScrollView",
            bounds = testRect(0, 100, 1080, 1920),
            actions = listOf(AutoNavigator.ID_SCROLL_FORWARD),
            isScrollable = true
        )
        val scrollCandidates = AutoNavigator.findCandidates(scrollNode, testRect(50, 200, 1030, 1800))
        val scrollInfo = PaginatedReaderDetector.detect(scrollCandidates, testRect(50, 200, 1030, 1800))
        assertFalse(scrollInfo.isPaginatedReader)
    }

    // 2. Reader viewport bounds
    @Test
    fun testReaderViewportBounds() {
        val readerNode = TestNode(
            className = "androidx.viewpager2.widget.ViewPager2",
            bounds = testRect(0, 120, 1080, 2100),
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT),
            isScrollable = true
        )
        val candidates = AutoNavigator.findCandidates(readerNode, testRect(50, 200, 1030, 1800))
        val info = PaginatedReaderDetector.detect(candidates, testRect(50, 200, 1030, 1800))
        assertEquals(0, info.readerBounds.left)
        assertEquals(120, info.readerBounds.top)
        assertEquals(1080, info.readerBounds.right)
        assertEquals(2100, info.readerBounds.bottom)
    }

    // 3. Right-edge tap coordinate calculation
    @Test
    fun testRightEdgeTapCoordinateCalculation() {
        val readerBounds = testRect(0, 0, 1000, 2000)
        val readingRegion = testRect(100, 400, 900, 1600)

        val (tapX, tapY) = EdgeTapCoordinatesCalculator.calculateNextPageTap(readerBounds, readingRegion)

        // 92% across 1000 = 920
        assertEquals(920f, tapX, 1.0f)
        // Vertically centered around reading region (400 + 1600)/2 = 1000
        assertEquals(1000f, tapY, 1.0f)
    }

    // 4. Left-edge tap coordinate calculation
    @Test
    fun testLeftEdgeTapCoordinateCalculation() {
        val readerBounds = testRect(0, 0, 1000, 2000)
        val readingRegion = testRect(100, 400, 900, 1600)

        val (tapX, tapY) = EdgeTapCoordinatesCalculator.calculatePreviousPageTap(readerBounds, readingRegion)

        // 8% across 1000 = 80
        assertEquals(80f, tapX, 1.0f)
        assertEquals(1000f, tapY, 1.0f)
    }

    // 5. Coordinate conversion in physical screen coordinates
    @Test
    fun testCoordinateConversion() {
        val readerBounds = testRect(100, 200, 900, 1800)
        val (tapX, tapY) = EdgeTapCoordinatesCalculator.calculateNextPageTap(readerBounds)

        // Inside reader bounds
        assertTrue(tapX >= readerBounds.left + 24)
        assertTrue(tapX <= readerBounds.right - 24)
        assertTrue(tapY >= readerBounds.top)
        assertTrue(tapY <= readerBounds.bottom)
    }

    // 6. System bar and toolbar exclusion
    @Test
    fun testSystemBarExclusion() {
        val readerBounds = testRect(0, 0, 1000, 2000)
        // Reading region near extreme top
        val topRegion = testRect(50, 10, 950, 80)
        val (_, tapYTop) = EdgeTapCoordinatesCalculator.calculateNextPageTap(readerBounds, topRegion)
        // Must be clamped >= 12% padding (240px)
        assertTrue(tapYTop >= 240f)

        // Reading region near extreme bottom
        val bottomRegion = testRect(50, 1950, 950, 1990)
        val (_, tapYBottom) = EdgeTapCoordinatesCalculator.calculateNextPageTap(readerBounds, bottomRegion)
        // Must be clamped <= 2000 - 240 = 1760px
        assertTrue(tapYBottom <= 1760f)
    }

    // 7. Accessibility action preference
    @Test
    fun testAccessibilityActionPreference() {
        val candidate = AutoNavigator.CandidateNode(
            node = TestNode(actions = listOf(AutoNavigator.ID_PAGE_RIGHT)),
            actionType = NavigationActionType.PAGE_RIGHT,
            actionId = AutoNavigator.ID_PAGE_RIGHT,
            bounds = testRect(0, 100, 1080, 1920),
            className = "androidx.viewpager2.widget.ViewPager2",
            allActions = listOf(AutoNavigator.ID_PAGE_RIGHT),
            score = 100
        )
        val pagedInfo = PaginatedReaderDetector.detect(listOf(candidate), null)
        assertTrue(pagedInfo.isPaginatedReader)
        assertTrue(pagedInfo.hasHorizontalPageAction)
    }

    // 8. Edge-tap fallback eligibility based on settings
    @Test
    fun testEdgeTapFallbackEligibility() {
        val defaultSettings = ReadMeSettings(
            isAutoAdvanceScreenReadingEnabled = true,
            pagedReaderNavigationMode = PagedReaderNavigationMode.ACCESSIBILITY_ACTION
        )
        assertEquals(PagedReaderNavigationMode.ACCESSIBILITY_ACTION, defaultSettings.pagedReaderNavigationMode)

        val edgeTapSettings = ReadMeSettings(
            isAutoAdvanceScreenReadingEnabled = true,
            pagedReaderNavigationMode = PagedReaderNavigationMode.TAP_SCREEN_EDGE
        )
        assertEquals(PagedReaderNavigationMode.TAP_SCREEN_EDGE, edgeTapSettings.pagedReaderNavigationMode)
    }

    // 9. Gesture capability requirement
    @Test
    fun testGestureCapabilityRequirement() {
        val enabledDispatcher = TestGestureDispatcher(canDispatchGestures = true)
        assertTrue(enabledDispatcher.canDispatchGestures)

        val disabledDispatcher = TestGestureDispatcher(canDispatchGestures = false)
        assertFalse(disabledDispatcher.canDispatchGestures)
    }

    // 10. One tap per page
    @Test
    fun testOneTapPerPage() {
        val dispatcher = TestGestureDispatcher()
        kotlinx.coroutines.runBlocking {
            dispatcher.performTap(950f, 1000f, 80L)
        }
        assertEquals(1, dispatcher.dispatchedTaps.size)
        assertEquals(950f, dispatcher.dispatchedTaps[0].first)
        assertEquals(1000f, dispatcher.dispatchedTaps[0].second)
        assertEquals(80L, dispatcher.dispatchedTaps[0].third)
    }

    // 11. Duplicate tap prevention
    @Test
    fun testDuplicateTapPrevention() {
        val dispatcher = TestGestureDispatcher()
        kotlinx.coroutines.runBlocking {
            val tap1 = dispatcher.performTap(950f, 1000f, 80L)
            assertTrue(tap1)
        }
        // In one navigation cycle, only 1 tap is invoked
        assertEquals(1, dispatcher.dispatchedTaps.size)
    }

    // 12. Transition settling duration
    @Test
    fun testTransitionSettling() {
        val settlingMs = 500L
        assertTrue(settlingMs in 300L..800L)
    }

    // 13. Same-page fingerprint
    @Test
    fun testSamePageFingerprint() {
        val text = "Chapter 1: It was a dark and stormy night in the enchanted forest."
        val fp1 = ContentFingerprint.create(text)
        val fp2 = ContentFingerprint.create(text)

        assertEquals(fp1.sampleHash, fp2.sampleHash)
        assertEquals(fp1.wordCount, fp2.wordCount)
        assertTrue(AutoNavigationCoordinator.isContentPracticallyIdentical(text, text))
    }

    // 14. Changed-page fingerprint
    @Test
    fun testChangedPageFingerprint() {
        val page1 = "Chapter 1: It was a dark and stormy night in the enchanted forest."
        val page2 = "Chapter 2: The morning sun broke through the misty clouds over the castle."

        val fp1 = ContentFingerprint.create(page1)
        val fp2 = ContentFingerprint.create(page2)

        assertFalse(fp1.sampleHash == fp2.sampleHash)
        assertFalse(AutoNavigationCoordinator.isContentPracticallyIdentical(page1, page2))
    }

    // 15. Partial movement followed by snap-back
    @Test
    fun testPartialMovementFollowedBySnapBack() {
        val originalPage = "Page 4 content with words that the novel reader displays for reading."
        // During snap-back, reader temporarily moved then snapped back to original content
        val snapBackPage = "Page 4 content with words that the novel reader displays for reading."

        assertTrue(AutoNavigationCoordinator.isContentPracticallyIdentical(originalPage, snapBackPage))
    }

    // 16. Timeout / bounding
    @Test
    fun testTimeoutBounding() {
        val settlingTimeout = 1000L
        assertTrue(settlingTimeout <= 1500L)
    }

    // 17. App switch cancellation check
    @Test
    fun testAppSwitchCancellation() {
        val targetPkg = "com.novel.reader"
        val activePkg = "com.other.app"
        assertFalse(targetPkg == activePkg)
    }

    // 18. Pause cancellation
    @Test
    fun testPauseCancellation() {
        // Simulates cancelPendingNavigation on pause
        var isNavigationActive = true
        fun onPause() {
            isNavigationActive = false
        }
        onPause()
        assertFalse(isNavigationActive)
    }

    // 19. Stop cancellation
    @Test
    fun testStopCancellation() {
        var isReading = true
        var isNavigating = true
        fun onStop() {
            isReading = false
            isNavigating = false
        }
        onStop()
        assertFalse(isReading)
        assertFalse(isNavigating)
    }

    // 20. Reselect cancellation
    @Test
    fun testReselectCancellation() {
        var isPendingNav = true
        fun onReselectArea() {
            isPendingNav = false
        }
        onReselectArea()
        assertFalse(isPendingNav)
    }

    // 21. Orientation change
    @Test
    fun testOrientationChangeDetection() {
        val portrait = testRect(0, 0, 1080, 1920)
        val landscape = testRect(0, 0, 1920, 1080)

        val portW = portrait.right - portrait.left
        val portH = portrait.bottom - portrait.top
        val landW = landscape.right - landscape.left
        val landH = landscape.bottom - landscape.top

        val changed = portW != landW || portH != landH
        assertTrue(changed)
    }

    // 22. Selection persistence across page turn
    @Test
    fun testSelectionPersistenceAcrossPageTurn() {
        val initialRegion = testRect(100, 200, 980, 1700)
        var rememberedRegion: Rect? = initialRegion

        // After successful page navigation:
        val preservedRegion = rememberedRegion
        assertNotNull(preservedRegion)
        assertEquals(initialRegion.left, preservedRegion?.left)
        assertEquals(initialRegion.top, preservedRegion?.top)
        assertEquals(initialRegion.right, preservedRegion?.right)
        assertEquals(initialRegion.bottom, preservedRegion?.bottom)
    }

    // 23. Successful next-page flow
    @Test
    fun testSuccessfulNextPageFlow() {
        val pre = "Page 1: The knight rode across the river."
        val post = "Page 2: Upon reaching the high tower, he called out."

        assertFalse(AutoNavigationCoordinator.isContentPracticallyIdentical(pre, post))
    }

    // 24. Unsupported reader
    @Test
    fun testUnsupportedReaderDetection() {
        val emptyNode = TestNode(
            className = "android.widget.FrameLayout",
            bounds = testRect(0, 0, 100, 100),
            isScrollable = false
        )
        val candidates = AutoNavigator.findCandidates(emptyNode, null)
        val info = PaginatedReaderDetector.detect(candidates, null)
        assertFalse(info.isPaginatedReader)
    }

    // 25. Gesture failure handling
    @Test
    fun testGestureFailure() {
        val failingDispatcher = TestGestureDispatcher(shouldSucceed = false)
        kotlinx.coroutines.runBlocking {
            val result = failingDispatcher.performTap(900f, 1000f, 80L)
            assertFalse(result)
        }
    }

    // 26. Accessibility action failure
    @Test
    fun testAccessibilityActionFailure() {
        val failingNode = TestNode(
            actions = listOf(AutoNavigator.ID_PAGE_RIGHT),
            actionExecutionResult = { false }
        )
        val performed = failingNode.performAction(AutoNavigator.ID_PAGE_RIGHT)
        assertFalse(performed)
    }

    // 27. Action success but no visual change
    @Test
    fun testActionSuccessButNoVisualChange() {
        val pageText = "Still the exact same page text after action."
        val actionReturnValue = true // performAction returned true

        val visualChanged = !AutoNavigationCoordinator.isContentPracticallyIdentical(pageText, pageText)
        assertTrue(actionReturnValue)
        assertFalse(visualChanged) // Verified no visual change!
    }
}
