package com.readme.app.ui.overlay

import android.util.Log
import com.readme.app.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostic and touch lifecycle tracing for the floating ReadMe bubble (Phase 9AI).
 *
 * Tracks pointer events (ACTION_DOWN, ACTION_MOVE, ACTION_UP, ACTION_CANCEL),
 * drag vs tap discrimination, displacement measurements, and callback dispatches.
 */
object BubbleTouchTrace {

    private const val TAG = "BubbleTouchTrace"

    @Volatile var lastAction: String = "None"
    @Volatile var lastPointerId: Long = -1L
    @Volatile var startX: Float = 0f
    @Volatile var startY: Float = 0f
    @Volatile var currentX: Float = 0f
    @Volatile var currentY: Float = 0f
    @Volatile var displacement: Float = 0f
    @Volatile var dragThresholdPx: Float = 0f
    @Volatile var isDragging: Boolean = false
    @Volatile var isClosing: Boolean = false
    @Volatile var isExpandedBefore: Boolean = false
    @Volatile var isExpandedAfter: Boolean = false
    @Volatile var lastCallbackInvoked: String = "None"

    val tapCount = AtomicInteger(0)
    val dragCount = AtomicInteger(0)
    val cancelCount = AtomicInteger(0)

    fun reset() {
        lastAction = "None"
        lastPointerId = -1L
        startX = 0f
        startY = 0f
        currentX = 0f
        currentY = 0f
        displacement = 0f
        dragThresholdPx = 0f
        isDragging = false
        isClosing = false
        isExpandedBefore = false
        isExpandedAfter = false
        lastCallbackInvoked = "None"
        tapCount.set(0)
        dragCount.set(0)
        cancelCount.set(0)
    }

    fun recordDown(pointerId: Long, startX: Float, startY: Float, threshold: Float, expanded: Boolean) {
        lastAction = "ACTION_DOWN"
        lastPointerId = pointerId
        this.startX = startX
        this.startY = startY
        this.currentX = startX
        this.currentY = startY
        this.displacement = 0f
        this.dragThresholdPx = threshold
        this.isDragging = false
        this.isClosing = false
        this.isExpandedBefore = expanded
        this.isExpandedAfter = expanded
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ACTION_DOWN: pointerId=$pointerId, start=($startX, $startY), threshold=$threshold, isExpanded=$expanded")
        }
    }

    fun recordMove(currentX: Float, currentY: Float, displacement: Float, dragging: Boolean) {
        lastAction = "ACTION_MOVE"
        this.currentX = currentX
        this.currentY = currentY
        this.displacement = displacement
        this.isDragging = dragging
    }

    fun recordDragStart() {
        this.isDragging = true
        dragCount.incrementAndGet()
        lastCallbackInvoked = "onDragStart"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Drag threshold exceeded ($displacement > $dragThresholdPx): started DRAG")
        }
    }

    fun recordTap(isExpandedBefore: Boolean, isExpandedAfter: Boolean) {
        lastAction = "ACTION_UP"
        this.isDragging = false
        this.isExpandedBefore = isExpandedBefore
        this.isExpandedAfter = isExpandedAfter
        lastCallbackInvoked = "onBubbleTap"
        tapCount.incrementAndGet()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ACTION_UP -> TAP confirmed: displacement=$displacement <= $dragThresholdPx, expanded: $isExpandedBefore -> $isExpandedAfter")
        }
    }

    fun recordRelease() {
        lastAction = "ACTION_UP"
        this.isDragging = false
        lastCallbackInvoked = "onDragEnd"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ACTION_UP -> DRAG released: final displacement=$displacement")
        }
    }

    fun recordCancel(wasDragging: Boolean) {
        lastAction = "ACTION_CANCEL"
        this.isDragging = false
        cancelCount.incrementAndGet()
        lastCallbackInvoked = if (wasDragging) "onDragCancel" else "None"
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ACTION_CANCEL: wasDragging=$wasDragging")
        }
    }

    /**
     * Diagnostic helper providing a deterministic test/audit snapshot of touch state.
     */
    fun getSnapshot(): String {
        return "BubbleTouchTrace(action=$lastAction, pointerId=$lastPointerId, start=($startX, $startY), " +
                "current=($currentX, $currentY), displacement=$displacement, threshold=$dragThresholdPx, " +
                "isDragging=$isDragging, expandedBefore=$isExpandedBefore, expandedAfter=$isExpandedAfter, " +
                "callback=$lastCallbackInvoked, taps=${tapCount.get()}, drags=${dragCount.get()})"
    }
}
