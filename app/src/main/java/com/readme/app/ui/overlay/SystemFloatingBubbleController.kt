package com.readme.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState
import com.readme.app.ui.components.BubbleState
import com.readme.app.ui.components.getBubbleState

class SystemFloatingBubbleController(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var bubbleView: SystemFloatingBubbleView? = null
    private var isAdded = false

    val isShowing: Boolean
        get() = isAdded

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    fun show(
        sessionState: ActiveReadingSessionState,
        activeDocumentState: ActiveDocumentState,
        canAcquireText: Boolean = false,
        onToggleReading: () -> Unit,
        onAcquireMode: (CrossAppAcquisitionMode) -> Unit = {}
    ) {
        if (!Settings.canDrawOverlays(context)) return

        val bubbleState = getSystemBubbleState(sessionState, activeDocumentState, canAcquireText)
        if (bubbleState == BubbleState.Hidden) {
            hide()
            return
        }

        if (bubbleView == null) {
            bubbleView = SystemFloatingBubbleView(context).apply {
                composeView.setContent {
                    SystemFloatingBubbleContent(
                        sessionState = sessionState,
                        activeDocumentState = activeDocumentState,
                        canAcquireText = canAcquireText,
                        onToggleReading = onToggleReading,
                        onAcquireMode = onAcquireMode,
                        onDrag = { dx, dy -> handleDrag(this, dx, dy) }
                    )
                }
                start()
            }
        } else {
            updateState(sessionState, activeDocumentState, canAcquireText, onToggleReading, onAcquireMode)
            return
        }

        if (!isAdded) {
            try {
                val params = createLayoutParams()
                windowManager?.addView(bubbleView, params)
                isAdded = true
            } catch (e: Exception) {
                // Safely handle if already added or invalid window token
                isAdded = false
            }
        }
    }

    private fun handleDrag(view: View, dx: Float, dy: Float) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        
        val newX = params.x + dx.toInt()
        val newY = params.y + dy.toInt()
        
        val (clampedX, clampedY) = clampPosition(
            newX,
            newY,
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
            // Safely ignore update errors if window is transitioning
        }
    }

    fun updateState(
        sessionState: ActiveReadingSessionState,
        activeDocumentState: ActiveDocumentState,
        canAcquireText: Boolean = false,
        onToggleReading: () -> Unit,
        onAcquireMode: (CrossAppAcquisitionMode) -> Unit = {}
    ) {
        val bubbleState = getSystemBubbleState(sessionState, activeDocumentState, canAcquireText)
        if (bubbleState == BubbleState.Hidden) {
            hide()
            return
        }
        
        if (!isAdded) {
            show(sessionState, activeDocumentState, canAcquireText, onToggleReading, onAcquireMode)
            return
        }

        // Ensure bubble is clamped within screen bounds (e.g. after rotation)
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

        bubbleView?.composeView?.setContent {
            SystemFloatingBubbleContent(
                sessionState = sessionState,
                activeDocumentState = activeDocumentState,
                canAcquireText = canAcquireText,
                onToggleReading = onToggleReading,
                onAcquireMode = onAcquireMode,
                onDrag = { dx, dy -> handleDrag(bubbleView!!, dx, dy) }
            )
        }
    }

    fun hide() {
        if (isAdded) {
            try {
                bubbleView?.stop()
                windowManager?.removeView(bubbleView)
            } catch (e: Exception) {
                // Safely handle already removed or invalid context/window token
            } finally {
                isAdded = false
            }
        }
    }

    fun destroy() {
        hide()
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

        fun clampPosition(
            x: Int,
            y: Int,
            viewWidth: Int,
            viewHeight: Int,
            screenWidth: Int,
            screenHeight: Int
        ): Pair<Int, Int> {
            val clampedX = x.coerceIn(0, (screenWidth - viewWidth).coerceAtLeast(0))
            val clampedY = y.coerceIn(0, (screenHeight - viewHeight).coerceAtLeast(0))
            return Pair(clampedX, clampedY)
        }
    }
}
