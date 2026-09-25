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
        val fallbackActions: List<Pair<NavigationActionType, Int>> = emptyList(),
        val bounds: Rect,
        val className: String,
        val score: Int
    )

    /**
     * Traverses the accessibility node tree starting from [root], identifying candidate
     * scrollable/paged container nodes ranked by suitability.
     */
    fun findCandidates(
        root: AccessibleNode?,
        readingRegion: Rect?
    ): List<CandidateNode> {
        if (root == null) return emptyList()

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

        return candidates.sortedByDescending { it.score }
    }

    /**
     * Traverses the accessibility node tree starting from [root], identifying the best candidate
     * scrollable/paged container node and selecting its best advance action.
     */
    fun findBestCandidate(
        root: AccessibleNode?,
        readingRegion: Rect?
    ): CandidateNode? {
        return findCandidates(root, readingRegion).firstOrNull()
    }

    /**
     * Dispatches the navigation action on the best candidate node in the tree.
     * If the primary candidate or action is rejected, attempts fallback actions or candidates.
     */
    fun executeNavigation(
        root: AccessibleNode?,
        readingRegion: Rect?
    ): AutoAdvanceActionResult {
        val candidates = findCandidates(root, readingRegion)
        if (candidates.isEmpty()) {
            return AutoAdvanceActionResult.NoCandidateNodeFound
        }

        for (candidate in candidates.take(3)) {
            val actionsToTry = listOf(candidate.actionType to candidate.actionId) + candidate.fallbackActions
            for ((actionType, actionId) in actionsToTry) {
                try {
                    val actionSucceeded = candidate.node.performAction(actionId)
                    if (actionSucceeded) {
                        return AutoAdvanceActionResult.Dispatched(
                            actionType = actionType.name,
                            nodeClass = candidate.className
                        )
                    }
                } catch (e: Throwable) {
                    // Try next action/candidate
                }
            }
        }

        return AutoAdvanceActionResult.ActionRejectedByNode
    }

    private fun evaluateNodeForAction(
        node: AccessibleNode,
        nodeBounds: Rect,
        actions: List<Int>,
        readingRegion: Rect?
    ): CandidateNode? {
        val actionSet = actions.toSet()

        // Discover viable forward-advance actions in order of reading preference:
        val viableActions = mutableListOf<Pair<NavigationActionType, Int>>()
        if (actionSet.contains(ID_PAGE_RIGHT)) viableActions.add(NavigationActionType.PAGE_RIGHT to ID_PAGE_RIGHT)
        if (actionSet.contains(ID_PAGE_DOWN)) viableActions.add(NavigationActionType.PAGE_DOWN to ID_PAGE_DOWN)
        if (actionSet.contains(ID_SCROLL_DOWN)) viableActions.add(NavigationActionType.SCROLL_DOWN to ID_SCROLL_DOWN)
        if (actionSet.contains(ID_SCROLL_FORWARD)) viableActions.add(NavigationActionType.SCROLL_FORWARD to ID_SCROLL_FORWARD)
        if (actionSet.contains(ID_SCROLL_RIGHT)) viableActions.add(NavigationActionType.SCROLL_RIGHT to ID_SCROLL_RIGHT)
        if (actionSet.contains(ID_PAGE_LEFT)) viableActions.add(NavigationActionType.PAGE_LEFT to ID_PAGE_LEFT)
        if (actionSet.contains(ID_PAGE_UP)) viableActions.add(NavigationActionType.PAGE_UP to ID_PAGE_UP)

        if (viableActions.isEmpty()) return null

        val primaryAction = viableActions.first()
        val fallbacks = viableActions.drop(1)

        val className = node.className?.toString() ?: ""
        val lowerClass = className.lowercase()
        var score = 10

        // Explicitly marked scrollable containers get a massive priority boost
        if (node.isScrollable) {
            score += 40
        }

        // Paging vs scrolling actions
        when (primaryAction.first) {
            NavigationActionType.PAGE_RIGHT, NavigationActionType.PAGE_DOWN -> score += 35
            NavigationActionType.SCROLL_FORWARD, NavigationActionType.SCROLL_DOWN -> score += 30
            else -> score += 10
        }

        // Favor prominent reading containers based on class name
        if (lowerClass.contains("viewpager") || lowerClass.contains("reader") || lowerClass.contains("pager")) {
            score += 45
        } else if (lowerClass.contains("recyclerview") || lowerClass.contains("scrollview") || lowerClass.contains("webview") || lowerClass.contains("adapterview") || lowerClass.contains("listview")) {
            score += 35
        } else if (!node.isScrollable && (lowerClass.contains("textview") || lowerClass.contains("text") || lowerClass.contains("image"))) {
            // Static leaf block with spurious actions: penalize heavily so true containers win
            score -= 50
        }

        // Region containment/overlap: favor container containing or overlapping the reading region
        if (readingRegion != null && !readingRegion.isEmpty && !nodeBounds.isEmpty) {
            if (nodeBounds.contains(readingRegion)) {
                score += 30
            } else if (Rect.intersects(nodeBounds, readingRegion)) {
                score += 15
            }
        }

        return CandidateNode(
            node = node,
            actionType = primaryAction.first,
            actionId = primaryAction.second,
            fallbackActions = fallbacks,
            bounds = nodeBounds,
            className = className,
            score = score
        )
    }
}
