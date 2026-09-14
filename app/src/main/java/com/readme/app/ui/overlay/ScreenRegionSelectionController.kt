package com.readme.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.readme.app.accessibility.ScreenGeometryMapper

/**
 * Controller that displays a full-screen interactive overlay for selecting a reading region.
 *
 * Provides:
 * - Dimmed background with a clear selection cutout
 * - Draggable and resizable selection box
 * - Minimum size enforcement
 * - Explicit Cancel and Read (Confirm) actions
 * - Safe WindowManager lifecycle
 */
class ScreenRegionSelectionController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    private var overlayView: FrameLayout? = null
    private var isAdded = false

    fun isShowing(): Boolean = isAdded

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        windowBounds: Rect? = null,
        onRegionSelected: (Rect) -> Unit,
        onCancelled: () -> Unit
    ) {
        if (isAdded) {
            dismiss()
        }

        val wm = windowManager ?: run {
            onCancelled()
            return
        }

        val dm = DisplayMetrics()
        wm.defaultDisplay?.getRealMetrics(dm)
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val effectiveBounds = windowBounds?.takeIf { !it.isEmpty }
            ?: Rect(0, 0, screenW, screenH)

        // Initialize selection rectangle centered in effective bounds
        val initW = (effectiveBounds.width() * 0.75f).toInt().coerceAtLeast(ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
        val initH = (effectiveBounds.height() * 0.35f).toInt().coerceAtLeast(ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
        val initL = effectiveBounds.left + (effectiveBounds.width() - initW) / 2
        val initT = effectiveBounds.top + (effectiveBounds.height() - initH) / 2
        val currentRect = Rect(initL, initT, initL + initW, initT + initH)

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Custom Canvas View for rendering scrim cutout and selection border
        val canvasView = object : View(context) {
            private val scrimPaint = Paint().apply {
                color = Color.parseColor("#99000000") // 60% black scrim
            }
            private val clearPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            private val borderPaint = Paint().apply {
                color = Color.parseColor("#00B4D8") // Teal accent
                style = Paint.Style.STROKE
                strokeWidth = 6f
                isAntiAlias = true
            }
            private val fillPaint = Paint().apply {
                color = Color.parseColor("#2200B4D8") // Subtle translucent teal fill
                style = Paint.Style.FILL
            }
            private val cornerPaint = Paint().apply {
                color = Color.parseColor("#00B4D8")
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            init {
                setLayerType(LAYER_TYPE_SOFTWARE, null)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                // Draw dimmed scrim over entire screen
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

                val rf = RectF(currentRect)
                // Cut out selected rectangle
                canvas.drawRoundRect(rf, 12f, 12f, clearPaint)
                // Draw translucent fill and border
                canvas.drawRoundRect(rf, 12f, 12f, fillPaint)
                canvas.drawRoundRect(rf, 12f, 12f, borderPaint)

                // Draw corner handles
                val handleRadius = 14f
                canvas.drawCircle(rf.left, rf.top, handleRadius, cornerPaint)
                canvas.drawCircle(rf.right, rf.top, handleRadius, cornerPaint)
                canvas.drawCircle(rf.left, rf.bottom, handleRadius, cornerPaint)
                canvas.drawCircle(rf.right, rf.bottom, handleRadius, cornerPaint)
            }
        }

        root.addView(
            canvasView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Top Header Chip
        val header = TextView(context).apply {
            text = "Drag or resize to select text to read"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#D91E1E1E"))
            gravity = Gravity.CENTER
        }
        val headerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (screenH * 0.08f).toInt()
        }
        root.addView(header, headerParams)

        // Bottom Action Controls Container
        val buttonBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.parseColor("#F21E1E1E"))
        }

        val cancelButton = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setOnClickListener {
                dismiss()
                onCancelled()
            }
        }

        val spacer = View(context)

        val confirmButton = Button(context).apply {
            text = "Read"
            setTextColor(Color.parseColor("#121212"))
            setBackgroundColor(Color.parseColor("#00B4D8"))
            setOnClickListener {
                val clamped = ScreenGeometryMapper.clampRegion(currentRect, effectiveBounds)
                dismiss()
                if (clamped != null) {
                    onRegionSelected(clamped)
                } else {
                    onCancelled()
                }
            }
        }

        buttonBar.addView(cancelButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttonBar.addView(spacer, LinearLayout.LayoutParams(32, 1))
        buttonBar.addView(confirmButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val buttonBarParams = FrameLayout.LayoutParams(
            (screenW * 0.85f).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (screenH * 0.08f).toInt()
        }
        root.addView(buttonBar, buttonBarParams)

        // Touch handling: Drag / Resize
        var touchMode = 0 // 0 = none, 1 = drag, 2 = resize top-left, 3 = top-right, 4 = bottom-left, 5 = bottom-right
        var lastTouchX = 0f
        var lastTouchY = 0f
        val touchSlop = 60f

        canvasView.setOnTouchListener { _, event ->
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = x
                    lastTouchY = y

                    // Determine if touching a corner handle
                    val dlTopLeft = Math.hypot((x - currentRect.left).toDouble(), (y - currentRect.top).toDouble())
                    val dlTopRight = Math.hypot((x - currentRect.right).toDouble(), (y - currentRect.top).toDouble())
                    val dlBottomLeft = Math.hypot((x - currentRect.left).toDouble(), (y - currentRect.bottom).toDouble())
                    val dlBottomRight = Math.hypot((x - currentRect.right).toDouble(), (y - currentRect.bottom).toDouble())

                    touchMode = when {
                        dlTopLeft < touchSlop -> 2
                        dlTopRight < touchSlop -> 3
                        dlBottomLeft < touchSlop -> 4
                        dlBottomRight < touchSlop -> 5
                        currentRect.contains(x.toInt(), y.toInt()) -> 1
                        else -> {
                            // Touch outside selection: center selection around touch
                            val halfW = currentRect.width() / 2
                            val halfH = currentRect.height() / 2
                            currentRect.set(
                                (x - halfW).toInt(),
                                (y - halfH).toInt(),
                                (x + halfW).toInt(),
                                (y + halfH).toInt()
                            )
                            val clamped = ScreenGeometryMapper.clampRegion(currentRect, effectiveBounds)
                            if (clamped != null) {
                                currentRect.set(clamped)
                            }
                            canvasView.invalidate()
                            1
                        }
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (x - lastTouchX).toInt()
                    val dy = (y - lastTouchY).toInt()
                    lastTouchX = x
                    lastTouchY = y

                    val minSize = ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX

                    when (touchMode) {
                        1 -> {
                            // Drag
                            val newL = currentRect.left + dx
                            val newT = currentRect.top + dy
                            val newR = currentRect.right + dx
                            val newB = currentRect.bottom + dy
                            val temp = Rect(newL, newT, newR, newB)
                            val clamped = ScreenGeometryMapper.clampRegion(temp, effectiveBounds)
                            if (clamped != null) {
                                currentRect.set(clamped)
                            }
                        }
                        2 -> {
                            // Top-Left resize
                            val newL = (currentRect.left + dx).coerceAtMost(currentRect.right - minSize)
                            val newT = (currentRect.top + dy).coerceAtMost(currentRect.bottom - minSize)
                            currentRect.left = newL.coerceAtLeast(effectiveBounds.left)
                            currentRect.top = newT.coerceAtLeast(effectiveBounds.top)
                        }
                        3 -> {
                            // Top-Right resize
                            val newR = (currentRect.right + dx).coerceAtLeast(currentRect.left + minSize)
                            val newT = (currentRect.top + dy).coerceAtMost(currentRect.bottom - minSize)
                            currentRect.right = newR.coerceAtMost(effectiveBounds.right)
                            currentRect.top = newT.coerceAtLeast(effectiveBounds.top)
                        }
                        4 -> {
                            // Bottom-Left resize
                            val newL = (currentRect.left + dx).coerceAtMost(currentRect.right - minSize)
                            val newB = (currentRect.bottom + dy).coerceAtLeast(currentRect.top + minSize)
                            currentRect.left = newL.coerceAtLeast(effectiveBounds.left)
                            currentRect.bottom = newB.coerceAtMost(effectiveBounds.bottom)
                        }
                        5 -> {
                            // Bottom-Right resize
                            val newR = (currentRect.right + dx).coerceAtLeast(currentRect.left + minSize)
                            val newB = (currentRect.bottom + dy).coerceAtLeast(currentRect.top + minSize)
                            currentRect.right = newR.coerceAtMost(effectiveBounds.right)
                            currentRect.bottom = newB.coerceAtMost(effectiveBounds.bottom)
                        }
                    }
                    canvasView.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchMode = 0
                    true
                }
                else -> false
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(root, params)
            overlayView = root
            isAdded = true
        } catch (e: Exception) {
            isAdded = false
            overlayView = null
            onCancelled()
        }
    }

    fun dismiss() {
        if (isAdded) {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Exception) {
            } finally {
                overlayView = null
                isAdded = false
            }
        }
    }
}
