package com.readme.app.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.readme.app.ui.theme.ReadMeTheme

/**
 * Overlay window that displays the subtle bottom drop target ("Release to close")
 * during drag-to-close gestures.
 */
class CloseZoneOverlayController(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var dropZoneView: SystemFloatingBubbleView? = null
    private var isAdded = false
    private var isHoveredState by mutableStateOf(false)

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    fun show() {
        if (dropZoneView == null) {
            dropZoneView = SystemFloatingBubbleView(context).apply {
                composeView.setContent {
                    ReadMeTheme(darkTheme = true) {
                        CloseDropZoneContent(isHovered = isHoveredState)
                    }
                }
                start()
            }
        } else {
            updateContent()
        }

        if (!isAdded) {
            try {
                val params = createLayoutParams()
                windowManager?.addView(dropZoneView, params)
                isAdded = true
            } catch (e: Exception) {
                isAdded = false
            }
        }
    }

    fun setHovered(hovered: Boolean) {
        if (isHoveredState != hovered) {
            isHoveredState = hovered
            updateContent()
        }
    }

    private fun updateContent() {
        dropZoneView?.composeView?.setContent {
            ReadMeTheme(darkTheme = true) {
                CloseDropZoneContent(isHovered = isHoveredState)
            }
        }
    }

    fun hide() {
        if (isAdded) {
            try {
                dropZoneView?.stop()
                windowManager?.removeView(dropZoneView)
            } catch (e: Exception) {
                // Ignore if already removed
            } finally {
                isAdded = false
                isHoveredState = false
            }
        }
    }

    fun destroy() {
        hide()
        dropZoneView?.destroy()
        dropZoneView = null
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 32
        }
    }
}

@Composable
fun CloseDropZoneContent(isHovered: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        val containerColor = if (isHovered) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
        }

        val contentColor = if (isHovered) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = containerColor,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .semantics {
                    this.contentDescription = "Release to close"
                }
                .testTag("close_drop_zone")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "✕",
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Release to close",
                    color = contentColor,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
