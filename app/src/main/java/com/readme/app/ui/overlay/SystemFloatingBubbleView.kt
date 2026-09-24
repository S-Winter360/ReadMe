package com.readme.app.ui.overlay

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.readme.app.accessibility.CrossAppAcquisitionMode
import com.readme.app.reading.ActiveDocumentState
import com.readme.app.reading.ActiveReadingSessionState

data class BubbleViewPayload(
    val sessionState: ActiveReadingSessionState,
    val activeDocumentState: ActiveDocumentState,
    val crossAppReadingEnabled: Boolean,
    val canAcquireText: Boolean,
    val isAutoAdvanceEnabled: Boolean = false,
    val onToggleReading: () -> Unit,
    val onPauseReading: () -> Unit,
    val onResumeReading: () -> Unit,
    val onReselectArea: () -> Unit,
    val onStopReading: () -> Unit,
    val onCloseBubble: () -> Unit,
    val onAcquireMode: (CrossAppAcquisitionMode) -> Unit,
    val onDragStart: () -> Unit,
    val onDrag: (Float, Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onDragCancel: () -> Unit
)

class SystemFloatingBubbleView(context: Context) : FrameLayout(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val _viewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = _viewModelStore
        
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    val composeView = ComposeView(context)
    val bubblePayloadState = mutableStateOf<BubbleViewPayload?>(null)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        setViewTreeLifecycleOwner(this)
        setViewTreeSavedStateRegistryOwner(this)
        setViewTreeViewModelStoreOwner(this)

        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)

        composeView.setContent {
            val payload = bubblePayloadState.value
            if (payload != null) {
                SystemFloatingBubbleContent(
                    sessionState = payload.sessionState,
                    activeDocumentState = payload.activeDocumentState,
                    crossAppReadingEnabled = payload.crossAppReadingEnabled,
                    canAcquireText = payload.canAcquireText,
                    isAutoAdvanceEnabled = payload.isAutoAdvanceEnabled,
                    onToggleReading = payload.onToggleReading,
                    onPauseReading = payload.onPauseReading,
                    onResumeReading = payload.onResumeReading,
                    onReselectArea = payload.onReselectArea,
                    onStopReading = payload.onStopReading,
                    onCloseBubble = payload.onCloseBubble,
                    onAcquireMode = payload.onAcquireMode,
                    onDragStart = payload.onDragStart,
                    onDrag = payload.onDrag,
                    onDragEnd = payload.onDragEnd,
                    onDragCancel = payload.onDragCancel
                )
            }
        }
        
        addView(composeView)
    }

    fun updatePayload(payload: BubbleViewPayload) {
        bubblePayloadState.value = payload
    }

    fun start() {
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED || lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    fun stop() {
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    fun destroy() {
        stop()
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        _viewModelStore.clear()
    }
}
