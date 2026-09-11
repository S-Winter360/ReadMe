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
    val childCount: Int
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
    override val childCount: Int get() = node.childCount

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
