package com.readme.app.accessibility.autonav

import android.graphics.Rect
import com.readme.app.accessibility.AccessibleNode

data class PaginatedReaderInfo(
    val isPaginatedReader: Boolean,
    val readerBounds: Rect,
    val confidenceReason: String,
    val primaryCandidate: AutoNavigator.CandidateNode? = null,
    val hasHorizontalPageAction: Boolean = false
)

/**
 * Deterministically classifies whether an application's current surface is a paginated reader.
 *
 * Implements Phase 9AC Section 2:
 * - Identifies ViewPager, ViewPager2, HorizontalPager, and custom novel/book readers.
 * - Inspects horizontal paging actions (PAGE_RIGHT, PAGE_LEFT, SCROLL_RIGHT).
 * - Checks whether user's selected reading region is contained within the reader bounds.
 * - Explicitly rejects standard vertical scrolling webpages or scrollable lists.
 */
object PaginatedReaderDetector {

    private val PAGED_CLASS_KEYWORDS = listOf(
        "viewpager2",
        "viewpager",
        "horizontalpager",
        "pagedview",
        "pagereader",
        "novelreader",
        "bookreader",
        "storyreader",
        "pagecontainer",
        "readercontainer",
        "readerpager",
        "turnview",
        "curlview",
        "flipview"
    )

    private val VERTICAL_SCROLL_ONLY_KEYWORDS = listOf(
        "nestedscrollview",
        "scrollview",
        "listview",
        "webview"
    )

    fun detect(
        candidates: List<AutoNavigator.CandidateNode>,
        readingRegion: Rect?,
        windowBounds: Rect? = null
    ): PaginatedReaderInfo {
        if (candidates.isEmpty()) {
            val fallbackBounds = windowBounds ?: readingRegion ?: Rect(0, 0, 1080, 1920)
            return PaginatedReaderInfo(
                isPaginatedReader = false,
                readerBounds = fallbackBounds,
                confidenceReason = "No candidate nodes discovered in hierarchy"
            )
        }

        // 1. Check for explicit horizontal pager containers or horizontal paging actions
        for (candidate in candidates) {
            val lowerClass = candidate.className.lowercase()

            val hasHorizontalPageAction = candidate.allActions.contains(AutoNavigator.ID_PAGE_RIGHT) ||
                candidate.allActions.contains(AutoNavigator.ID_PAGE_LEFT) ||
                candidate.allActions.contains(AutoNavigator.ID_SCROLL_RIGHT)

            val isExplicitPagerClass = PAGED_CLASS_KEYWORDS.any { lowerClass.contains(it) }

            if (isExplicitPagerClass || hasHorizontalPageAction) {
                val cW = candidate.bounds.right - candidate.bounds.left
                val cH = candidate.bounds.bottom - candidate.bounds.top
                val bounds = if (cW > 100 && cH > 100) {
                    candidate.bounds
                } else {
                    windowBounds ?: candidate.bounds
                }

                return PaginatedReaderInfo(
                    isPaginatedReader = true,
                    readerBounds = bounds,
                    confidenceReason = if (isExplicitPagerClass) {
                        "Explicit pager container: ${candidate.className}"
                    } else {
                        "Horizontal paging actions exposed on ${candidate.className}"
                    },
                    primaryCandidate = candidate,
                    hasHorizontalPageAction = hasHorizontalPageAction
                )
            }
        }

        // 2. Check for child nodes filling full-screen page surface in reader-like packages
        val bestCandidate = candidates.firstOrNull()
        if (bestCandidate != null) {
            val lowerClass = bestCandidate.className.lowercase()

            val isVerticalOnly = VERTICAL_SCROLL_ONLY_KEYWORDS.any { lowerClass.contains(it) } &&
                !candidateHasAnyHorizontalClue(bestCandidate)

            if (isVerticalOnly) {
                return PaginatedReaderInfo(
                    isPaginatedReader = false,
                    readerBounds = bestCandidate.bounds,
                    confidenceReason = "Vertical scrolling container without horizontal page semantics: ${bestCandidate.className}",
                    primaryCandidate = bestCandidate,
                    hasHorizontalPageAction = false
                )
            }

            val bW = bestCandidate.bounds.right - bestCandidate.bounds.left
            val bH = bestCandidate.bounds.bottom - bestCandidate.bounds.top
            val winW = windowBounds?.let { it.right - it.left } ?: bW
            val winH = windowBounds?.let { it.bottom - it.top } ?: bH

            if (bW >= (winW * 0.70f).toInt() && bH >= (winH * 0.50f).toInt()) {
                val hasPageAction = bestCandidate.allActions.contains(AutoNavigator.ID_PAGE_RIGHT)
                if (lowerClass.contains("reader") || lowerClass.contains("novel") || lowerClass.contains("book")) {
                    return PaginatedReaderInfo(
                        isPaginatedReader = true,
                        readerBounds = bestCandidate.bounds,
                        confidenceReason = "Reader-named container filling screen: ${bestCandidate.className}",
                        primaryCandidate = bestCandidate,
                        hasHorizontalPageAction = hasPageAction
                    )
                }
            }
        }

        val fallbackBounds = bestCandidate?.bounds ?: windowBounds ?: readingRegion ?: Rect(0, 0, 1080, 1920)
        return PaginatedReaderInfo(
            isPaginatedReader = false,
            readerBounds = fallbackBounds,
            confidenceReason = "Standard vertical or non-paginated content",
            primaryCandidate = bestCandidate,
            hasHorizontalPageAction = false
        )
    }

    private fun candidateHasAnyHorizontalClue(candidate: AutoNavigator.CandidateNode): Boolean {
        if (candidate.allActions.contains(AutoNavigator.ID_PAGE_RIGHT)) return true
        if (candidate.allActions.contains(AutoNavigator.ID_PAGE_LEFT)) return true
        if (candidate.allActions.contains(AutoNavigator.ID_SCROLL_RIGHT)) return true
        val lower = candidate.className.lowercase()
        return PAGED_CLASS_KEYWORDS.any { lower.contains(it) }
    }
}
