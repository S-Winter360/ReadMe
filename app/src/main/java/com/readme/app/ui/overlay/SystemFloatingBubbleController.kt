package com.readme.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.readme.app.BuildConfig
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.diagnostics.ReadMeCrashLogger
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState

class SystemFloatingBubbleController(private val context: Context) {

    val controllerInstanceId: Long = ReadMeCrashLogger.controllerInstanceCounter.incrementAndGet()

    private var windowManager: WindowManager? = null
    private var bubbleView: SystemFloatingBubbleView? = null
    private var closeZoneController: CloseZoneOverlayController? = null
    private var isAdded = false

    val isShowing: Boolean
        get() = isAdded

    init {
        ReadMeCrashLogger.currentControllerInstanceId = controllerInstanceId
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        closeZoneController = CloseZoneOverlayController(context)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "SystemFloatingBubbleController initialized (ID: $controllerInstanceId)")
        }
    }

    fun show(
        sessionState: ActiveReadingSessionState,
        activeDocumentState: ActiveDocumentState,
        crossAppReadingEnabled: Boolean = true,
        canAcquireText: Boolean = false,
        isAutoAdvanceEnabled: Boolean = false,
        onToggleReading: () -> Unit = {},
        onPauseReading: () -> Unit = {},
        onResumeReading: () -> Unit = {},
        onReselectArea: () -> Unit = {},
        onStopReading: () -> Unit = {},
        onCloseBubble: () -> Unit = {},
        onAcquireMode: (CrossAppAcquisitionMode) -> Unit = {}
    ) {
        val hasOverlay = Settings.canDrawOverlays(context)
        ReadMeCrashLogger.overlayPermissionGranted = hasOverlay
        if (!hasOverlay) {
            ReadMeCrashLogger.bubbleControllerState = "PermissionUnavailable"
            return
        }

        val wm = windowManager ?: run {
            ReadMeCrashLogger.bubbleControllerState = "OverlayUnavailable"
            return
        }

        if (bubbleView == null) {
            bubbleView = SystemFloatingBubbleView(context)
        }

        val view = bubbleView ?: return

        val payload = BubbleViewPayload(
            sessionState = sessionState,
            activeDocumentState = activeDocumentState,
            crossAppReadingEnabled = crossAppReadingEnabled,
            canAcquireText = canAcquireText,
            isAutoAdvanceEnabled = isAutoAdvanceEnabled,
            onToggleReading = onToggleReading,
            onPauseReading = onPauseReading,
            onResumeReading = onResumeReading,
            onReselectArea = onReselectArea,
            onStopReading = onStopReading,
            onCloseBubble = onCloseBubble,
            onAcquireMode = onAcquireMode,
            onDragStart = { handleDragStart() },
            onDrag = { dx, dy -> handleDrag(view, dx, dy) },
            onDragEnd = { handleDragEnd(view, onCloseBubble) },
            onDragCancel = { handleDragCancel(view) }
        )
        view.updatePayload(payload)

        if (!isAdded) {
            try {
                val params = createLayoutParams()
                wm.addView(view, params)
                isAdded = true
                view.start()
                ReadMeCrashLogger.bubbleAddCount.incrementAndGet()
                ReadMeCrashLogger.isBubbleViewAttached = true
                ReadMeCrashLogger.bubbleControllerState = "Visible"
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Bubble attached (Controller: $controllerInstanceId, AddCount: ${ReadMeCrashLogger.bubbleAddCount.get()})")
                }
            } catch (e: WindowManager.BadTokenException) {
                Log.e(TAG, "BadTokenException adding bubble overlay", e)
                isAdded = false
                ReadMeCrashLogger.bubbleControllerState = "OverlayUnavailable"
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException adding bubble overlay", e)
                isAdded = false
                ReadMeCrashLogger.bubbleControllerState = "PermissionUnavailable"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add bubbleView to WindowManager", e)
                isAdded = false
                ReadMeCrashLogger.bubbleControllerState = "OverlayUnavailable"
            }
        } else {
            // Already added: ensure position is clamped
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
                val dm = DisplayMetrics()
                wm.defaultDisplay?.getMetrics(dm)
                val (clampedX, clampedY) = clampPosition(
                    params.x,
                    params.y,
                    view.width,
                    view.height,
                    dm.widthPixels,
                    dm.heightPixels
                )
                if (params.x != clampedX || params.y != clampedY) {
                    params.x = clampedX
                    params.y = clampedY
                    try {
                        wm.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun updateState(
        sessionState: ActiveReadingSessionState,
        activeDocumentState: ActiveDocumentState,
        crossAppReadingEnabled: Boolean = true,
        canAcquireText: Boolean = false,
        onToggleReading: () -> Unit = {},
        onPauseReading: () -> Unit = {},
        onResumeReading: () -> Unit = {},
        onReselectArea: () -> Unit = {},
        onStopReading: () -> Unit = {},
        onCloseBubble: () -> Unit = {},
        onAcquireMode: (CrossAppAcquisitionMode) -> Unit = {}
    ) {
        val hasOverlay = Settings.canDrawOverlays(context)
        ReadMeCrashLogger.overlayPermissionGranted = hasOverlay
        if (!hasOverlay) {
            hide()
            ReadMeCrashLogger.bubbleControllerState = "PermissionUnavailable"
            return
        }

        if (!isAdded) {
            show(
                sessionState = sessionState,
                activeDocumentState = activeDocumentState,
                crossAppReadingEnabled = crossAppReadingEnabled,
                canAcquireText = canAcquireText,
                onToggleReading = onToggleReading,
                onPauseReading = onPauseReading,
                onResumeReading = onResumeReading,
                onReselectArea = onReselectArea,
                onStopReading = onStopReading,
                onCloseBubble = onCloseBubble,
                onAcquireMode = onAcquireMode
            )
            return
        }

        val view = bubbleView ?: return
        val wm = windowManager

        // Clamp bubble within screen bounds
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (params != null && wm != null) {
            val displayMetrics = DisplayMetrics()
            wm.defaultDisplay?.getMetrics(displayMetrics)
            val (clampedX, clampedY) = clampPosition(
                params.x,
                params.y,
                view.width,
                view.height,
                displayMetrics.widthPixels,
                displayMetrics.heightPixels
            )
            if (params.x != clampedX || params.y != clampedY) {
                params.x = clampedX
                params.y = clampedY
                try {
                    wm.updateViewLayout(view, params)
                } catch (_: Exception) {}
            }
        }

        val payload = BubbleViewPayload(
            sessionState = sessionState,
            activeDocumentState = activeDocumentState,
            crossAppReadingEnabled = crossAppReadingEnabled,
            canAcquireText = canAcquireText,
            onToggleReading = onToggleReading,
            onPauseReading = onPauseReading,
            onResumeReading = onResumeReading,
            onReselectArea = onReselectArea,
            onStopReading = onStopReading,
            onCloseBubble = onCloseBubble,
            onAcquireMode = onAcquireMode,
            onDragStart = { handleDragStart() },
            onDrag = { dx, dy -> handleDrag(view, dx, dy) },
            onDragEnd = { handleDragEnd(view, onCloseBubble) },
            onDragCancel = { handleDragCancel(view) }
        )
        view.updatePayload(payload)
    }

    private fun handleDragStart() {
        closeZoneController?.show()
    }

    private fun handleDrag(view: View, dx: Float, dy: Float) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)

        params.x = (params.x + dx.toInt())
        params.y = (params.y + dy.toInt())

        val thresholdPx = (140 * displayMetrics.density).toInt()
        val inCloseZone = isPositionInCloseZone(
            bubbleY = params.y,
            bubbleHeight = view.height,
            screenHeight = displayMetrics.heightPixels,
            thresholdPx = thresholdPx
        )
        closeZoneController?.setHovered(inCloseZone)

        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun handleDragEnd(view: View, onCloseBubble: () -> Unit) {
        val params = view.layoutParams as? WindowManager.LayoutParams
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        val thresholdPx = (140 * displayMetrics.density).toInt()

        val releasedInCloseZone = if (params != null) {
            isPositionInCloseZone(
                bubbleY = params.y,
                bubbleHeight = view.height,
                screenHeight = displayMetrics.heightPixels,
                thresholdPx = thresholdPx
            )
        } else false

        closeZoneController?.hide()

        if (releasedInCloseZone) {
            onCloseBubble()
        } else if (params != null) {
            val (clampedX, clampedY) = clampPosition(
                params.x,
                params.y,
                view.width,
                view.height,
                displayMetrics.widthPixels,
                displayMetrics.heightPixels
            )
            params.x = clampedX
            params.y = clampedY
            try {
                windowManager?.updateViewLayout(view, params)
            } catch (_: Exception) {}
        }
    }

    private fun handleDragCancel(view: View) {
        closeZoneController?.hide()
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        val (clampedX, clampedY) = clampPosition(
            params.x,
            params.y,
            view.width,
            view.height,
            displayMetrics.widthPixels,
            displayMetrics.heightPixels
        )
        params.x = clampedX
        params.y = clampedY
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    fun hide() {
        closeZoneController?.hide()
        if (isAdded) {
            val view = bubbleView
            try {
                view?.stop()
                if (view != null) {
                    windowManager?.removeView(view)
                    ReadMeCrashLogger.bubbleRemoveCount.incrementAndGet()
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Bubble removed (Controller: $controllerInstanceId, RemoveCount: ${ReadMeCrashLogger.bubbleRemoveCount.get()})")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception while removing bubbleView: ${e.message}")
            } finally {
                isAdded = false
                ReadMeCrashLogger.isBubbleViewAttached = false
                ReadMeCrashLogger.bubbleControllerState = "Hidden"
                view?.destroy()
                bubbleView = null
            }
        }
    }

    fun destroy() {
        hide()
        closeZoneController?.destroy()
        closeZoneController = null
        bubbleView?.destroy()
        bubbleView = null
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }
    }

    companion object {
        private const val TAG = "SystemFloatingBubble"

        fun shouldDisplaySystemBubble(
            isSystemBubbleEnabled: Boolean,
            hasOverlayPermission: Boolean,
            isAppForeground: Boolean,
            hasActiveDocument: Boolean
        ): Boolean {
            return isSystemBubbleEnabled && hasOverlayPermission && !isAppForeground && hasActiveDocument
        }

        @VisibleForTesting
        fun isPositionInCloseZone(
            bubbleY: Int,
            bubbleHeight: Int,
            screenHeight: Int,
            thresholdPx: Int
        ): Boolean {
            val effectiveH = if (bubbleHeight > 0) bubbleHeight else 160
            return (bubbleY + effectiveH) >= (screenHeight - thresholdPx)
        }

        @VisibleForTesting
        fun clampPosition(
            x: Int,
            y: Int,
            viewWidth: Int,
            viewHeight: Int,
            screenWidth: Int,
            screenHeight: Int
        ): Pair<Int, Int> {
            val effectiveW = if (viewWidth > 0) viewWidth else 180
            val effectiveH = if (viewHeight > 0) viewHeight else 180
            val clampedX = x.coerceIn(0, (screenWidth - effectiveW).coerceAtLeast(0))
            val clampedY = y.coerceIn(0, (screenHeight - effectiveH).coerceAtLeast(0))
            return Pair(clampedX, clampedY)
        }
    }
}
