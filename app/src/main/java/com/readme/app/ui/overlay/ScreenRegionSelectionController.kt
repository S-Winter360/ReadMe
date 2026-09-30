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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
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
 * Deterministic selector state model according to Phase 9AE/9AF.
 */
sealed class SelectorState {
    object Idle : SelectorState()
    object Showing : SelectorState()
    object Selecting : SelectorState()
    object Confirming : SelectorState()
    object Cancelling : SelectorState()
    object Completed : SelectorState()
    object Destroyed : SelectorState()
    data class Failed(val reason: String, val cause: Throwable? = null) : SelectorState()

    val name: String
        get() = when (this) {
            is Idle -> "Idle"
            is Showing -> "Showing"
            is Selecting -> "Selecting"
            is Confirming -> "Confirming"
            is Cancelling -> "Cancelling"
            is Completed -> "Completed"
            is Destroyed -> "Destroyed"
            is Failed -> "Failed($reason)"
        }
}

/**
 * Controller that displays a full-screen interactive overlay for selecting a reading region.
 *
 * Hardened for Phase 9AF:
 * - Deterministic State Machine (Idle, Showing, Selecting, Confirming, Cancelling, Completed, Destroyed, Failed)
 * - Cancel idempotency and exactly-once callback dispatch
 * - Synchronous and immediate WindowManager surface detachment on Cancel to prevent ghost overlays
 * - Complete touch listener detachment and View.GONE visibility on dismissal
 * - Single attached selector globally across instances and per instance (no duplicate addView calls)
 * - WindowManager exception safety (BadTokenException, SecurityException, IllegalStateException, IllegalArgumentException)
 * - Stable display-aware window context
 * - Main thread affinity and safe destruction
 */
class ScreenRegionSelectionController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val windowContext: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } catch (_: Throwable) {
            context
        }
    } else {
        context
    }

    private val themedContext: Context = ContextThemeWrapper(windowContext, R.style.Theme_ReadMe)

    private val windowManager: WindowManager? =
        windowContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)

    private val stateLock = Any()

    @Volatile
    private var currentState: SelectorState = SelectorState.Idle

    @Volatile
    private var overlayView: FrameLayout? = null

    @Volatile
    private var canvasViewRef: View? = null

    @Volatile
    private var activeSelectionRect: Rect = Rect()

    @Volatile
    private var currentEffectiveBounds: Rect = Rect()

    @Volatile
    private var pendingCancelledCallback: (() -> Unit)? = null

    @Volatile
    private var pendingSelectedCallback: ((Rect) -> Unit)? = null

    val isAttached: Boolean
        get() = synchronized(stateLock) {
            (currentState == SelectorState.Showing || currentState == SelectorState.Selecting) && overlayView != null
        }

    fun isShowing(): Boolean = isAttached

    fun getState(): SelectorState = synchronized(stateLock) { currentState }

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        windowBounds: Rect? = null,
        initialSelection: Rect? = null,
        onRegionSelected: (Rect) -> Unit,
        onCancelled: () -> Unit
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post {
                show(windowBounds, initialSelection, onRegionSelected, onCancelled)
            }
            return
        }

        synchronized(stateLock) {
            // Prevent Destroyed -> Showing
            if (currentState == SelectorState.Destroyed) {
                Log.w(TAG, "Cannot show selection overlay: controller is destroyed")
                onCancelled()
                return
            }

            // Register callbacks
            pendingSelectedCallback = onRegionSelected
            pendingCancelledCallback = onCancelled

            // If already attached in this instance, update current selection rather than adding another view
            if (isAttached && overlayView != null) {
                val realBounds = ScreenGeometryMapper.getRealDisplayBounds(context)
                val screenW = realBounds.width().coerceAtLeast(720)
                val screenH = realBounds.height().coerceAtLeast(1280)
                val bounds = windowBounds?.takeIf { !it.isEmpty } ?: Rect(0, 0, screenW, screenH)
                currentEffectiveBounds = bounds

                val newSelection = if (initialSelection != null && !initialSelection.isEmpty) {
                    val norm = ScreenGeometryMapper.normalizeRect(initialSelection)
                    ScreenGeometryMapper.clampRegion(norm, bounds) ?: createDefaultSelection(bounds)
                } else {
                    createDefaultSelection(bounds)
                }
                activeSelectionRect.set(newSelection)
                canvasViewRef?.invalidate()
                Log.d(TAG, "Selection overlay already attached; updated selection region without re-adding view")
                return
            }

            // Enforce at most one selector overlay globally
            activeAttachedInstance?.takeIf { it !== this }?.let { otherController ->
                Log.i(TAG, "Dismissing previous active selector instance to enforce single attached selector")
                otherController.dismiss()
            }

            // Verify overlay permission
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Overlay permission not granted; cannot show region selection")
                currentState = SelectorState.Failed("Overlay permission unavailable")
                ReadMeCrashLogger.selectorState = "Failed: PermissionUnavailable"
                val cb = pendingCancelledCallback
                pendingCancelledCallback = null
                pendingSelectedCallback = null
                cb?.invoke()
                return
            }

            val wm = windowManager ?: run {
                Log.e(TAG, "WindowManager unavailable; cannot show region selection")
                currentState = SelectorState.Failed("WindowManager unavailable")
                ReadMeCrashLogger.selectorState = "Failed: WindowManagerUnavailable"
                val cb = pendingCancelledCallback
                pendingCancelledCallback = null
                pendingSelectedCallback = null
                cb?.invoke()
                return
            }

            currentState = SelectorState.Showing
            ReadMeCrashLogger.selectorState = "Showing"

            val realBounds = ScreenGeometryMapper.getRealDisplayBounds(context)
            val screenW = realBounds.width().coerceAtLeast(720)
            val screenH = realBounds.height().coerceAtLeast(1280)
            val dm = context.resources.displayMetrics

            val effectiveBounds = windowBounds?.takeIf { !it.isEmpty }
                ?: Rect(0, 0, screenW, screenH)
            currentEffectiveBounds = effectiveBounds

            // Initialize selection rectangle
            val currentRect = if (initialSelection != null && !initialSelection.isEmpty) {
                val norm = ScreenGeometryMapper.normalizeRect(initialSelection)
                ScreenGeometryMapper.clampRegion(norm, effectiveBounds) ?: createDefaultSelection(effectiveBounds)
            } else {
                createDefaultSelection(effectiveBounds)
            }
            activeSelectionRect = currentRect

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
            canvasViewRef = canvasView

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
                    performCancel()
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
                    val cb = synchronized(stateLock) {
                        if (currentState != SelectorState.Selecting && currentState != SelectorState.Showing) {
                            null
                        } else {
                            currentState = SelectorState.Confirming
                            ReadMeCrashLogger.selectorState = "Confirming"
                            val callback = pendingSelectedCallback
                            pendingSelectedCallback = null
                            pendingCancelledCallback = null
                            callback
                        }
                    }

                    if (cb != null) {
                        val loc = IntArray(2)
                        try {
                            canvasView.getLocationOnScreen(loc)
                        } catch (_: Throwable) {}
                        val norm = ScreenGeometryMapper.normalizeRect(currentRect)
                        val onScreen = if (loc[0] != 0 || loc[1] != 0) {
                            Rect(
                                norm.left + loc[0],
                                norm.top + loc[1],
                                norm.right + loc[0],
                                norm.bottom + loc[1]
                            )
                        } else {
                            norm
                        }
                        val clamped = ScreenGeometryMapper.clampRegion(onScreen, effectiveBounds) ?: onScreen
                        dismiss()
                        synchronized(stateLock) {
                            currentState = SelectorState.Completed
                            ReadMeCrashLogger.selectorState = "Completed"
                        }
                        cb(clamped)
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
                val state = synchronized(stateLock) { currentState }
                if (state != SelectorState.Selecting && state != SelectorState.Showing) {
                    return@setOnTouchListener false
                }

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
                                    val newL = (currentRect.left + dx).coerceIn(effectiveBounds.left, (effectiveBounds.right - currentRect.width()).coerceAtLeast(effectiveBounds.left))
                                    val newT = (currentRect.top + dy).coerceIn(effectiveBounds.top, (effectiveBounds.bottom - currentRect.height()).coerceAtLeast(effectiveBounds.top))
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
                wm.addView(root, params)
                overlayView = root
                currentState = SelectorState.Selecting
                activeAttachedInstance = this
                ReadMeCrashLogger.selectionAddCount.incrementAndGet()
                ReadMeCrashLogger.isSelectionAttached = true
                ReadMeCrashLogger.selectorState = "Selecting"
                Log.i(TAG, "Screen region selection overlay attached successfully")
            } catch (e: WindowManager.BadTokenException) {
                handleAttachmentFailure("BadTokenException adding selection overlay", e)
            } catch (e: SecurityException) {
                handleAttachmentFailure("SecurityException adding selection overlay", e)
            } catch (e: IllegalStateException) {
                handleAttachmentFailure("IllegalStateException adding selection overlay", e)
            } catch (e: IllegalArgumentException) {
                handleAttachmentFailure("IllegalArgumentException adding selection overlay", e)
            } catch (e: Throwable) {
                handleAttachmentFailure("Unexpected error adding selection overlay: ${e.message}", e)
            }
        }
    }

    private fun performCancel() {
        val cb = synchronized(stateLock) {
            if (currentState != SelectorState.Selecting && currentState != SelectorState.Showing) {
                null
            } else {
                currentState = SelectorState.Cancelling
                ReadMeCrashLogger.selectorState = "Cancelling"
                val callback = pendingCancelledCallback
                pendingCancelledCallback = null
                pendingSelectedCallback = null
                callback
            }
        }

        // Section 9: Strict callback ordering:
        // 1. mark selector as cancelling (done above)
        // 2. remove selector touch surface & detach WindowManager view (in dismiss)
        // 3. clear selector references (in dismiss)
        // 4. update controller state to Idle (in dismiss)
        // 5. notify caller exactly once
        dismiss()

        cb?.invoke()
    }

    private fun handleAttachmentFailure(reason: String, cause: Throwable) {
        Log.e(TAG, "$reason: ${cause.message}", cause)
        overlayView = null
        canvasViewRef = null
        currentState = SelectorState.Failed(reason, cause)
        ReadMeCrashLogger.isSelectionAttached = false
        ReadMeCrashLogger.selectorState = "Failed($reason)"
        val cb = pendingCancelledCallback
        pendingCancelledCallback = null
        pendingSelectedCallback = null
        cb?.invoke()
    }

    private fun createDefaultSelection(bounds: Rect): Rect {
        val initW = (bounds.width() * 0.75f).toInt().coerceAtLeast(ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
        val initH = (bounds.height() * 0.35f).toInt().coerceAtLeast(ScreenGeometryMapper.DEFAULT_MIN_SIZE_PX)
        val initL = bounds.left + (bounds.width() - initW) / 2
        val initT = bounds.top + (bounds.height() - initH) / 2
        return Rect(initL, initT, initL + initW, initT + initH)
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismiss() }
            return
        }

        synchronized(stateLock) {
            val view = overlayView
            overlayView = null
            canvasViewRef = null

            if (activeAttachedInstance === this) {
                activeAttachedInstance = null
            }

            if (view != null) {
                try {
                    // Section 4 & 9: Instantly eliminate touch interception before removal
                    view.visibility = View.GONE
                    view.setOnTouchListener(null)
                    val wm = windowManager
                    if (wm != null) {
                        try {
                            wm.removeViewImmediate(view)
                            ReadMeCrashLogger.selectionRemoveCount.incrementAndGet()
                        } catch (_: Throwable) {
                            if (view.isAttachedToWindow) {
                                try {
                                    wm.removeView(view)
                                    ReadMeCrashLogger.selectionRemoveCount.incrementAndGet()
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Selector view was already not attached to WindowManager: ${e.message}")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "IllegalStateException removing selector view: ${e.message}")
                } catch (e: Throwable) {
                    Log.w(TAG, "Unexpected error removing selector overlay: ${e.message}")
                } finally {
                    ReadMeCrashLogger.isSelectionAttached = false
                }
            }

            if (currentState != SelectorState.Destroyed) {
                currentState = SelectorState.Idle
                ReadMeCrashLogger.selectorState = "Idle"
            }
        }
    }

    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { destroy() }
            return
        }

        synchronized(stateLock) {
            dismiss()
            currentState = SelectorState.Destroyed
            ReadMeCrashLogger.selectorState = "Destroyed"
            pendingCancelledCallback = null
            pendingSelectedCallback = null
            Log.d(TAG, "ScreenRegionSelectionController destroyed")
        }
    }

    companion object {
        private const val TAG = "ScreenRegionSelection"

        @Volatile
        private var activeAttachedInstance: ScreenRegionSelectionController? = null

        @androidx.annotation.VisibleForTesting
        fun resetActiveInstance() {
            activeAttachedInstance = null
        }
    }
}
