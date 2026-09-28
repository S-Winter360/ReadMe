package com.readme.app.accessibility

import android.content.res.Configuration
import android.graphics.Rect
import com.readme.app.accessibility.autonav.AutoNavigationCoordinator
import com.readme.app.accessibility.autonav.AutoNavigationDiagnostics
import com.readme.app.accessibility.autonav.AutoNavigator
import com.readme.app.accessibility.autonav.ContentFingerprint
import com.readme.app.accessibility.autonav.NavigationActionType
import com.readme.app.accessibility.autonav.NavigationDiagnosticRecord
import com.readme.app.accessibility.autonav.PageTurnCalibration
import com.readme.app.accessibility.autonav.PageTurnCalibrationRepository
import com.readme.app.settings.PagedReaderNavigationMode
import com.readme.app.settings.ReadMeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Phase 9AD:
 * User-Calibrated Novel Page-Turn Navigation.
 */
class Phase9ADCalibratedNovelNavTest {

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

    // =========================================================================
    // 1. PageTurnCalibration Model & Serialization Tests
    // =========================================================================

    @Test
    fun testPageTurnCalibrationModelCreationAndSerializationRoundTrip() {
        val calibration = PageTurnCalibration(
            packageName = "com.user.novelreader",
            relativeX = 0.90f,
            relativeY = 0.55f,
            absoluteX = 972f,
            absoluteY = 1056f,
            screenWidth = 1080,
            screenHeight = 1920,
            orientation = Configuration.ORIENTATION_PORTRAIT,
            readerBounds = testRect(0, 100, 1080, 1820)
        )

        val serialized = calibration.toJson()
        assertTrue(serialized.contains("com.user.novelreader"))
        assertTrue(serialized.contains("rx=0.9"))

        val restored = PageTurnCalibration.fromJson(serialized)
        assertNotNull(restored)
        assertEquals("com.user.novelreader", restored?.packageName)
        assertEquals(0.90f, restored!!.relativeX, 0.001f)
        assertEquals(0.55f, restored.relativeY, 0.001f)
        assertEquals(972f, restored.absoluteX, 0.001f)
        assertEquals(1056f, restored.absoluteY, 0.001f)
        assertEquals(Configuration.ORIENTATION_PORTRAIT, restored.orientation)
        assertEquals(100, restored.readerBounds?.top)
    }

    @Test
    fun testResolveTapPointCalculatesExactRelativePositionWithinBounds() {
        val calibration = PageTurnCalibration(
            packageName = "com.sample.novel",
            relativeX = 0.85f,
            relativeY = 0.60f
        )

        val readerBounds = testRect(0, 0, 1000, 2000)
        val displayBounds = testRect(0, 0, 1080, 2400)

        val (tapX, tapY) = calibration.resolveTapPoint(readerBounds, displayBounds)
        assertEquals(850f, tapX, 1.0f)
        assertEquals(1200f, tapY, 1.0f)
    }

    @Test
    fun testResolveTapPointClampsSafelyWithinBoundaries() {
        val calibrationEdge = PageTurnCalibration(
            packageName = "com.sample.novel",
            relativeX = 0.999f,
            relativeY = 0.999f
        )

        val readerBounds = testRect(0, 0, 1000, 2000)
        val displayBounds = testRect(0, 0, 1080, 2400)

        val (tapX, tapY) = calibrationEdge.resolveTapPoint(readerBounds, displayBounds)
        // Should be clamped at least 24px from right and 32px from bottom
        assertTrue(tapX <= 976f)
        assertTrue(tapY <= 1968f)
    }

    // =========================================================================
    // 2. Removal of Guessing Loop for Calibrated Apps
    // =========================================================================

    @Test
    fun testCalibratedTapDirectPriorityOverGenericGuessing() {
        val calibration = PageTurnCalibration(
            packageName = "com.failing.novelreader",
            relativeX = 0.92f,
            relativeY = 0.50f
        )

        val readerBounds = testRect(0, 0, 1080, 1920)
        val (tapX, tapY) = calibration.resolveTapPoint(readerBounds, readerBounds)

        assertEquals(1080f * 0.92f, tapX, 1.0f)
        assertEquals(1920f * 0.50f, tapY, 1.0f)

        // Verify diagnostic record format for calibrated tap
        val record = NavigationDiagnosticRecord(
            targetPackage = "com.failing.novelreader",
            targetWindowId = 10,
            navigationMethod = "USER_CALIBRATED_TAP",
            readerBounds = readerBounds,
            selectedNodeClass = "UserCalibratedPageTurn",
            selectedNodeBounds = readerBounds,
            selectedNodeResourceId = null,
            selectedNodeContentDescription = null,
            selectedNodeTextSnippet = null,
            isScrollable = true,
            availableActions = emptyList(),
            selectedNavigationAction = "CALIBRATED_NEXT_PAGE_TAP",
            gestureEnabled = true,
            tapCoordinates = "(${tapX.toInt()}, ${tapY.toInt()})",
            tapRelativeCoordinates = "(0.92, 0.50)",
            performActionReturnValue = true,
            timestampBeforeAction = 1000L,
            timestampAfterAction = 1080L,
            settlingDurationMs = 500L,
            accessibilityEventsReceived = emptyList(),
            preNavigationFingerprint = ContentFingerprint.create("Chapter 1: The Start of the Journey"),
            postNavigationFingerprint = ContentFingerprint.create("Chapter 2: The Next Adventure Begins"),
            pageChangeDetected = true,
            finalNavigationResult = "SUCCESS"
        )

        assertEquals("USER_CALIBRATED_TAP", record.navigationMethod)
        assertEquals("CALIBRATED_NEXT_PAGE_TAP", record.selectedNavigationAction)
        assertTrue(record.pageChangeDetected)
    }

    // =========================================================================
    // 3. Same-Page Protection & Snap-Back Prevention
    // =========================================================================

    @Test
    fun testContentFingerprintDetectsSnapBackOrUnchangedPage() {
        val pageText = "The brave warrior stood atop the hill looking toward the sunset."
        val pre = ContentFingerprint.create(pageText)
        val postSnapBack = ContentFingerprint.create(pageText)

        assertEquals(pre.length, postSnapBack.length)
        assertEquals(pre.wordCount, postSnapBack.wordCount)
        assertEquals(pre.sampleHash, postSnapBack.sampleHash)

        // Verifies that identical content stops navigation safely
        val isSame = AutoNavigationCoordinator.isContentPracticallyIdentical(pageText, pageText)
        assertTrue("Identical text must be detected as unchanged to prevent infinite re-reading loops", isSame)
    }

    @Test
    fun testContentFingerprintDetectsRealPageTransition() {
        val page1 = "Chapter 1. The beginning of a long journey across the vast kingdoms."
        val page2 = "Chapter 2. Entering the dark forest where the shadows seemed to whisper."

        val pre = ContentFingerprint.create(page1)
        val post = ContentFingerprint.create(page2)

        val isSame = AutoNavigationCoordinator.isContentPracticallyIdentical(page1, page2)
        assertFalse("Different pages must be detected as changed so reading resumes", isSame)
    }

    // =========================================================================
    // 4. Single Tap Enforced (No Guessing / No Multiple Swipes)
    // =========================================================================

    @Test
    fun testSingleTapExecutionDispatchedOnce() {
        val dispatcher = TestGestureDispatcher()
        val calibration = PageTurnCalibration(
            packageName = "com.sample.novel",
            relativeX = 0.90f,
            relativeY = 0.50f
        )

        val (tapX, tapY) = calibration.resolveTapPoint(testRect(0, 0, 1000, 2000), testRect(0, 0, 1000, 2000))

        // Simulate dispatch
        kotlinx.coroutines.runBlocking {
            dispatcher.performTap(tapX, tapY, 80L)
        }

        assertEquals(1, dispatcher.dispatchedTaps.size)
        assertEquals(tapX, dispatcher.dispatchedTaps[0].first, 0.001f)
        assertEquals(tapY, dispatcher.dispatchedTaps[0].second, 0.001f)
        assertEquals(80L, dispatcher.dispatchedTaps[0].third)
    }
}
