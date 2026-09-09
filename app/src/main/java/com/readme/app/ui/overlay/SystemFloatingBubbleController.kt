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
        onToggleReading: () -> Unit
    ) {
        if (!Settings.canDrawOverlays(context)) return

        val bubbleState = getBubbleState(sessionState, activeDocumentState)
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
                        onToggleReading = onToggleReading
                    )
                }
                setupDragging(this, onToggleReading)
                start()
            }
        } else {
            updateState(sessionState, activeDocumentState, onToggleReading)
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

    fun updateState(
        sessionState: ActiveReadingSessionState,
        activeDocumentState: ActiveDocumentState,
        onToggleReading: () -> Unit
    ) {
        val bubbleState = getBubbleState(sessionState, activeDocumentState)
        if (bubbleState == BubbleState.Hidden) {
            hide()
            return
        }
        
        if (!isAdded) {
            show(sessionState, activeDocumentState, onToggleReading)
            return
        }

        bubbleView?.composeView?.setContent {
            SystemFloatingBubbleContent(
                sessionState = sessionState,
                activeDocumentState = activeDocumentState,
                onToggleReading = onToggleReading
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

    private fun setupDragging(view: View, onToggleReading: () -> Unit) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        val displayMetrics = DisplayMetrics()
        
        view.setOnTouchListener { _, event ->
            val params = view.layoutParams as WindowManager.LayoutParams
            windowManager?.defaultDisplay?.getMetrics(displayMetrics)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rawNewX = initialX + (event.rawX - initialTouchX).toInt()
                    val rawNewY = initialY + (event.rawY - initialTouchY).toInt()
                    
                    val (clampedX, clampedY) = clampPosition(
                        rawNewX,
                        rawNewY,
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
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    if (diffX < 10 && diffY < 10) {
                        onToggleReading()
                    }
                    true
                }
                else -> false
            }
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
