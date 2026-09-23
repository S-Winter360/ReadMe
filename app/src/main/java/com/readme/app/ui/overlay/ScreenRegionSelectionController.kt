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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.readme.app.R
import com.readme.app.accessibility.ScreenGeometryMapper
import com.readme.app.diagnostics.ReadMeCrashLogger

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

    private val themedContext: Context = ContextThemeWrapper(context, R.style.Theme_ReadMe)

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

        if (!Settings.canDrawOverlays(context)) {
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

        val root = FrameLayout(themedContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Custom Canvas View for rendering scrim cutout and selection border
        val canvasView = object : View(themedContext) {
            private val scrimPaint = Paint().apply {
                color = Color.parseColor("#99000000") // 60% black scrim
            }
            private val clearPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
            private val borderPaint = Paint().apply {
                color = Color.parseColor("#00B4D8") // ReadMe Cyan
                style = Paint.Style.STROKE
                strokeWidth = (2.5f * dm.density).coerceAtLeast(4f)
                isAntiAlias = true
            }
            private val cornerPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                try {
                    val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

                    // Draw dark scrim over entire display
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

                    // Cut out transparent hole for selected region
                    canvas.drawRect(
                        currentRect.left.toFloat(),
                        currentRect.top.toFloat(),
                        currentRect.right.toFloat(),
                        currentRect.bottom.toFloat(),
                        clearPaint
                    )

                    canvas.restoreToCount(saveCount)

                    // Draw cyan border around selection
                    canvas.drawRect(
                        currentRect.left.toFloat(),
                        currentRect.top.toFloat(),
                        currentRect.right.toFloat(),
                        currentRect.bottom.toFloat(),
                        borderPaint
                    )

                    // Draw corner handles
                    val handleSize = (14f * dm.density).coerceAtLeast(24f)
                    val halfH = handleSize / 2f

                    // Top-Left
                    canvas.drawRect(currentRect.left - halfH, currentRect.top - halfH, currentRect.left + halfH, currentRect.top + halfH, cornerPaint)
                    // Top-Right
                    canvas.drawRect(currentRect.right - halfH, currentRect.top - halfH, currentRect.right + halfH, currentRect.top + halfH, cornerPaint)
                    // Bottom-Left
                    canvas.drawRect(currentRect.left - halfH, currentRect.bottom - halfH, currentRect.left + halfH, currentRect.bottom + halfH, cornerPaint)
                    // Bottom-Right
                    canvas.drawRect(currentRect.right - halfH, currentRect.bottom - halfH, currentRect.right + halfH, currentRect.bottom + halfH, cornerPaint)
                } catch (_: Throwable) {
                    // Prevent any drawing exception from crashing overlay
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
        val header = TextView(themedContext).apply {
            text = "Select the area to read"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(32, 16, 32, 16)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#D91E1E1E"))
                cornerRadius = 16f * dm.density
            }
            background = bg
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
        val buttonBar = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            val barBg = GradientDrawable().apply {
                setColor(Color.parseColor("#F21E1E1E"))
                cornerRadius = 24f * dm.density
            }
            background = barBg
        }

        val cancelButton = TextView(themedContext).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#333333"))
                cornerRadius = 16f * dm.density
            }
            background = btnBg
            minHeight = (48 * dm.density).toInt()
            contentDescription = "Cancel selection"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                onCancelled()
            }
        }

        val spacer = View(themedContext)

        val confirmButton = TextView(themedContext).apply {
            text = "Read"
            setTextColor(Color.parseColor("#121212"))
            textSize = 14f
            gravity = Gravity.CENTER
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#00B4D8"))
                cornerRadius = 16f * dm.density
            }
            background = btnBg
            minHeight = (48 * dm.density).toInt()
            contentDescription = "Confirm selection and read"
            isClickable = true
            isFocusable = true
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

        val handleRadius = (32f * dm.density).coerceAtLeast(48f)

        canvasView.setOnTouchListener { _, event ->
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialDownX = event.x
                        initialDownY = event.y
                        lastTouchX = event.x
                        lastTouchY = event.y

                        // Test corner hits first
                        val dTL = Math.hypot((event.x - currentRect.left).toDouble(), (event.y - currentRect.top).toDouble())
                        val dTR = Math.hypot((event.x - currentRect.right).toDouble(), (event.y - currentRect.top).toDouble())
                        val dBL = Math.hypot((event.x - currentRect.left).toDouble(), (event.y - currentRect.bottom).toDouble())
                        val dBR = Math.hypot((event.x - currentRect.right).toDouble(), (event.y - currentRect.bottom).toDouble())

                        touchMode = when {
                            dTL <= handleRadius -> 2
                            dTR <= handleRadius -> 3
                            dBL <= handleRadius -> 4
                            dBR <= handleRadius -> 5
                            currentRect.contains(event.x.toInt(), event.y.toInt()) -> 1 // drag box
                            else -> 6 // outside: drag out a brand new selection box
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.x - lastTouchX).toInt()
                        val dy = (event.y - lastTouchY).toInt()

                        when (touchMode) {
                            1 -> {
                                // Translate whole rect
                                val newL = (currentRect.left + dx).coerceIn(effectiveBounds.left, effectiveBounds.right - currentRect.width())
                                val newT = (currentRect.top + dy).coerceIn(effectiveBounds.top, effectiveBounds.bottom - currentRect.height())
                                currentRect.offsetTo(newL, newT)
                            }
                            2 -> {
                                // Resize Top-Left
                                currentRect.left = (currentRect.left + dx).coerceIn(effectiveBounds.left, currentRect.right - ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
                                currentRect.top = (currentRect.top + dy).coerceIn(effectiveBounds.top, currentRect.bottom - ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
                            }
                            3 -> {
                                // Resize Top-Right
                                currentRect.right = (currentRect.right + dx).coerceIn(currentRect.left + ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX, effectiveBounds.right)
                                currentRect.top = (currentRect.top + dy).coerceIn(effectiveBounds.top, currentRect.bottom - ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
                            }
                            4 -> {
                                // Resize Bottom-Left
                                currentRect.left = (currentRect.left + dx).coerceIn(effectiveBounds.left, currentRect.right - ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
                                currentRect.bottom = (currentRect.bottom + dy).coerceIn(currentRect.top + ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX, effectiveBounds.bottom)
                            }
                            5 -> {
                                // Resize Bottom-Right
                                currentRect.right = (currentRect.right + dx).coerceIn(currentRect.left + ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX, effectiveBounds.right)
                                currentRect.bottom = (currentRect.bottom + dy).coerceIn(currentRect.top + ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX, effectiveBounds.bottom)
                            }
                            6 -> {
                                // Free-drag rectangle from initial touch down point to current touch
                                val rawL = minOf(initialDownX, event.x).toInt().coerceIn(effectiveBounds.left, effectiveBounds.right)
                                val rawR = maxOf(initialDownX, event.x).toInt().coerceIn(effectiveBounds.left, effectiveBounds.right)
                                val rawT = minOf(initialDownY, event.y).toInt().coerceIn(effectiveBounds.top, effectiveBounds.bottom)
                                val rawB = maxOf(initialDownY, event.y).toInt().coerceIn(effectiveBounds.top, effectiveBounds.bottom)

                                if ((rawR - rawL) >= ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX &&
                                    (rawB - rawT) >= ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX) {
                                    currentRect.set(rawL, rawT, rawR, rawB)
                                }
                            }
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                        canvasView.invalidate()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touchMode = 0
                        // Normalize rect bounds on release
                        val norm = ScreenGeometryMapper.normalizeRect(currentRect)
                        val clamped = ScreenGeometryMapper.clampRegion(norm, effectiveBounds)
                        if (clamped != null) {
                            currentRect.set(clamped)
                        }
                        canvasView.invalidate()
                        true
                    }
                    else -> false
                }
            } catch (_: Throwable) {
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
            ReadMeCrashLogger.selectionAddCount.incrementAndGet()
            ReadMeCrashLogger.isSelectionAttached = true
        } catch (e: Throwable) {
            android.util.Log.e("ReadMeCrash", "Failed to add selection overlay window", e)
            isAdded = false
            ReadMeCrashLogger.isSelectionAttached = false
            overlayView = null
            onCancelled()
        }
    }

    fun dismiss() {
        if (isAdded) {
            val view = overlayView
            try {
                if (view != null) {
                    windowManager?.removeView(view)
                    ReadMeCrashLogger.selectionRemoveCount.incrementAndGet()
                }
            } catch (_: Throwable) {
            } finally {
                overlayView = null
                isAdded = false
                ReadMeCrashLogger.isSelectionAttached = false
            }
        }
    }
}
