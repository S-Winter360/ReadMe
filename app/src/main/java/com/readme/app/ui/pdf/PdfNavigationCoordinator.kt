package com.readme.app.ui.pdf

import android.util.Log
import com.readme.app.reading.ReadingPosition
import com.readme.app.reading.ReadingSessionState
import com.readme.app.reading.content.pdf.PdfReadingPositionMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates speech-driven PDF page navigation according to Phase 8E rules:
 * - One-way sync: Speech -> Viewport only.
 * - Viewport movements and zoom changes never alter speech or reading position.
 * - One-shot navigation requests without repeated loops.
 * - Document-aware navigation: requests for document A never navigate document B.
 * - Safe handling of unready viewers (single pending target).
 * - Newer speech target replaces older pending target without unbounded queues.
 */
class PdfNavigationCoordinator(
    private var navigator: PdfPageNavigator? = null
) {
    private val _pendingNavigation = MutableStateFlow<PendingPdfNavigation?>(null)
    val pendingNavigation: StateFlow<PendingPdfNavigation?> = _pendingNavigation.asStateFlow()

    var pdfMapper: PdfReadingPositionMapper? = null
        private set

    var isPdfActive: Boolean = false
        private set

    var readingSessionState: ReadingSessionState = ReadingSessionState.Idle
        private set

    var currentViewportState: PdfViewportState = PdfViewportState()
        private set

    var lastNavigatedPage: Int? = null
        private set

    fun getNavigator(): PdfPageNavigator? = navigator

    fun setNavigator(navigator: PdfPageNavigator?) {
        this.navigator = navigator
        if (navigator != null) {
            val pending = _pendingNavigation.value
            val mapper = pdfMapper
            if (pending != null && mapper != null && isPdfActive && pending.documentId == mapper.documentId) {
                if (!mapper.isPageVisible(pending.pageIndex, currentViewportState)) {
                    executeNavigation(pending)
                } else {
                    _pendingNavigation.value = null
                }
            }
        }
    }

    fun setPdfDocument(mapper: PdfReadingPositionMapper?, isActive: Boolean) {
        _pendingNavigation.value = null
        lastNavigatedPage = null
        this.pdfMapper = mapper
        this.isPdfActive = isActive
    }

    fun clearDocument() {
        _pendingNavigation.value = null
        lastNavigatedPage = null
        this.pdfMapper = null
        this.isPdfActive = false
        this.currentViewportState = PdfViewportState()
    }

    fun setReadingState(state: ReadingSessionState) {
        this.readingSessionState = state
        if (state == ReadingSessionState.Stopped || state == ReadingSessionState.Completed) {
            _pendingNavigation.value = null
            lastNavigatedPage = null
        }
    }

    fun onReadingStarted(position: ReadingPosition?) {
        this.readingSessionState = ReadingSessionState.Reading
        this.lastNavigatedPage = null
        evaluateNavigation(position)
    }

    fun onReadingStopped() {
        this.readingSessionState = ReadingSessionState.Stopped
        _pendingNavigation.value = null
        lastNavigatedPage = null
    }

    /**
     * Viewport change callback.
     * Updates internal viewport state and clears pending navigation if the viewport
     * has confirmed visibility of the target page.
     * Does NOT evaluate navigation or trigger speech movement.
     */
    fun onViewportChanged(viewportState: PdfViewportState) {
        this.currentViewportState = viewportState
        val pending = _pendingNavigation.value
        val mapper = pdfMapper
        if (pending != null && mapper != null && pending.documentId == mapper.documentId) {
            if (mapper.isPageVisible(pending.pageIndex, viewportState)) {
                _pendingNavigation.value = null
            }
        }
    }

    /**
     * Evaluates whether an automatic speech-driven PDF page navigation should occur.
     */
    fun evaluateNavigation(position: ReadingPosition?) {
        val mapper = pdfMapper ?: return
        if (!isPdfActive) return
        if (readingSessionState != ReadingSessionState.Reading) return
        if (position == null || position.documentId != mapper.documentId) return

        val speechPage = mapper.getPageForPosition(position) ?: return

        // 1. If the speech page is already visible, do not navigate
        val isVisible = mapper.isPageVisible(speechPage, currentViewportState)
        if (isVisible) {
            if (_pendingNavigation.value?.documentId == mapper.documentId &&
                _pendingNavigation.value?.pageIndex == speechPage) {
                _pendingNavigation.value = null
            }
            lastNavigatedPage = speechPage
            return
        }

        // 2. Same page already navigated for current reading stream
        if (lastNavigatedPage == speechPage) {
            return
        }

        // 3. Same page already pending for this document
        val currentPending = _pendingNavigation.value
        if (currentPending != null &&
            currentPending.documentId == mapper.documentId &&
            currentPending.pageIndex == speechPage) {
            return
        }

        // 4. Issue one-shot navigation request (or replace older pending target)
        val request = PendingPdfNavigation(documentId = mapper.documentId, pageIndex = speechPage)
        _pendingNavigation.value = request
        lastNavigatedPage = speechPage

        executeNavigation(request)
    }

    private fun executeNavigation(request: PendingPdfNavigation) {
        val nav = navigator ?: return
        val mapper = pdfMapper ?: return
        if (request.documentId != mapper.documentId) return
        try {
            nav.navigateToPage(request.pageIndex)
        } catch (t: Throwable) {
            // Visual navigation failure must not crash or modify reading state
            Log.e(TAG, "Visual navigation to page ${request.pageIndex} failed safely", t)
        }
    }

    companion object {
        private const val TAG = "PdfNavigationCoordinator"
    }
}
