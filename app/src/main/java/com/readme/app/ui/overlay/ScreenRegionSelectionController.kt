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
import android.provider.Settings
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

        if (!android.provider.Settings.canDrawOverlays(context)) {
            onCancelled()
            return
        }

        val wm = windowManager ?: run {
            onCancelled()
            return
        }

        val dm = context.resources.displayMetrics
        var screenW = dm.widthPixels.coerceAtLeast(720)
        var screenH = dm.heightPixels.coerceAtLeast(1280)
        try {
            val realDm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(realDm)
            if (realDm.widthPixels > 0 && realDm.heightPixels > 0) {
                screenW = realDm.widthPixels
                screenH = realDm.heightPixels
            }
        } catch (_: Throwable) {
            // Keep resources.displayMetrics
        }

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
                strokeWidth = 5f
                isAntiAlias = true
            }
            private val fillPaint = Paint().apply {
                color = Color.parseColor("#1A00B4D8") // Subtle translucent teal fill
                style = Paint.Style.FILL
            }
            private val outerHandlePaint = Paint().apply {
                color = Color.parseColor("#00B4D8")
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            private val innerHandlePaint = Paint().apply {
                color = Color.WHITE
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

                // Draw prominent, comfortable corner handles
                val outerRadius = 18f
                val innerRadius = 7f
                val corners = listOf(
                    rf.left to rf.top,
                    rf.right to rf.top,
                    rf.left to rf.bottom,
                    rf.right to rf.bottom
                )
                for ((cx, cy) in corners) {
                    canvas.drawCircle(cx, cy, outerRadius, outerHandlePaint)
                    canvas.drawCircle(cx, cy, innerRadius, innerHandlePaint)
                }
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
            text = "Select the area to read"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(32, 16, 32, 16)
            setBackgroundColor(Color.parseColor("#D91E1E1E"))
            gravity = Gravity.CENTER
            contentDescription = "Select the area to read"
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
            minHeight = (48 * dm.density).toInt()
            contentDescription = "Cancel selection"
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
            minHeight = (48 * dm.density).toInt()
            contentDescription = "Confirm selection and read"
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

        // Touch handling: Drag / Resize / Free-drag in ANY direction
        var touchMode = 0 // 0 = none, 1 = drag, 2 = resize TL, 3 = TR, 4 = BL, 5 = BR, 6 = free-draw
        var initialDownX = 0f
        var initialDownY = 0f
        var lastTouchX = 0f
        var lastTouchY = 0f
        val handleTouchSlop = 90f // Comfortable ~30dp touch target on physical phones
        val freeDragThreshold = 16f

        canvasView.setOnTouchListener { _, event ->
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialDownX = x
                    initialDownY = y
                    lastTouchX = x
                    lastTouchY = y

                    // Determine if touching a corner handle
                    val dlTopLeft = Math.hypot((x - currentRect.left).toDouble(), (y - currentRect.top).toDouble())
                    val dlTopRight = Math.hypot((x - currentRect.right).toDouble(), (y - currentRect.top).toDouble())
                    val dlBottomLeft = Math.hypot((x - currentRect.left).toDouble(), (y - currentRect.bottom).toDouble())
                    val dlBottomRight = Math.hypot((x - currentRect.right).toDouble(), (y - currentRect.bottom).toDouble())

                    touchMode = when {
                        dlTopLeft < handleTouchSlop -> 2
                        dlTopRight < handleTouchSlop -> 3
                        dlBottomLeft < handleTouchSlop -> 4
                        dlBottomRight < handleTouchSlop -> 5
                        currentRect.contains(x.toInt(), y.toInt()) -> 1
                        else -> 0 // Might become free-draw or tap-to-move depending on movement
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (x - lastTouchX).toInt()
                    val dy = (y - lastTouchY).toInt()
                    lastTouchX = x
                    lastTouchY = y

                    val minSize = ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX

                    if (touchMode == 0) {
                        val distFromStart = Math.hypot((x - initialDownX).toDouble(), (y - initialDownY).toDouble())
                        if (distFromStart > freeDragThreshold) {
                            touchMode = 6 // Switch to free drag selection in any direction
                        }
                    }

                    when (touchMode) {
                        1 -> {
                            // Drag existing box
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
                        6 -> {
                            // Free drag selection in ANY direction (TL to BR, BR to TL, TR to BL, BL to TR)
                            val left = minOf(initialDownX, x).toInt().coerceIn(effectiveBounds.left, effectiveBounds.right)
                            val right = maxOf(initialDownX, x).toInt().coerceIn(effectiveBounds.left, effectiveBounds.right)
                            val top = minOf(initialDownY, y).toInt().coerceIn(effectiveBounds.top, effectiveBounds.bottom)
                            val bottom = maxOf(initialDownY, y).toInt().coerceIn(effectiveBounds.top, effectiveBounds.bottom)
                            currentRect.set(left, top, right, bottom)
                        }
                    }
                    canvasView.invalidate()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (touchMode == 0) {
                        // Tapped outside without dragging: center current selection around touch
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
                    } else if (touchMode == 6) {
                        // Ensure minimum size and valid normalization
                        val clamped = ScreenGeometryMapper.clampRegion(currentRect, effectiveBounds)
                        if (clamped != null) {
                            currentRect.set(clamped)
                        } else {
                            // Fallback to min box centered on touch
                            val minSize = ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX
                            val cX = initialDownX.toInt()
                            val cY = initialDownY.toInt()
                            val fallback = Rect(
                                (cX - minSize / 2).coerceIn(effectiveBounds.left, effectiveBounds.right - minSize),
                                (cY - minSize / 2).coerceIn(effectiveBounds.top, effectiveBounds.bottom - minSize),
                                (cX + minSize / 2).coerceIn(effectiveBounds.left + minSize, effectiveBounds.right),
                                (cY + minSize / 2).coerceIn(effectiveBounds.top + minSize, effectiveBounds.bottom)
                            )
                            currentRect.set(fallback)
                        }
                        canvasView.invalidate()
                    }
                    touchMode = 0
                    true
                }
                else -> false
            }
        }

        // Enable Back button press dismissal
        root.isFocusableInTouchMode = true
        root.requestFocus()
        root.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && keyEvent.action == android.view.KeyEvent.ACTION_UP) {
                dismiss()
                onCancelled()
                true
            } else {
                false
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
        } catch (e: Throwable) {
            android.util.Log.e("ReadMeCrash", "Failed to add selection overlay window", e)
            isAdded = false
            overlayView = null
            onCancelled()
        }
    }

    fun dismiss() {
        if (isAdded) {
            try {
                overlayView?.let { windowManager?.removeView(it) }
            } catch (_: Throwable) {
            } finally {
                overlayView = null
                isAdded = false
            }
        }
    }
}
