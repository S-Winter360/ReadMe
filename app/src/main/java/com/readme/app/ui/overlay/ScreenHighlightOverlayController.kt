package com.readme.app.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Overlay controller that renders a soft teal glowing highlight over the currently read
 * sentence on the screen.
 *
 * Characteristics:
 * - Completely non-interactive (FLAG_NOT_TOUCHABLE, FLAG_NOT_FOCUSABLE)
 * - Transparent canvas outside the highlighted sentence rectangles
 * - Supports multi-line sentence bounding boxes with clean line merging
 * - Soft animated breathing/pulse effect
 * - Smooth cross-fade transition between sentences
 * - Immediate clean removal on speech stop, pause, document switch, app switch, or error
 */
class ScreenHighlightOverlayController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var highlightView: View? = null
    private var isAdded = false

    private var currentRects: List<RectF> = emptyList()
    private var previousRects: List<RectF> = emptyList()

    private var pulseAlpha: Float = 0.20f
    private var pulseAnimator: ValueAnimator? = null

    private var transitionProgress: Float = 1.0f
    private var transitionAnimator: ValueAnimator? = null

    init {
        pulseAnimator = ValueAnimator.ofFloat(0.16f, 0.24f).apply {
            duration = 1600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseAlpha = animator.animatedValue as Float
                highlightView?.invalidate()
            }
        }
    }

    fun isShowing(): Boolean = isAdded && currentRects.isNotEmpty()

    /**
     * Updates the active highlight with the provided bounding rectangles.
     * Merges adjacent segments on the same line and smoothly cross-fades from the previous sentence.
     */
    fun showHighlight(rectangles: List<RectF>) {
        val validRects = combineAdjacentLineRectangles(rectangles.filter { !it.isEmpty && it.width() > 0 && it.height() > 0 })
        if (validRects.isEmpty()) {
            clearHighlight()
            return
        }

        if (currentRects.isNotEmpty() && currentRects != validRects) {
            // Smoothly cross-fade from previous sentence to new sentence
            previousRects = currentRects
            currentRects = validRects
            transitionAnimator?.cancel()
            transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    transitionProgress = anim.animatedValue as Float
                    highlightView?.invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        previousRects = emptyList()
                        transitionProgress = 1.0f
                        highlightView?.invalidate()
                    }
                })
            }
            transitionAnimator?.start()
        } else {
            currentRects = validRects
            previousRects = emptyList()
            transitionProgress = 1.0f
        }

        if (!isAdded) {
            attachOverlay()
        }

        if (pulseAnimator?.isRunning != true) {
            pulseAnimator?.start()
        }
        highlightView?.invalidate()
    }

    /**
     * Immediately clears and detaches the highlight overlay.
     */
    fun clearHighlight() {
        currentRects = emptyList()
        previousRects = emptyList()
        transitionProgress = 1.0f
        transitionAnimator?.cancel()
        pulseAnimator?.cancel()
        if (isAdded) {
            try {
                highlightView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            } finally {
                isAdded = false
                highlightView = null
            }
        }
    }

    private fun attachOverlay() {
        if (!android.provider.Settings.canDrawOverlays(context)) {
            return
        }
        val wm = windowManager ?: return

        val view = object : View(context) {
            private val fillPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            private val strokePaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            private val glowPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }

            override fun onConfigurationChanged(newConfig: Configuration?) {
                super.onConfigurationChanged(newConfig)
                // When screen rotates, existing highlight coordinates are stale; clear safely
                clearHighlight()
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                if (currentRects.isEmpty() && previousRects.isEmpty()) return

                // Draw fading-out previous sentence if cross-fading
                if (previousRects.isNotEmpty() && transitionProgress < 1.0f) {
                    val prevAlphaMul = (1.0f - transitionProgress).coerceIn(0f, 1f)
                    drawRectangles(canvas, previousRects, prevAlphaMul)
                }

                // Draw fading-in / active sentence
                if (currentRects.isNotEmpty()) {
                    val currAlphaMul = if (previousRects.isNotEmpty()) transitionProgress else 1.0f
                    drawRectangles(canvas, currentRects, currAlphaMul)
                }
            }

            private fun drawRectangles(canvas: Canvas, rects: List<RectF>, alphaMultiplier: Float) {
                val loc = IntArray(2)
                try {
                    getLocationOnScreen(loc)
                } catch (_: Throwable) {}
                val viewX = loc[0].toFloat()
                val viewY = loc[1].toFloat()

                val fillAlpha = (pulseAlpha * alphaMultiplier * 255).toInt().coerceIn(0, 255)
                val strokeAlpha = (0.42f * alphaMultiplier * 255).toInt().coerceIn(0, 255)
                val glowAlpha = (0.16f * pulseAlpha * alphaMultiplier * 255).toInt().coerceIn(0, 255)

                // Deep translucent teal/mint (#12A096 / #14B8A6)
                fillPaint.color = Color.argb(fillAlpha, 18, 160, 150)
                strokePaint.color = Color.argb(strokeAlpha, 20, 184, 166)
                glowPaint.color = Color.argb(glowAlpha, 45, 212, 191)

                val cornerRadius = 6f
                for (rect in rects) {
                    val localLeft = rect.left - viewX
                    val localTop = rect.top - viewY
                    val localRight = rect.right - viewX
                    val localBottom = rect.bottom - viewY

                    val padded = RectF(
                        localLeft - 2f,
                        localTop - 1f,
                        localRight + 2f,
                        localBottom + 1f
                    )
                    // Draw outer soft glow halo
                    canvas.drawRoundRect(padded, cornerRadius, cornerRadius, glowPaint)
                    // Draw translucent fill
                    canvas.drawRoundRect(padded, cornerRadius, cornerRadius, fillPaint)
                    // Draw delicate subtle border
                    canvas.drawRoundRect(padded, cornerRadius, cornerRadius, strokePaint)
                }
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }

        try {
            wm.addView(view, params)
            highlightView = view
            isAdded = true
        } catch (e: Exception) {
            isAdded = false
            highlightView = null
        }
    }

    /**
     * Merges adjacent rectangles that lie on the same visual horizontal line.
     * Prevents overlapping translucent seams when OCR yields word fragments or multiple blocks.
     */
    private fun combineAdjacentLineRectangles(rects: List<RectF>): List<RectF> {
        if (rects.size <= 1) return rects

        val sorted = rects.sortedWith(compareBy({ it.top }, { it.left }))
        val merged = mutableListOf<RectF>()

        for (rect in sorted) {
            if (merged.isEmpty()) {
                merged.add(RectF(rect))
                continue
            }

            val last = merged.last()
            val verticalOverlap = minOf(last.bottom, rect.bottom) - maxOf(last.top, rect.top)
            val minHeight = minOf(last.height(), rect.height()).coerceAtLeast(1f)
            val isSameLine = verticalOverlap > (minHeight * 0.5f)
            val horizontalGap = rect.left - last.right

            if (isSameLine && horizontalGap <= 24f && horizontalGap >= -16f) {
                // Merge into single continuous line box
                last.left = minOf(last.left, rect.left)
                last.top = minOf(last.top, rect.top)
                last.right = maxOf(last.right, rect.right)
                last.bottom = maxOf(last.bottom, rect.bottom)
            } else {
                merged.add(RectF(rect))
            }
        }

        return merged
    }

    fun destroy() {
        clearHighlight()
        pulseAnimator = null
        transitionAnimator = null
    }
}
