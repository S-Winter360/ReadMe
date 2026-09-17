package com.readme.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState

class SystemFloatingBubbleController(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var bubbleView: SystemFloatingBubbleView? = null
    private var closeZoneController: CloseZoneOverlayController? = null
    private var isAdded = false

    val isShowing: Boolean
        get() = isAdded

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        closeZoneController = CloseZoneOverlayController(context)
    }

    fun show(
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
        if (!Settings.canDrawOverlays(context)) return

        if (bubbleView == null) {
            bubbleView = SystemFloatingBubbleView(context).apply {
                setContent(
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
                start()
            }
        } else {
            updateState(
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

        if (!isAdded) {
            try {
                val params = createLayoutParams()
                windowManager?.addView(bubbleView, params)
                isAdded = true
            } catch (e: Exception) {
                isAdded = false
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
        if (!Settings.canDrawOverlays(context)) {
            hide()
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

        // Clamp bubble within screen bounds
        bubbleView?.let { view ->
            val params = view.layoutParams as? WindowManager.LayoutParams
            if (params != null) {
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
                if (params.x != clampedX || params.y != clampedY) {
                    params.x = clampedX
                    params.y = clampedY
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }

        bubbleView?.setContent(
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
    }

    private fun SystemFloatingBubbleView.setContent(
        sessionState: ActiveReadingSessionState,
        activeDocumentState: ActiveDocumentState,
        crossAppReadingEnabled: Boolean,
        canAcquireText: Boolean,
        onToggleReading: () -> Unit,
        onPauseReading: () -> Unit,
        onResumeReading: () -> Unit,
        onReselectArea: () -> Unit,
        onStopReading: () -> Unit,
        onCloseBubble: () -> Unit,
        onAcquireMode: (CrossAppAcquisitionMode) -> Unit
    ) {
        composeView.setContent {
            SystemFloatingBubbleContent(
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
                onDrag = { dx, dy -> handleDrag(this, dx, dy) },
                onDragEnd = { handleDragEnd(this, onCloseBubble) },
                onDragCancel = { handleDragCancel(this) }
            )
        }
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
        } catch (e: Exception) {
            // Ignore during transitions
        }
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
            } catch (e: Exception) {
                // Ignore
            }
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
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun hide() {
        closeZoneController?.hide()
        if (isAdded) {
            try {
                bubbleView?.stop()
                windowManager?.removeView(bubbleView)
            } catch (e: Exception) {
                // Safely handle already removed
            } finally {
                isAdded = false
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
