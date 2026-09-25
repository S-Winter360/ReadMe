package com.readme.app.accessibility.autonav

import android.graphics.Rect
import android.util.Log
import com.readme.app.BuildConfig
import java.util.ArrayDeque

/**
 * Compact fingerprint of screen reading content for pre/post navigation change detection.
 *
 * Enforces:
 * - Privacy: Zero full-text logging. Only lengths, word counts, safe truncated snippet, and hashes.
 * - Deterministic comparison across OCR cycles.
 */
data class ContentFingerprint(
    val length: Int,
    val wordCount: Int,
    val sampleHash: String,
    val previewSnippet: String
) {
    companion object {
        fun create(text: String?): ContentFingerprint {
            if (text.isNullOrBlank()) {
                return ContentFingerprint(
                    length = 0,
                    wordCount = 0,
                    sampleHash = "empty",
                    previewSnippet = ""
                )
            }
            val trimmed = text.trim()
            val words = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }
            val norm = trimmed.lowercase().replace("[^a-z0-9 ]".toRegex(), " ").trim()
            val hash = norm.hashCode().toString()
            val snippet = words.take(4).joinToString(" ")
            return ContentFingerprint(
                length = trimmed.length,
                wordCount = words.size,
                sampleHash = hash,
                previewSnippet = snippet
            )
        }
    }
}

/**
 * In-memory diagnostic record capturing the state and results of an accessibility auto-navigation attempt.
 *
 * Implements Phase 9AA Section 2 requirements:
 * - target package
 * - target window ID
 * - selected node class
 * - selected node bounds
 * - selected node resource ID if available
 * - selected node content description if available
 * - selected node text snippet if safe
 * - isScrollable
 * - available accessibility actions
 * - selected navigation action
 * - performAction return value
 * - timestamp before action
 * - timestamp after action
 * - accessibility events received
 * - pre-navigation content fingerprint
 * - post-navigation content fingerprint
 * - page-change detected
 * - final navigation result
 */
data class NavigationDiagnosticRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val targetPackage: String,
    val targetWindowId: Int,
    val selectedNodeClass: String,
    val selectedNodeBounds: Rect,
    val selectedNodeResourceId: String?,
    val selectedNodeContentDescription: String?,
    val selectedNodeTextSnippet: String?,
    val isScrollable: Boolean,
    val availableActions: List<Int>,
    val selectedNavigationAction: String,
    val performActionReturnValue: Boolean,
    val timestampBeforeAction: Long,
    val timestampAfterAction: Long,
    val accessibilityEventsReceived: List<String>,
    val preNavigationFingerprint: ContentFingerprint,
    val postNavigationFingerprint: ContentFingerprint,
    val pageChangeDetected: Boolean,
    val finalNavigationResult: String
)

/**
 * In-memory diagnostics registry for auditing novel-reader navigation and page-turn actions.
 *
 * Strictly in-memory during DEBUG/test execution.
 * Zero persistence of bitmaps or full document content.
 */
object AutoNavigationDiagnostics {
    private const val TAG = "ReadMeNavDiagnostic"
    private const val MAX_HISTORY = 20

    private val history = ArrayDeque<NavigationDiagnosticRecord>()

    @Volatile
    var latestRecord: NavigationDiagnosticRecord? = null
        private set

    @Synchronized
    fun record(record: NavigationDiagnosticRecord) {
        latestRecord = record
        if (history.size >= MAX_HISTORY) {
            history.removeFirst()
        }
        history.addLast(record)

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "AutoNavAttempt: pkg=${record.targetPackage}, node=${record.selectedNodeClass}, " +
                    "action=${record.selectedNavigationAction}, dispatched=${record.performActionReturnValue}, " +
                    "pageChanged=${record.pageChangeDetected}, result=${record.finalNavigationResult}, " +
                    "events=${record.accessibilityEventsReceived.size}"
            )
        }
    }

    @Synchronized
    fun getHistory(): List<NavigationDiagnosticRecord> {
        return history.toList()
    }

    @Synchronized
    fun clear() {
        history.clear()
        latestRecord = null
    }
}
