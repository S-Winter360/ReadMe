package com.readme.app.accessibility.autonav

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.readme.app.accessibility.AccessibleNode
import java.util.ArrayDeque

/**
 * Discovers and dispatches accessibility page-turning and scroll actions on external
 * applications deterministically according to Phase 9X Part B.
 *
 * Enforces:
 * - Read-only / accessibility-action-only execution: NO gesture dispatch, NO simulated swipes.
 * - Only uses explicit [AccessibilityNodeInfo] actions exposed by the target container.
 * - Prioritizes candidate nodes that are visible, enabled, scrollable, and contain/overlap
 *   the user's selected reading region.
 * - Prioritizes horizontal page actions (e.g. ACTION_PAGE_RIGHT) for paged reading readers,
 *   and vertical scroll actions (ACTION_SCROLL_DOWN / ACTION_SCROLL_FORWARD) for vertical readers.
 */
object AutoNavigator {

    // Standard Android framework action IDs for cross-version compatibility
    val ID_PAGE_RIGHT: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id
        } else 16908381
    }

    val ID_PAGE_DOWN: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
        } else 16908379
    }

    val ID_PAGE_LEFT: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id
        } else 16908380
    }

    val ID_PAGE_UP: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
        } else 16908378
    }

    val ID_SCROLL_DOWN: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        } else 16908346
    }

    val ID_SCROLL_FORWARD: Int = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

    val ID_SCROLL_RIGHT: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
        } else 16908347
    }

    data class CandidateNode(
        val node: AccessibleNode,
        val actionType: NavigationActionType,
        val actionId: Int,
        val bounds: Rect,
        val className: String,
        val score: Int
    )

    /**
     * Traverses the accessibility node tree starting from [root], identifying the best candidate
     * scrollable/paged container node and selecting its best advance action.
     */
    fun findBestCandidate(
        root: AccessibleNode?,
        readingRegion: Rect?
    ): CandidateNode? {
        if (root == null) return null

        val candidates = mutableListOf<CandidateNode>()
        val queue = ArrayDeque<AccessibleNode>()
        queue.add(root)
        var visitedCount = 0

        while (queue.isNotEmpty() && visitedCount < 300) {
            val node = queue.removeFirst()
            visitedCount++

            if (node.isVisibleToUser && node.isEnabled) {
                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                val availableActions = node.availableActions()
                val candidate = evaluateNodeForAction(node, nodeBounds, availableActions, readingRegion)
                if (candidate != null) {
                    candidates.add(candidate)
                }

                for (i in 0 until node.childCount) {
                    val child = try { node.getChild(i) } catch (_: Throwable) { null }
                    if (child != null) {
                        queue.add(child)
                    }
                }
            }
        }

        // Return candidate with highest score
        return candidates.maxByOrNull { it.score }
    }

    /**
     * Dispatches the navigation action on the best candidate node in the tree.
     */
    fun executeNavigation(
        root: AccessibleNode?,
        readingRegion: Rect?
    ): AutoAdvanceActionResult {
        val candidate = findBestCandidate(root, readingRegion)
            ?: return AutoAdvanceActionResult.NoCandidateNodeFound

        val actionSucceeded = try {
            candidate.node.performAction(candidate.actionId)
        } catch (e: Throwable) {
            return AutoAdvanceActionResult.Error(e.message ?: "Action invocation threw exception")
        }

        return if (actionSucceeded) {
            AutoAdvanceActionResult.Dispatched(
                actionType = candidate.actionType.name,
                nodeClass = candidate.className
            )
        } else {
            AutoAdvanceActionResult.ActionRejectedByNode
        }
    }

    private fun evaluateNodeForAction(
        node: AccessibleNode,
        nodeBounds: Rect,
        actions: List<Int>,
        readingRegion: Rect?
    ): CandidateNode? {
        val actionSet = actions.toSet()

        // 1. Identify available action with preference:
        // Paged Reader -> ACTION_PAGE_RIGHT, ACTION_PAGE_DOWN
        // Scroll Reader -> ACTION_SCROLL_DOWN, ACTION_SCROLL_FORWARD
        // Alternate -> ACTION_PAGE_LEFT, ACTION_SCROLL_RIGHT
        val matchedAction: Pair<NavigationActionType, Int>? = when {
            actionSet.contains(ID_PAGE_RIGHT) -> NavigationActionType.PAGE_RIGHT to ID_PAGE_RIGHT
            actionSet.contains(ID_PAGE_DOWN) -> NavigationActionType.PAGE_DOWN to ID_PAGE_DOWN
            actionSet.contains(ID_SCROLL_DOWN) -> NavigationActionType.SCROLL_DOWN to ID_SCROLL_DOWN
            actionSet.contains(ID_SCROLL_FORWARD) -> NavigationActionType.SCROLL_FORWARD to ID_SCROLL_FORWARD
            actionSet.contains(ID_SCROLL_RIGHT) -> NavigationActionType.SCROLL_RIGHT to ID_SCROLL_RIGHT
            actionSet.contains(ID_PAGE_LEFT) -> NavigationActionType.PAGE_LEFT to ID_PAGE_LEFT
            actionSet.contains(ID_PAGE_UP) -> NavigationActionType.PAGE_UP to ID_PAGE_UP
            else -> null
        }

        if (matchedAction == null) return null

        val className = node.className?.toString() ?: ""
        var score = 10

        // Explicitly marked scrollable
        if (node.isScrollable) {
            score += 30
        }

        // Paging actions are given higher priority for reading apps
        when (matchedAction.first) {
            NavigationActionType.PAGE_RIGHT, NavigationActionType.PAGE_DOWN -> score += 40
            NavigationActionType.SCROLL_DOWN, NavigationActionType.SCROLL_FORWARD -> score += 25
            else -> score += 10
        }

        // Region containment/overlap: strongly favor container containing the reading region
        if (readingRegion != null && !readingRegion.isEmpty && !nodeBounds.isEmpty) {
            if (nodeBounds.contains(readingRegion)) {
                score += 50
            } else if (Rect.intersects(nodeBounds, readingRegion)) {
                score += 25
            }
        }

        // Favor prominent reading containers based on class name
        val lowerClass = className.lowercase()
        if (lowerClass.contains("viewpager") || lowerClass.contains("reader") || lowerClass.contains("pager")) {
            score += 35
        } else if (lowerClass.contains("recyclerview") || lowerClass.contains("scrollview") || lowerClass.contains("webview")) {
            score += 20
        }

        return CandidateNode(
            node = node,
            actionType = matchedAction.first,
            actionId = matchedAction.second,
            bounds = nodeBounds,
            className = className,
            score = score
        )
    }
}
