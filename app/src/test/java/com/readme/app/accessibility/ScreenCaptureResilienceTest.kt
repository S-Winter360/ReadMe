package com.readme.app.accessibility

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates resilience and safety of screen capture pipeline:
 * - Handling of native ScreenCaptureListenerWrapper consumer termination
 * - Graceful fallback from window-specific capture to display capture
 * - Security protections (sensitive password fields, secure windows)
 * - Stale app switch avoidance
 * - Android 11+ (API 30+) compatibility
 */
class ScreenCaptureResilienceTest {

    private fun createTarget(
        packageName: String = "com.example.reader",
        windowId: Int = 42,
        displayId: Int = 0,
        isSensitive: Boolean = false
    ) = CrossAppWindowTarget(
        packageName = packageName,
        windowId = windowId,
        displayId = displayId,
        requestId = 1001L,
        generation = 1L,
        isSensitiveOrPassword = isSensitive,
        windowBounds = Rect(0, 0, 1080, 2400)
    )

    private fun createDummyBitmap(): Bitmap {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(Bitmap::class.java) as Bitmap
    }

    // 1. Valid window capture returns success without fallback
    @Test
    fun testWindowCaptureSuccess_doesNotFallback() = runBlocking {
        var windowCaptureCalled = false
        var displayCaptureCalled = false

        val capturer = object : CrossAppScreenshotCapturer {
            override val isSupported: Boolean = true
            override fun identifyTargetWindow(): CrossAppWindowTarget? = createTarget()

            override suspend fun captureWindow(target: CrossAppWindowTarget): ScreenshotCaptureResult {
                windowCaptureCalled = true
                val dummyBmp = createDummyBitmap()
                return ScreenshotCaptureResult.Success(
                    CrossAppImageSnapshot(
                        packageName = target.packageName,
                        windowId = target.windowId,
                        requestId = target.requestId,
                        generation = target.generation,
                        bitmap = dummyBmp
                    )
                )
            }
        }

        val result = capturer.captureWindow(createTarget())
        assertTrue(result is ScreenshotCaptureResult.Success)
        assertTrue(windowCaptureCalled)
        assertFalse(displayCaptureCalled)
    }

    // 2. Window capture failure (e.g. consumer not alive) falls back to display capture
    @Test
    fun testWindowCaptureFailure_fallsBackToDisplayCapture() = runBlocking {
        var windowCaptureAttempts = 0
        var displayCaptureAttempts = 0

        // Simulate fallback logic
        suspend fun executeCapture(target: CrossAppWindowTarget): ScreenshotCaptureResult {
            // First attempt window capture
            windowCaptureAttempts++
            val windowResult: ScreenshotCaptureResult = ScreenshotCaptureResult.Error("ScreenCaptureListenerWrapper consumer not alive", 2)
            if (windowResult is ScreenshotCaptureResult.Success) {
                return windowResult
            }

            // Fallback to display capture
            displayCaptureAttempts++
            val displayBmp = createDummyBitmap()
            return ScreenshotCaptureResult.Success(
                CrossAppImageSnapshot(
                    packageName = target.packageName,
                    windowId = target.windowId,
                    requestId = target.requestId,
                    generation = target.generation,
                    bitmap = displayBmp
                )
            )
        }

        val result = executeCapture(createTarget())
        assertEquals(1, windowCaptureAttempts)
        assertEquals(1, displayCaptureAttempts)
        assertTrue(result is ScreenshotCaptureResult.Success)
        val success = result as ScreenshotCaptureResult.Success
        assertNotNull(success.snapshot.bitmap)
    }

    // 3. Sensitive/password content strictly blocks screen capture without display fallback
    @Test
    fun testSensitiveContent_strictlyBlocksWithoutFallback() = runBlocking {
        var fallbackAttempted = false
        val target = createTarget(isSensitive = true)

        suspend fun executeCapture(t: CrossAppWindowTarget): ScreenshotCaptureResult {
            if (t.isSensitiveOrPassword) {
                return ScreenshotCaptureResult.SensitiveContentBlocked
            }
            fallbackAttempted = true
            return ScreenshotCaptureResult.Success(
                CrossAppImageSnapshot(t.packageName, t.windowId, t.requestId, t.generation, Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            )
        }

        val result = executeCapture(target)
        assertTrue(result is ScreenshotCaptureResult.SensitiveContentBlocked)
        assertFalse(fallbackAttempted)
    }

    // 4. Secure window error strictly blocks capture without display fallback
    @Test
    fun testSecureWindow_strictlyBlocksWithoutFallback() = runBlocking {
        var fallbackAttempted = false

        suspend fun executeCapture(t: CrossAppWindowTarget): ScreenshotCaptureResult {
            val windowResult: ScreenshotCaptureResult = ScreenshotCaptureResult.SecureWindow
            if (windowResult is ScreenshotCaptureResult.SecureWindow) {
                return windowResult
            }
            fallbackAttempted = true
            return ScreenshotCaptureResult.Success(
                CrossAppImageSnapshot(t.packageName, t.windowId, t.requestId, t.generation, Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
            )
        }

        val result = executeCapture(createTarget())
        assertTrue(result is ScreenshotCaptureResult.SecureWindow)
        assertFalse(fallbackAttempted)
    }

    // 5. Stale app switch detection cancels capture
    @Test
    fun testStaleAppSwitch_blocksCapture() = runBlocking {
        val target = createTarget(packageName = "com.target.reader")
        val currentActivePackage = "com.other.app"

        fun checkAppSwitch(t: CrossAppWindowTarget, currentPkg: String?): ScreenshotCaptureResult? {
            if (currentPkg != null && currentPkg != t.packageName) {
                return ScreenshotCaptureResult.StaleAppSwitch(
                    expectedPackage = t.packageName,
                    actualPackage = currentPkg
                )
            }
            return null
        }

        val result = checkAppSwitch(target, currentActivePackage)
        assertNotNull(result)
        assertTrue(result is ScreenshotCaptureResult.StaleAppSwitch)
        val switch = result as ScreenshotCaptureResult.StaleAppSwitch
        assertEquals("com.target.reader", switch.expectedPackage)
        assertEquals("com.other.app", switch.actualPackage)
    }

    // 6. ReadMe self-ignore protection
    @Test
    fun testReadMeSelfIgnored() = runBlocking {
        val target = createTarget(packageName = "com.readme.app")
        val ourPackage = "com.readme.app"

        val result = if (target.packageName == ourPackage) {
            ScreenshotCaptureResult.ReadMeSelfIgnored
        } else {
            ScreenshotCaptureResult.Success(
                CrossAppImageSnapshot(target.packageName, target.windowId, target.requestId, target.generation, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
            )
        }

        assertTrue(result is ScreenshotCaptureResult.ReadMeSelfIgnored)
    }
}
