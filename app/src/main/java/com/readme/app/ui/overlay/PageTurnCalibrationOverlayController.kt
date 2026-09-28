package com.readme.app.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import android.widget.Toast
import com.readme.app.R
import com.readme.app.accessibility.ScreenGeometryMapper
import com.readme.app.accessibility.autonav.PageTurnCalibration
import com.readme.app.accessibility.autonav.PageTurnCalibrationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Controller that presents a temporary, lightly dimmed full-screen calibration overlay
 * according to Phase 9AD.
 *
 * The user taps the exact spot they normally use to turn pages in their novel reader.
 * ReadMe calculates and stores the relative coordinates for that app and orientation.
 */
class PageTurnCalibrationOverlayController(private val context: Context) {

    private val themedContext: Context = ContextThemeWrapper(context, R.style.Theme_ReadMe)
    private val windowManager: WindowManager? =
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: FrameLayout? = null
    private var isAdded = false

    @Volatile
    var isArmed: Boolean = false
        private set

    private var armedCallback: ((PageTurnCalibration?) -> Unit)? = null
    private var armedTargetPackage: String? = null

    fun isShowing(): Boolean = isAdded

    /**
     * Arms the calibration workflow. If the user is currently inside ReadMe Settings,
     * ReadMe will wait until the foreground app switches to the target reader app before
     * displaying the calibration overlay.
     */
    fun armCalibration(
        targetPackage: String? = null,
        onCompleted: (PageTurnCalibration?) -> Unit
    ) {
        if (isAdded) dismiss()

        armedTargetPackage = targetPackage
        armedCallback = onCompleted
        isArmed = true
    }

    fun disarm() {
        isArmed = false
        armedTargetPackage = null
        armedCallback = null
    }

    /**
     * Called by ReadMeAccessibilityService when window state changes.
     */
    fun checkAndTriggerIfArmed(newPackage: String, windowBounds: Rect? = null) {
        if (!isArmed) return
        if (newPackage.isBlank() || newPackage == context.packageName || newPackage == "com.android.systemui") {
            return
        }

        val callback = armedCallback
        isArmed = false
        armedTargetPackage = null
        armedCallback = null

        mainHandler.post {
            show(
                packageName = newPackage,
                windowBounds = windowBounds,
                onCompleted = { cal -> callback?.invoke(cal) },
                onCancelled = { callback?.invoke(null) }
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        packageName: String,
        windowBounds: Rect? = null,
        onCompleted: (PageTurnCalibration) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (isAdded) dismiss()
        isArmed = false

        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Overlay permission required for calibration", Toast.LENGTH_SHORT).show()
            onCancelled()
            return
        }

        val wm = windowManager ?: run {
            onCancelled()
            return
        }

        val realBounds = ScreenGeometryMapper.getRealDisplayBounds(context)
        val screenW = realBounds.width().coerceAtLeast(720)
        val screenH = realBounds.height().coerceAtLeast(1280)
        val dm = context.resources.displayMetrics
        val orientation = context.resources.configuration.orientation

        val effectiveBounds = windowBounds?.takeIf { !it.isEmpty }
            ?: Rect(0, 0, screenW, screenH)

        var tappedX = -1f
        var tappedY = -1f
        var isConfirmed = false

        val root = FrameLayout(themedContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Custom Canvas View for rendering scrim and tap marker feedback
        val canvasView = object : View(themedContext) {
            private val scrimPaint = Paint().apply {
                color = Color.parseColor("#73000000") // 45% black scrim so story text is clearly visible
            }
            private val markerOuterPaint = Paint().apply {
                color = Color.parseColor("#00B4D8") // ReadMe Cyan / Teal
                style = Paint.Style.STROKE
                strokeWidth = (3.5f * dm.density).coerceAtLeast(6f)
                isAntiAlias = true
            }
            private val markerInnerPaint = Paint().apply {
                color = Color.parseColor("#90E0EF")
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            private val markerCenterDotPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            private val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 15f * dm.scaledDensity
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                isAntiAlias = true
                setShadowLayer(4f, 0f, 2f, Color.BLACK)
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                // Draw light scrim
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)

                // Draw tap marker if user tapped
                if (tappedX >= 0f && tappedY >= 0f) {
                    val outerRadius = 32f * dm.density
                    val innerRadius = 16f * dm.density
                    val dotRadius = 5f * dm.density

                    canvas.drawCircle(tappedX, tappedY, outerRadius, markerOuterPaint)
                    canvas.drawCircle(tappedX, tappedY, innerRadius, markerInnerPaint)
                    canvas.drawCircle(tappedX, tappedY, dotRadius, markerCenterDotPaint)

                    val textY = if (tappedY > 120f * dm.density) {
                        tappedY - outerRadius - (12f * dm.density)
                    } else {
                        tappedY + outerRadius + (24f * dm.density)
                    }
                    canvas.drawText("Page-Turn Tap Calibrated! ✓", tappedX, textY, textPaint)
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

        // Instruction card banner at top
        val bannerCard = LinearLayout(themedContext).apply {
            setOrientation(LinearLayout.VERTICAL)
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = (20 * dm.density).toInt()
            val padV = (16 * dm.density).toInt()
            setPadding(padH, padV, padH, padV)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F01E1E1E"))
                cornerRadius = 20f * dm.density
                setStroke((1.5f * dm.density).toInt(), Color.parseColor("#00B4D8"))
            }
            background = bg
            elevation = 16f * dm.density
        }

        val titleText = TextView(themedContext).apply {
            text = "Calibrate Next Page Turn"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val subtitleText = TextView(themedContext).apply {
            text = "Tap the exact location on the screen where you normally tap to turn to the next page in this reader."
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, (6 * dm.density).toInt(), 0, (12 * dm.density).toInt())
        }

        val cancelButton = TextView(themedContext).apply {
            text = "Cancel"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#3A3A3A"))
                cornerRadius = 14f * dm.density
            }
            background = btnBg
            minHeight = (48 * dm.density).toInt()
            minWidth = (120 * dm.density).toInt()
            contentDescription = "Cancel calibration"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dismiss()
                onCancelled()
            }
        }

        bannerCard.addView(titleText)
        bannerCard.addView(subtitleText)
        bannerCard.addView(cancelButton)

        val bannerParams = FrameLayout.LayoutParams(
            (screenW * 0.88f).toInt().coerceIn(300, 560),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (48 * dm.density).toInt()
        }
        root.addView(bannerCard, bannerParams)

        // Handle tap on canvas
        canvasView.setOnTouchListener { _, event ->
            if (isConfirmed) return@setOnTouchListener true
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_DOWN) {
                val rawX = event.x
                val rawY = event.y

                // Ignore if touch was inside the instruction banner
                val bannerLoc = IntArray(2)
                bannerCard.getLocationOnScreen(bannerLoc)
                val bannerRect = Rect(
                    bannerLoc[0],
                    bannerLoc[1],
                    bannerLoc[0] + bannerCard.width,
                    bannerLoc[1] + bannerCard.height
                )
                if (bannerRect.contains(rawX.toInt(), rawY.toInt())) {
                    return@setOnTouchListener false
                }

                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    isConfirmed = true
                    tappedX = rawX
                    tappedY = rawY
                    canvasView.invalidate()

                    val relX = ((rawX - effectiveBounds.left) / effectiveBounds.width().toFloat()).coerceIn(0.01f, 0.99f)
                    val relY = ((rawY - effectiveBounds.top) / effectiveBounds.height().toFloat()).coerceIn(0.01f, 0.99f)

                    val calibration = PageTurnCalibration(
                        packageName = packageName,
                        relativeX = relX,
                        relativeY = relY,
                        absoluteX = rawX,
                        absoluteY = rawY,
                        screenWidth = screenW,
                        screenHeight = screenH,
                        orientation = orientation,
                        readerBounds = effectiveBounds,
                        calibratedTimestamp = System.currentTimeMillis()
                    )

                    // Persist calibration
                    CoroutineScope(Dispatchers.IO).launch {
                        PageTurnCalibrationRepository.getInstance(context).saveCalibration(calibration)
                    }

                    Toast.makeText(context, "Page-turn tap calibrated!", Toast.LENGTH_SHORT).show()

                    mainHandler.postDelayed({
                        dismiss()
                        onCompleted(calibration)
                    }, 400L)
                }
                return@setOnTouchListener true
            }
            true
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
            isAdded = true
        } catch (e: Exception) {
            isAdded = false
            onCancelled()
        }
    }

    fun dismiss() {
        disarm()
        if (!isAdded) return
        val view = overlayView ?: return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Throwable) {
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {}
        } finally {
            overlayView = null
            isAdded = false
        }
    }
}
