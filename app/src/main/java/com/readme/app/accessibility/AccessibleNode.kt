package com.readme.app.accessibility

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Abstraction over an accessibility node allowing pure Kotlin DFS traversal and unit testing
 * without requiring live Android framework mocks.
 */
interface AccessibleNode {
    val packageName: CharSequence?
    val text: CharSequence?
    val contentDescription: CharSequence?
    val isPassword: Boolean
    val isVisibleToUser: Boolean
    val isHeading: Boolean
    val className: CharSequence?
    val isScrollable: Boolean get() = false
    val isEnabled: Boolean get() = true
    val childCount: Int
    val viewIdResourceName: String? get() = null
    fun getBoundsInScreen(outBounds: android.graphics.Rect) {}
    fun availableActions(): List<Int> = emptyList()
    fun performAction(actionId: Int): Boolean = false
    fun getChild(index: Int): AccessibleNode?
    fun recycle()
}

/**
 * Concrete Android framework implementation of [AccessibleNode] wrapping [AccessibilityNodeInfo].
 */
class AndroidAccessibleNode(val node: AccessibilityNodeInfo) : AccessibleNode {
    override val packageName: CharSequence? get() = node.packageName
    override val text: CharSequence? get() = node.text
    override val contentDescription: CharSequence? get() = node.contentDescription
    override val isPassword: Boolean get() = node.isPassword
    override val isVisibleToUser: Boolean get() = node.isVisibleToUser
    override val isHeading: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.isHeading
        } else {
            false
        }
    override val className: CharSequence? get() = node.className
    override val isScrollable: Boolean get() = node.isScrollable
    override val isEnabled: Boolean get() = node.isEnabled
    override val childCount: Int get() = node.childCount
    override val viewIdResourceName: String?
        get() = try {
            node.viewIdResourceName
        } catch (_: Throwable) {
            null
        }

    override fun getBoundsInScreen(outBounds: android.graphics.Rect) {
        try {
            node.getBoundsInScreen(outBounds)
        } catch (_: Throwable) {}
    }

    override fun availableActions(): List<Int> {
        return try {
            node.actionList.map { it.id }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    override fun performAction(actionId: Int): Boolean {
        return try {
            node.performAction(actionId)
        } catch (_: Throwable) {
            false
        }
    }

    override fun getChild(index: Int): AccessibleNode? {
        val child = node.getChild(index) ?: return null
        return AndroidAccessibleNode(child)
    }

    override fun recycle() {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Throwable) {
            // Ignored on newer platform levels where recycling is automatic
        }
    }
}
