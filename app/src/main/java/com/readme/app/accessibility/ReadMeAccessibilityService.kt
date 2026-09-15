package com.readme.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Dedicated, read-only AccessibilityService for acquiring textual content exposed by the
 * currently active external application or explicitly capturing a single window screenshot for local OCR.
 *
 * Enforces:
 * - Read-only operation: absolutely no gesture dispatching, touch injection, or UI manipulation.
 * - No continuous screen monitoring, automatic screenshots, or MediaProjection.
 * - No clipboard access.
 * - ReadMe self-filter: ignores and never acquires ReadMe's own package content.
 * - Minimal event handling: does not perform expensive traversals in [onAccessibilityEvent].
 * - Explicit user-initiated acquisition via [acquireCurrentText] and [captureWindow].
 * - Window screenshot capture restricted to API 34+ via [takeScreenshotOfWindow].
 */
class ReadMeAccessibilityService : AccessibilityService(), CrossAppTextAcquirer, CrossAppScreenshotCapturer {

    private val generationCounter = AtomicLong(0L)

    @Volatile
    var currentActivePackage: String? = null
        private set

    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            com.readme.app.reading.service.ReadMeReadingService.syncService(this)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        _activePackageFlow.value = null
        try {
            com.readme.app.reading.service.ReadMeReadingService.syncService(this)
        } catch (e: Exception) {}
    }

    private val IGNORED_SYSTEM_PACKAGES by lazy {
        setOf("com.android.systemui", packageName)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg !in IGNORED_SYSTEM_PACKAGES) {
            currentActivePackage = pkg
            _activePackageFlow.value = pkg
        }
    }

    override fun onInterrupt() {
        // No-op: service is purely passive and read-only
    }

    override fun acquireCurrentText(): CrossAppTextSnapshot? {
        val root = rootInActiveWindow ?: return null
        return try {
            val pkg = root.packageName?.toString() ?: ""
            if (pkg.isBlank() || pkg in IGNORED_SYSTEM_PACKAGES) {
                // ReadMe self-filter or system bar filter
                return CrossAppTextSnapshot(
                    sourcePackageName = pkg.ifBlank { packageName },
                    blocks = emptyList()
                )
            }

            val gen = generationCounter.incrementAndGet()
            val windowId = root.windowId

            CrossAppTextExtractor.extract(
                root = AndroidAccessibleNode(root),
                sourcePackageName = pkg,
                sourceWindowId = windowId,
                generation = gen
            )
        } finally {
            try {
                @Suppress("DEPRECATION")
                root.recycle()
            } catch (_: Throwable) {
                // Framework handles recycling on newer Android versions
            }
        }
    }

    override fun identifyTargetWindow(): CrossAppWindowTarget? {
        if (!isSupported) return null

        // 1. Check interactive windows, strictly prioritizing application windows (TYPE_APPLICATION)
        val windowList = try { windows } catch (_: Throwable) { null }
        if (windowList != null) {
            // First pass: look for TYPE_APPLICATION windows
            val appWindows = windowList.filter { win ->
                win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
            }

            // If currentActivePackage is set, prioritize matching application window
            val preferredWindow = if (!currentActivePackage.isNullOrBlank()) {
                appWindows.firstOrNull { win ->
                    val root = try { win.root } catch (_: Throwable) { null }
                    try {
                        root?.packageName?.toString() == currentActivePackage
                    } finally {
                        try {
                            @Suppress("DEPRECATION")
                            root?.recycle()
                        } catch (_: Throwable) {}
                    }
                }
            } else {
                null
            }

            val targetWin = preferredWindow ?: appWindows.firstOrNull()

            if (targetWin != null) {
                val root = try { targetWin.root } catch (_: Throwable) { null }
                try {
                    val pkg = root?.packageName?.toString() ?: currentActivePackage ?: ""
                    if (pkg.isNotBlank() && pkg !in IGNORED_SYSTEM_PACKAGES) {
                        val hasSensitive = root?.let { detectSensitiveFields(it) } ?: false
                        val winBounds = android.graphics.Rect()
                        targetWin.getBoundsInScreen(winBounds)
                        if (winBounds.isEmpty) {
                            root?.getBoundsInScreen(winBounds)
                        }
                        if (winBounds.isEmpty) {
                            val dm = resources.displayMetrics
                            winBounds.set(0, 0, dm.widthPixels, dm.heightPixels)
                        }
                        return CrossAppWindowTarget(
                            packageName = pkg,
                            windowId = targetWin.id,
                            displayId = targetWin.displayId,
                            requestId = System.currentTimeMillis(),
                            generation = generationCounter.incrementAndGet(),
                            isSensitiveOrPassword = hasSensitive,
                            windowBounds = winBounds
                        )
                    }
                } finally {
                    try {
                        @Suppress("DEPRECATION")
                        root?.recycle()
                    } catch (_: Throwable) {}
                }
            }
        }

        // 2. Fallback to rootInActiveWindow only if not in IGNORED_SYSTEM_PACKAGES
        val currentRoot = try { rootInActiveWindow } catch (_: Throwable) { null }
        try {
            if (currentRoot != null) {
                val pkg = currentRoot.packageName?.toString() ?: ""
                if (pkg.isNotBlank() && pkg !in IGNORED_SYSTEM_PACKAGES) {
                    val hasSensitive = detectSensitiveFields(currentRoot)
                    val rootBounds = android.graphics.Rect()
                    currentRoot.getBoundsInScreen(rootBounds)
                    if (rootBounds.isEmpty) {
                        val dm = resources.displayMetrics
                        rootBounds.set(0, 0, dm.widthPixels, dm.heightPixels)
                    }
                    return CrossAppWindowTarget(
                        packageName = pkg,
                        windowId = currentRoot.windowId,
                        displayId = 0,
                        requestId = System.currentTimeMillis(),
                        generation = generationCounter.incrementAndGet(),
                        isSensitiveOrPassword = hasSensitive,
                        windowBounds = rootBounds
                    )
                }
            }
        } finally {
            try {
                @Suppress("DEPRECATION")
                currentRoot?.recycle()
            } catch (_: Throwable) {}
        }

        return null
    }

    override suspend fun captureWindow(target: CrossAppWindowTarget): ScreenshotCaptureResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ScreenshotCaptureResult.ApiNotSupported
        }

        if (target.packageName == packageName) {
            return ScreenshotCaptureResult.ReadMeSelfIgnored
        }

        if (target.isSensitiveOrPassword) {
            return ScreenshotCaptureResult.SensitiveContentBlocked
        }

        val currentPkg = currentActivePackage
        if (currentPkg != null && currentPkg != target.packageName && currentPkg != packageName) {
            return ScreenshotCaptureResult.StaleAppSwitch(
                expectedPackage = target.packageName,
                actualPackage = currentPkg
            )
        }

        return captureWindowApi34(target)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun captureWindowApi34(target: CrossAppWindowTarget): ScreenshotCaptureResult =
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(this)
            try {
                takeScreenshotOfWindow(
                    target.windowId,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val postPkg = currentActivePackage
                            if (postPkg != null && postPkg != target.packageName && postPkg != packageName) {
                                try {
                                    screenshotResult.hardwareBuffer.close()
                                } catch (_: Throwable) {}
                                if (continuation.isActive) {
                                    continuation.resume(
                                        ScreenshotCaptureResult.StaleAppSwitch(
                                            expectedPackage = target.packageName,
                                            actualPackage = postPkg
                                        )
                                    )
                                }
                                return
                            }

                            var softwareBitmap: Bitmap? = null
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                if (hwBitmap != null) {
                                    softwareBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                    softwareBitmap?.density = resources.displayMetrics.densityDpi
                                }
                            } catch (e: Throwable) {
                                softwareBitmap?.recycle()
                                softwareBitmap = null
                            } finally {
                                try {
                                    hardwareBuffer.close()
                                } catch (_: Throwable) {}
                            }

                            if (softwareBitmap == null) {
                                if (continuation.isActive) {
                                    continuation.resume(
                                        ScreenshotCaptureResult.Error("Failed to convert window buffer to bitmap")
                                    )
                                }
                                return
                            }

                            if (continuation.isActive) {
                                continuation.resume(
                                    ScreenshotCaptureResult.Success(
                                        CrossAppImageSnapshot(
                                            packageName = target.packageName,
                                            windowId = target.windowId,
                                            requestId = target.requestId,
                                            generation = target.generation,
                                            bitmap = softwareBitmap
                                        )
                                    )
                                )
                            } else {
                                softwareBitmap.recycle()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (!continuation.isActive) return
                            val result = when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> ScreenshotCaptureResult.SecureWindow
                                ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> ScreenshotCaptureResult.InvalidTarget
                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> ScreenshotCaptureResult.RateLimited
                                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> ScreenshotCaptureResult.ServiceNotConnected
                                else -> ScreenshotCaptureResult.Error("Screenshot capture failed with code $errorCode", errorCode)
                            }
                            continuation.resume(result)
                        }
                    }
                )
            } catch (e: Throwable) {
                if (continuation.isActive) {
                    continuation.resume(
                        ScreenshotCaptureResult.Error(e.message ?: "Window screenshot capture exception")
                    )
                }
            }
        }

    private fun detectSensitiveFields(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var inspected = 0
        while (queue.isNotEmpty() && inspected < 150) {
            val node = queue.removeFirst()
            inspected++
            if (node.isPassword) {
                return true
            }
            val className = node.className?.toString()?.lowercase() ?: ""
            if (className.contains("password") || className.contains("pinview")) {
                return true
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Throwable) { null }
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    companion object {
        @Volatile
        var instance: ReadMeAccessibilityService? = null
            internal set

        private val _activePackageFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val activePackageFlow: kotlinx.coroutines.flow.StateFlow<String?> = _activePackageFlow

        val isConnected: Boolean
            get() = instance != null

        fun isServiceEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${ReadMeAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                    componentName.equals("${context.packageName}/.accessibility.ReadMeAccessibilityService", ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }
    }
}
